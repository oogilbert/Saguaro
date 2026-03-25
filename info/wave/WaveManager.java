package oog.mega.saguaro.info.wave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.math.GuessFactorDistributionHandle;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.RobotHitbox;
import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;

public class WaveManager {
    private static final double POWER_MATCH_TOLERANCE = 0.001;
    private static final double STRICT_ENEMY_WAVE_RADIUS_TOLERANCE = 1.0;
    private static final double DEFERRED_HIT_BY_BULLET_RADIUS_TOLERANCE = 1.5;
    private static final double BULLET_HIT_BULLET_POWER_TOLERANCE = 1e-6;
    private static final double BULLET_HIT_BULLET_RADIUS_EQUALITY_EPSILON = 0.5;
    private static final long WAVE_REMOVAL_GRACE_TICKS = 1L;

    private static class EnemyWaveMatch {
        public final Wave wave;
        public final double distanceError;
        public final int powerMatches;

        EnemyWaveMatch(Wave wave, double distanceError, int powerMatches) {
            this.wave = wave;
            this.distanceError = distanceError;
            this.powerMatches = powerMatches;
        }
    }

    private static final class MyWaveObservationState {
        long lastProcessedTime;
        final List<double[]> gfIntervals = new ArrayList<>();

        MyWaveObservationState(long lastProcessedTime) {
            this.lastProcessedTime = lastProcessedTime;
        }
    }

    private static final class PendingEnemyBulletHit {
        final double power;
        final double hitX;
        final double hitY;
        final long time;

        PendingEnemyBulletHit(double power, double hitX, double hitY, long time) {
            this.power = power;
            this.hitX = hitX;
            this.hitY = hitY;
            this.time = time;
        }
    }

    private Saguaro robot;
    private Info info;
    private final List<Wave> myWaves = new ArrayList<>();
    private final List<Wave> enemyWaves = new ArrayList<>();
    private final Set<Wave> loggedMyWaves = new HashSet<>();
    private final Map<Wave, MyWaveObservationState> myWaveObservationStates = new HashMap<>();
    private PendingEnemyBulletHit pendingEnemyBulletHit;
    private double battlefieldWidth;
    private double battlefieldHeight;

    public void init(Saguaro robot, double battlefieldWidth, double battlefieldHeight, Info info) {
        this.robot = robot;
        this.info = info;
        this.battlefieldWidth = battlefieldWidth;
        this.battlefieldHeight = battlefieldHeight;
        myWaves.clear();
        enemyWaves.clear();
        loggedMyWaves.clear();
        myWaveObservationStates.clear();
        pendingEnemyBulletHit = null;
    }

    public Wave onBulletFired(Bullet bullet) {
        long fireTime = robot.getTime();
        Wave myWave = new Wave(
                robot.getX(),
                robot.getY(),
                bullet.getVelocity(),
                fireTime,
                false,
                bullet.getHeadingRadians(),
                bullet
        );
        myWave.allowTargetingModelUpdate = info.getObservationProfile().shouldUpdateTargetingModel();
        EnemyInfo enemy = info.getEnemy();
        if (enemy != null && enemy.alive && enemy.seenThisRound) {
            EnemyInfo.PredictedPosition enemyAtFireTime = enemy.predictPositionAtTime(
                    fireTime, battlefieldWidth, battlefieldHeight);
            myWave.targetX = enemyAtFireTime.x;
            myWave.targetY = enemyAtFireTime.y;
            myWave.fireTimeContext = WaveContextFeatures.createWaveContext(
                    myWave.originX,
                    myWave.originY,
                    myWave.speed,
                    fireTime,
                    enemyAtFireTime.x,
                    enemyAtFireTime.y,
                    enemyAtFireTime.heading,
                    enemyAtFireTime.velocity,
                    enemyAtFireTime.accelerationSign,
                    enemyAtFireTime.ticksSinceVelocityReversal,
                    enemyAtFireTime.ticksSinceDecel,
                    enemyAtFireTime.lastNonZeroLateralDirectionSign,
                    battlefieldWidth,
                    battlefieldHeight,
                    myWaves,
                    null);
            myWave.fireTimeDistributionHandle = GuessFactorDistributionHandle.orNull(
                    info.getObservationProfile().createGunDistribution(myWave.fireTimeContext));
        }
        myWaves.add(myWave);
        myWaveObservationStates.put(myWave, new MyWaveObservationState(fireTime));
        for (Wave enemyWave : enemyWaves) {
            if (hasWaveReachedBot(enemyWave, myWave.originX, myWave.originY, fireTime)) {
                continue;
            }
            enemyWave.addShadowIntervals(BulletShadowUtil.buildShadowsOnEnemyWave(enemyWave, myWave));
        }
        return myWave;
    }

    public void addEnemyWave(Wave wave) {
        if (!wave.isEnemy || wave.isVirtual) {
            throw new IllegalArgumentException("WaveManager only tracks real enemy waves");
        }
        List<Wave> eligibleMyWaves = new ArrayList<>(myWaves.size());
        for (Wave myWave : myWaves) {
            if (hasWaveReachedBot(myWave, wave.originX, wave.originY, wave.fireTime)) {
                continue;
            }
            eligibleMyWaves.add(myWave);
        }
        wave.addShadowIntervals(BulletShadowUtil.buildShadowsOnEnemyWave(wave, eligibleMyWaves));
        enemyWaves.add(wave);
    }

    public Wave reconcilePendingEnemyBulletHit(long currentTime) {
        if (pendingEnemyBulletHit == null) {
            return null;
        }
        if (currentTime <= pendingEnemyBulletHit.time) {
            return null;
        }

        EnemyWaveMatch match = findBestEnemyWaveMatch(
                pendingEnemyBulletHit.power,
                pendingEnemyBulletHit.hitX,
                pendingEnemyBulletHit.hitY,
                pendingEnemyBulletHit.time,
                true,
                true);
        if (isAcceptableEnemyWaveMatch(match, DEFERRED_HIT_BY_BULLET_RADIUS_TOLERANCE)) {
            boolean updatedMovementModel =
                    logEnemyWaveMovementResult(match.wave, pendingEnemyBulletHit.hitX, pendingEnemyBulletHit.hitY);
            logFlattenerVisitForWave(match.wave, pendingEnemyBulletHit.hitX, pendingEnemyBulletHit.hitY);
            enemyWaves.remove(match.wave);
            if (updatedMovementModel) {
                refreshEnemyWaveDistributions();
            }
            pendingEnemyBulletHit = null;
            return match.wave;
        }

        pendingEnemyBulletHit = null;
        return null;
    }

    public void update() {
        long time = robot.getTime();
        double robotX = robot.getX();
        double robotY = robot.getY();
        double maxWaveDistance = Math.hypot(battlefieldWidth, battlefieldHeight);
        EnemyInfo enemy = info.getEnemy();

        logMyWaveResults(time);
        boolean removeWhenPassedEnemy = enemy != null && enemy.alive && enemy.seenThisRound;
        double enemyX = removeWhenPassedEnemy ? enemy.x : robotX;
        double enemyY = removeWhenPassedEnemy ? enemy.y : robotY;
        removePassedMyWaves(time, maxWaveDistance, enemyX, enemyY, removeWhenPassedEnemy);
        // Enemy waves that already passed our bot are no longer relevant for danger or matching.
        removePassedEnemyWaves(time, maxWaveDistance, robotX, robotY);
    }

    private void logMyWaveResults(long time) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return;
        }
        for (Wave wave : myWaves) {
            if (loggedMyWaves.contains(wave)) {
                continue;
            }
            if (wave.fireTimeContext == null
                    || Double.isNaN(wave.targetX) || Double.isNaN(wave.targetY)) {
                continue;
            }
            accumulateMyWaveOverlapInterval(wave, enemy, time);
            if (wave.hasPassed(enemy.x, enemy.y, time)) {
                finalizeMyWaveGunObservation(wave);
                info.onMyWavePassedEnemy(wave);
            }
        }
    }

    private void accumulateMyWaveOverlapInterval(Wave wave, EnemyInfo enemy, long time) {
        if (time <= wave.fireTime) {
            return;
        }
        if (wave.fireTimeContext == null
                || Double.isNaN(wave.targetX) || Double.isNaN(wave.targetY)) {
            return;
        }
        MyWaveObservationState state = myWaveObservationStates.get(wave);
        if (state == null) {
            state = new MyWaveObservationState(wave.fireTime);
            myWaveObservationStates.put(wave, state);
        }
        if (time <= state.lastProcessedTime) {
            return;
        }
        double innerRadius = wave.getRadius(state.lastProcessedTime);
        double outerRadius = wave.getRadius(time);
        if (outerRadius <= innerRadius) {
            state.lastProcessedTime = time;
            return;
        }
        double[] angularInterval = RobotHitbox.annulusAngularInterval(
                wave.originX,
                wave.originY,
                innerRadius,
                outerRadius,
                enemy.x,
                enemy.y);
        if (angularInterval != null) {
            double referenceBearing = Math.atan2(
                    wave.targetX - wave.originX, wave.targetY - wave.originY);
            double mea = MathUtils.maxEscapeAngle(wave.speed);
            state.gfIntervals.add(RobotHitbox.toGuessFactorInterval(
                    angularInterval[0],
                    angularInterval[1],
                    referenceBearing,
                    mea));
        }
        state.lastProcessedTime = time;
    }

    private void finalizeMyWaveGunObservation(Wave wave) {
        if (loggedMyWaves.contains(wave)) {
            return;
        }
        loggedMyWaves.add(wave);
        if (wave.fireTimeContext == null
                || Double.isNaN(wave.targetX) || Double.isNaN(wave.targetY)) {
            return;
        }
        MyWaveObservationState state = myWaveObservationStates.get(wave);
        if (state == null || state.gfIntervals.isEmpty()) {
            return;
        }
        List<double[]> mergedIntervals = BulletShadowUtil.mergeAndClipIntervals(state.gfIntervals, -1.0, 1.0);
        if (mergedIntervals.isEmpty()) {
            return;
        }
        double gfMin = mergedIntervals.get(0)[0];
        double gfMax = mergedIntervals.get(mergedIntervals.size() - 1)[1];
        info.getObservationProfile().logGunInterval(
                wave.fireTimeContext,
                gfMin,
                gfMax,
                true,
                wave.allowTargetingModelUpdate);
    }

    private boolean logEnemyWaveMovementResult(Wave wave, double hitX, double hitY) {
        double hitBearing = Math.atan2(hitX - wave.originX, hitY - wave.originY);
        return logEnemyWaveMovementResultForBearing(wave, hitBearing);
    }

    private boolean logEnemyWaveMovementResultForBearing(Wave wave, double hitBearing) {
        if (wave.fireTimeContext == null
                || Double.isNaN(wave.targetX) || Double.isNaN(wave.targetY)) {
            return false;
        }
        if (!wave.allowMovementObservationLogging) {
            return false;
        }
        double referenceBearing = Math.atan2(
                wave.targetX - wave.originX, wave.targetY - wave.originY);
        double mea = MathUtils.maxEscapeAngle(wave.speed);
        double gf = MathUtils.angleToGf(referenceBearing, hitBearing, mea);
        gf = Math.max(-1.0, Math.min(1.0, gf));
        info.getObservationProfile().logMovementResult(
                wave.fireTimeContext,
                gf,
                true,
                wave.allowMovementModelUpdate);
        return wave.allowMovementModelUpdate;
    }

    private void refreshEnemyWaveDistributions() {
        // Enemy-wave danger uses a per-wave fire-time snapshot. Refresh remaining
        // in-flight enemy waves after a movement-model update so live danger/rendering
        // picks up the new targeting estimate immediately.
        for (Wave wave : enemyWaves) {
            if (wave.fireTimeContext == null) {
                continue;
            }
            wave.fireTimeDistributionHandle = GuessFactorDistributionHandle.orNull(
                    info.getObservationProfile().createMovementDistribution(wave.fireTimeContext));
        }
    }

    private void logFlattenerVisitForWave(Wave wave, double robotX, double robotY) {
        if (wave.fireTimeContext == null
                || Double.isNaN(wave.targetX) || Double.isNaN(wave.targetY)) {
            return;
        }
        double referenceBearing = Math.atan2(
                wave.targetX - wave.originX, wave.targetY - wave.originY);
        double ourBearing = Math.atan2(
                robotX - wave.originX, robotY - wave.originY);
        double mea = MathUtils.maxEscapeAngle(wave.speed);
        double ourGf = MathUtils.angleToGf(referenceBearing, ourBearing, mea);
        ourGf = Math.max(-1.0, Math.min(1.0, ourGf));
        WaveLog.logFlattenerVisit(wave.fireTimeContext, ourGf);
    }

    private void removePassedMyWaves(long time, double maxDistance,
                                     double targetX, double targetY, boolean removeWhenPassedTarget) {
        Iterator<Wave> it = myWaves.iterator();
        while (it.hasNext()) {
            Wave wave = it.next();
            boolean passedTarget = false;
            if (removeWhenPassedTarget) {
                long removalCheckTime = time - WAVE_REMOVAL_GRACE_TICKS;
                if (removalCheckTime > wave.fireTime) {
                    passedTarget = wave.hasPassed(targetX, targetY, removalCheckTime);
                }
            }
            if (passedTarget || wave.getRadius(time) > maxDistance) {
                it.remove();
                clearMyWaveObservationState(wave);
            }
        }
    }

    private void removePassedEnemyWaves(long time, double maxDistance, double robotX, double robotY) {
        long removalCheckTime = time - WAVE_REMOVAL_GRACE_TICKS;
        Iterator<Wave> it = enemyWaves.iterator();
        while (it.hasNext()) {
            Wave wave = it.next();
            boolean passedRobot = removalCheckTime > wave.fireTime
                    && wave.hasPassed(robotX, robotY, removalCheckTime);
            if (passedRobot || wave.getRadius(time) > maxDistance) {
                if (passedRobot) {
                    logFlattenerVisitForWave(wave, robotX, robotY);
                    info.onEnemyWavePassedRobot(wave);
                }
                it.remove();
            }
        }
    }

    public Wave validateAndRemoveMyWave(BulletHitEvent e) {
        Wave matchedWave = findMyWave(e.getBullet());
        if (matchedWave != null) {
            EnemyInfo enemy = info.getEnemy();
            if (enemy != null && enemy.alive && enemy.seenThisRound) {
                accumulateMyWaveOverlapInterval(matchedWave, enemy, robot.getTime());
            }
            finalizeMyWaveGunObservation(matchedWave);
        }
        removeMyWave(matchedWave, "BulletHit");
        return matchedWave;
    }

    public Wave validateAndRemoveMyWave(BulletHitBulletEvent e) {
        Wave matchedWave = findMyWave(e.getBullet());
        removeMyWave(matchedWave, "BulletHitBullet");
        return matchedWave;
    }

    public Wave validateAndRemoveEnemyWave(HitByBulletEvent e) {
        Bullet bullet = e.getBullet();
        EnemyWaveMatch match = findBestEnemyWaveMatch(
                bullet.getPower(), bullet.getX(), bullet.getY(), e.getTime(), false, false);
        if (isAcceptableEnemyWaveMatch(match, STRICT_ENEMY_WAVE_RADIUS_TOLERANCE)) {
            boolean updatedMovementModel = logEnemyWaveMovementResult(match.wave, bullet.getX(), bullet.getY());
            logFlattenerVisitForWave(match.wave, bullet.getX(), bullet.getY());
            enemyWaves.remove(match.wave);
            if (updatedMovementModel) {
                refreshEnemyWaveDistributions();
            }
            pendingEnemyBulletHit = null;
            return match.wave;
        }
        pendingEnemyBulletHit = new PendingEnemyBulletHit(
                bullet.getPower(),
                bullet.getX(),
                bullet.getY(),
                e.getTime());
        return null;
    }

    public Wave validateAndRemoveEnemyWave(BulletHitBulletEvent e) {
        Bullet bullet = e.getHitBullet();
        EnemyWaveMatch match = findBestEnemyWaveMatchForBulletHitBullet(
                bullet.getPower(),
                bullet.getX(),
                bullet.getY(),
                e.getTime());
        if (match.wave != null) {
            boolean updatedMovementModel =
                    logEnemyWaveMovementResultForBearing(match.wave, bullet.getHeadingRadians());
            logFlattenerVisitForWave(match.wave, robot.getX(), robot.getY());
            enemyWaves.remove(match.wave);
            if (updatedMovementModel) {
                refreshEnemyWaveDistributions();
            }
            return match.wave;
        }
        return null;
    }

    private void removeMyWave(Wave matchedWave, String eventName) {
        if (matchedWave == null) {
            return;
        }
        myWaves.remove(matchedWave);
        clearMyWaveObservationState(matchedWave);
    }

    public List<Wave> getMyWaves() {
        return myWaves;
    }

    public List<Wave> getEnemyWaves() {
        return enemyWaves;
    }

    private static double radiusErrorForEvent(Wave wave, double hitX, double hitY, long time,
                                              boolean allowOneTickLag,
                                              boolean allowOneTickLead) {
        double distanceToHit = Math.hypot(hitX - wave.originX, hitY - wave.originY);
        double error = Math.abs(wave.getRadius(time) - distanceToHit);
        if (allowOneTickLag && time > wave.fireTime) {
            double previousTickError = Math.abs(wave.getRadius(time - 1) - distanceToHit);
            error = Math.min(error, previousTickError);
        }
        if (allowOneTickLead) {
            double nextTickError = Math.abs(wave.getRadius(time + 1) - distanceToHit);
            error = Math.min(error, nextTickError);
        }
        return error;
    }

    private Wave findMyWave(Bullet bullet) {
        for (Wave wave : myWaves) {
            if (bullet.equals(wave.bullet)) {
                return wave;
            }
        }
        return null;
    }

    private EnemyWaveMatch findBestEnemyWaveMatch(double power, double hitX, double hitY, long time,
                                                  boolean allowOneTickLag,
                                                  boolean allowOneTickLead) {
        Wave bestMatch = null;
        double bestDistanceError = Double.MAX_VALUE;
        int powerMatches = 0;

        for (Wave wave : enemyWaves) {
            if (Math.abs(wave.getFirepower() - power) > POWER_MATCH_TOLERANCE) {
                continue;
            }
            powerMatches++;
            double error = radiusErrorForEvent(wave, hitX, hitY, time, allowOneTickLag, allowOneTickLead);
            if (error < bestDistanceError) {
                bestDistanceError = error;
                bestMatch = wave;
            }
        }
        return new EnemyWaveMatch(bestMatch, bestDistanceError, powerMatches);
    }

    private EnemyWaveMatch findBestEnemyWaveMatchForBulletHitBullet(double power,
                                                                    double hitX,
                                                                    double hitY,
                                                                    long time) {
        Wave bestMatch = null;
        double bestDistanceError = Double.MAX_VALUE;
        double closestRadiusError = Double.MAX_VALUE;
        int powerMatches = 0;
        for (Wave wave : enemyWaves) {
            if (Math.abs(wave.getFirepower() - power) > BULLET_HIT_BULLET_POWER_TOLERANCE) {
                continue;
            }
            powerMatches++;
            if (time <= wave.fireTime) {
                continue;
            }
            double distanceToHit = Math.hypot(hitX - wave.originX, hitY - wave.originY);
            double previousTickError = Math.abs(wave.getRadius(time - 1) - distanceToHit);
            double twoTicksAgoError = time > wave.fireTime + 1
                    ? Math.abs(wave.getRadius(time - 2) - distanceToHit)
                    : Double.MAX_VALUE;
            double candidateError = Math.min(previousTickError, twoTicksAgoError);
            if (candidateError < closestRadiusError) {
                closestRadiusError = candidateError;
            }
            if (candidateError > BULLET_HIT_BULLET_RADIUS_EQUALITY_EPSILON) {
                continue;
            }
            if (candidateError < bestDistanceError) {
                bestDistanceError = candidateError;
                bestMatch = wave;
            }
        }
        return new EnemyWaveMatch(bestMatch, closestRadiusError, powerMatches);
    }

    private void clearMyWaveObservationState(Wave wave) {
        myWaveObservationStates.remove(wave);
        loggedMyWaves.remove(wave);
    }

    private static boolean isAcceptableEnemyWaveMatch(EnemyWaveMatch match, double radiusTolerance) {
        return match.wave != null && match.distanceError <= radiusTolerance;
    }

    private static boolean hasWaveReachedBot(Wave wave, double botX, double botY, long time) {
        return wave.hasHit(botX, botY, time);
    }

}

