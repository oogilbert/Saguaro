package oog.mega.saguaro.mode.scoremax;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.gun.ShotSolution;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveLog;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import oog.mega.saguaro.movement.MovementController;
import oog.mega.saguaro.movement.MovementEngine;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.PathWaveIntersection;
import robocode.Rules;

final class ShotPlanner {
    static final double SHADOW_FIRE_POWER = 0.1;
    static final double MIN_FIRE_POWER = 0.1;
    static final double MAX_FIRE_POWER = 3.0;
    private static final double SHADOW_GF_SCAN_STEP = 0.04;
    private static final double SHADOW_CANDIDATE_EPS = 1e-9;
    private static final double SHADOW_REACHABILITY_EPSILON = 2.5e-3;
    interface ScoreEvaluator {
        ShotScore scoreShot(CandidatePath path,
                            List<PathWaveIntersection> pathIntersections,
                            ShotSelection selection,
                            double currentOurEnergy);
    }

    static final class ShotScore {
        final double score;
        final double absoluteScore;

        ShotScore(double score, double absoluteScore) {
            this.score = score;
            this.absoluteScore = absoluteScore;
        }
    }

    static final class ShotSelection {
        final double power;
        final double firingAngle;
        final double hitProbability;
        final long fireTime;
        final long hitTime;
        final double bulletDamage;
        final double expectedDamageDealt;
        final double adjustedExpectedBulletDamageTaken;
        final double adjustedExpectedEnemyEnergyGain;
        final double firingEnergyDelta;
        final double score;
        final double absoluteScore;
        final double continuationFirePower;
        final boolean shadowShot;
        final Map<Wave, Double> enemyWaveHitProbabilities;

        ShotSelection(double power, double firingAngle,
                      double hitProbability,
                      long fireTime,
                      long hitTime,
                      double bulletDamage,
                      double expectedDamageDealt,
                      double adjustedExpectedBulletDamageTaken,
                      double adjustedExpectedEnemyEnergyGain,
                      double firingEnergyDelta,
                      double score,
                      double absoluteScore,
                      double continuationFirePower,
                      boolean shadowShot,
                      Map<Wave, Double> enemyWaveHitProbabilities) {
            this.power = power;
            this.firingAngle = firingAngle;
            this.hitProbability = hitProbability;
            this.fireTime = fireTime;
            this.hitTime = hitTime;
            this.bulletDamage = bulletDamage;
            this.expectedDamageDealt = expectedDamageDealt;
            this.adjustedExpectedBulletDamageTaken = adjustedExpectedBulletDamageTaken;
            this.adjustedExpectedEnemyEnergyGain = adjustedExpectedEnemyEnergyGain;
            this.firingEnergyDelta = firingEnergyDelta;
            this.score = score;
            this.absoluteScore = absoluteScore;
            this.continuationFirePower = continuationFirePower;
            this.shadowShot = shadowShot;
            this.enemyWaveHitProbabilities = enemyWaveHitProbabilities == null
                    ? new IdentityHashMap<Wave, Double>()
                    : enemyWaveHitProbabilities;
        }

        ShotSelection withScore(ShotScore shotScore) {
            if (shotScore == null) {
                return this;
            }
            return new ShotSelection(
                    power,
                    firingAngle,
                    hitProbability,
                    fireTime,
                    hitTime,
                    bulletDamage,
                    expectedDamageDealt,
                    adjustedExpectedBulletDamageTaken,
                    adjustedExpectedEnemyEnergyGain,
                    firingEnergyDelta,
                    shotScore.score,
                    shotScore.absoluteScore,
                    continuationFirePower,
                    shadowShot,
                    enemyWaveHitProbabilities);
        }

        boolean hasFiniteScore() {
            return Double.isFinite(score) && Double.isFinite(absoluteScore);
        }
    }

    private static final class CollisionEffects {
        final double bulletSurvivalProbability;
        final double expectedBulletDamageTaken;
        final double expectedEnemyEnergyGain;
        final Map<Wave, Double> enemyWaveHitProbabilities;

        CollisionEffects(double bulletSurvivalProbability,
                         double expectedBulletDamageTaken,
                         double expectedEnemyEnergyGain,
                         Map<Wave, Double> enemyWaveHitProbabilities) {
            this.bulletSurvivalProbability = bulletSurvivalProbability;
            this.expectedBulletDamageTaken = expectedBulletDamageTaken;
            this.expectedEnemyEnergyGain = expectedEnemyEnergyGain;
            this.enemyWaveHitProbabilities = enemyWaveHitProbabilities == null
                    ? new IdentityHashMap<Wave, Double>()
                    : enemyWaveHitProbabilities;
        }
    }

    private final MovementController movement;
    private final GunController gun;
    private final ScoreEvaluator scoreEvaluator;
    private final double defaultContinuationPower;
    private boolean scoringWaveCacheValid;
    private CandidatePath scoringWaveCachePath;
    private int scoringWaveCacheTickOffset;
    private List<Wave> scoringWaveCacheWaves;

    ShotPlanner(MovementController movement,
                GunController gun,
                ScoreEvaluator scoreEvaluator,
                double defaultContinuationPower) {
        this.movement = movement;
        this.gun = gun;
        this.scoreEvaluator = scoreEvaluator;
        this.defaultContinuationPower = defaultContinuationPower;
    }

    // Selects the best shot option for a given path. Establishes a no-fire baseline, then
    // compares against a shadow shot (low-power bullet aimed to block incoming enemy waves)
    // and an offensive shot (full-power aimed to hit). If a forcedShot is provided, only that
    // option is evaluated. Returns the highest-scoring ShotSelection.
    ShotSelection chooseBestShot(CandidatePath path,
                                 double shooterX, double shooterY,
                                 EnemyInfo.PredictedPosition enemyAtFireTime,
                                 double expectedBulletDamageTaken,
                                 double expectedEnemyEnergyGain,
                                 double availableEnergy,
                                 double gunHeadingAtDecision,
                                 double currentOurEnergy,
                                 int ticksUntilFire,
                                 long fireTime,
                                 List<PathWaveIntersection> pathIntersections,
                                 MovementEngine.PlannedShot forcedShot,
                                 double enemyEnergyForScoring,
                                 double offensivePower,
                                 boolean allowShadow,
                                 boolean allowOffensive,
                                 boolean skipNoFire) {
        double scoreMaxPower = forcedShot == null
                ? Math.max(0.0, Math.min(availableEnergy, Math.min(MAX_FIRE_POWER, offensivePower)))
                : 0.0;
        ShotSelection bestSelection;
        if (skipNoFire) {
            bestSelection = new ShotSelection(
                    0.0, Double.NaN, 0.0, fireTime, fireTime, 0.0, 0.0,
                    expectedBulletDamageTaken, expectedEnemyEnergyGain, 0.0,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    0.0, false, baseWaveHitProbabilities(pathIntersections));
        } else {
            ShotSelection noFireCandidate = new ShotSelection(
                    0.0, Double.NaN, 0.0, fireTime, fireTime, 0.0, 0.0,
                    expectedBulletDamageTaken, expectedEnemyEnergyGain, 0.0,
                    0.0, 0.0,
                    0.0, false, baseWaveHitProbabilities(pathIntersections));
            ShotScore noFireScore = scoreEvaluator.scoreShot(path, pathIntersections, noFireCandidate, currentOurEnergy);
            bestSelection = noFireCandidate.withScore(noFireScore);
        }

        if (forcedShot == null && !allowShadow && !allowOffensive) {
            return bestSelection;
        }

        if (availableEnergy < MIN_FIRE_POWER) {
            return bestSelection;
        }

        List<Wave> enemyWavesAtFireTime = getScoringWavesAtFireTime(path, ticksUntilFire);
        if (forcedShot != null) {
            if (forcedShot.power > availableEnergy) {
                return new ShotSelection(
                        0.0, Double.NaN, 0.0, fireTime, fireTime, 0.0, 0.0,
                        expectedBulletDamageTaken, expectedEnemyEnergyGain, 0.0,
                        Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                        0.0, false, baseWaveHitProbabilities(pathIntersections));
            }
            return evaluateShotAtPower(
                    path, forcedShot.power, shooterX, shooterY, enemyAtFireTime,
                    expectedBulletDamageTaken, expectedEnemyEnergyGain,
                    gunHeadingAtDecision, currentOurEnergy, ticksUntilFire,
                    fireTime, enemyWavesAtFireTime, pathIntersections,
                    forcedShot.firingAngle, enemyEnergyForScoring, forcedShot.power, false);
        } else {
            if (allowShadow && SHADOW_FIRE_POWER <= availableEnergy) {
                double shadowFiringAngle = chooseShadowFiringAngle(
                        path,
                        shooterX,
                        shooterY,
                        enemyAtFireTime,
                        gunHeadingAtDecision,
                        ticksUntilFire,
                        fireTime,
                        pathIntersections);
                if (!Double.isNaN(shadowFiringAngle)) {
                    ShotSelection shadowCandidate = evaluateShotAtPower(
                            path, SHADOW_FIRE_POWER, shooterX, shooterY, enemyAtFireTime,
                            expectedBulletDamageTaken, expectedEnemyEnergyGain,
                            gunHeadingAtDecision, currentOurEnergy, ticksUntilFire,
                            fireTime, enemyWavesAtFireTime, pathIntersections,
                            shadowFiringAngle, enemyEnergyForScoring, scoreMaxPower, true);
                    if (shadowCandidate.score > bestSelection.score) {
                        bestSelection = shadowCandidate;
                    }
                }
            }

            if (allowOffensive && scoreMaxPower >= MIN_FIRE_POWER && scoreMaxPower <= availableEnergy) {
                ShotSelection scoreMaxCandidate = evaluateShotAtPower(
                        path, scoreMaxPower, shooterX, shooterY, enemyAtFireTime,
                        expectedBulletDamageTaken, expectedEnemyEnergyGain,
                        gunHeadingAtDecision, currentOurEnergy, ticksUntilFire,
                        fireTime, enemyWavesAtFireTime, pathIntersections,
                        Double.NaN, enemyEnergyForScoring, scoreMaxPower, false);
                if (scoreMaxCandidate.score > bestSelection.score) {
                    bestSelection = scoreMaxCandidate;
                }
            }
        }

        return bestSelection;
    }

    double estimateContinuationFirePower(double availableEnergy) {
        // Temporary fixed offensive baseline: compare only no-fire, 0.1, and 2.0.
        double fixedPower = Math.min(2.0, Math.min(MAX_FIRE_POWER, availableEnergy));
        return fixedPower >= MIN_FIRE_POWER ? fixedPower : 0.0;
    }

    static double targetingDataHitRateScale() {
        double maturity = Math.min(
                1.0,
                WaveLog.getTargetingSampleCount()
                        / (double) BotConfig.Gun.TARGETING_DATA_FULL_CONFIDENCE_SAMPLES);
        return BotConfig.Gun.MIN_TARGETING_DATA_HIT_RATE_SCALE
                + (1.0 - BotConfig.Gun.MIN_TARGETING_DATA_HIT_RATE_SCALE) * maturity;
    }

    // Evaluates a single shot at a specific power level. Queries the gun for the optimal
    // (or forced) firing angle, computes raw hit probability, adjusts for bullet-bullet
    // collisions via evaluateCollisionEffects, then delegates to the ScoreEvaluator for
    // final scoring.
    private ShotSelection evaluateShotAtPower(CandidatePath path,
                                              double power,
                                              double shooterX, double shooterY,
                                              EnemyInfo.PredictedPosition enemyAtFireTime,
                                              double expectedBulletDamageTaken,
                                              double expectedEnemyEnergyGain,
                                              double gunHeadingAtDecision,
                                              double currentOurEnergy,
                                              int ticksUntilFire,
                                              long fireTime,
                                              List<Wave> enemyWavesAtFireTime,
                                              List<PathWaveIntersection> pathIntersections,
                                              double forcedFiringAngle,
                                              double enemyEnergyForScoring,
                                              double continuationFirePower,
                                              boolean shadowShot) {
        if (enemyAtFireTime == null) {
            return new ShotSelection(
                    0.0, Double.NaN, 0.0, fireTime, fireTime, 0.0, 0.0,
                    expectedBulletDamageTaken, expectedEnemyEnergyGain, 0.0,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    0.0, false, baseWaveHitProbabilities(pathIntersections));
        }
        ShotSolution shotSolution;
        if (Double.isNaN(forcedFiringAngle)) {
            shotSolution = gun.selectOptimalShotFromPosition(
                    shooterX, shooterY, enemyAtFireTime.x, enemyAtFireTime.y, power,
                    gunHeadingAtDecision, ticksUntilFire);
        } else {
            shotSolution = gun.evaluateShotAtAngleFromPosition(
                    shooterX, shooterY, enemyAtFireTime.x, enemyAtFireTime.y, power,
                    forcedFiringAngle, gunHeadingAtDecision, ticksUntilFire);
        }
        double rawExpectedDamage = shotSolution.expectedDamage;
        double firingAngle = shotSolution.firingAngle;
        if (!Double.isNaN(forcedFiringAngle) && Double.isNaN(firingAngle)) {
            return new ShotSelection(
                    0.0, Double.NaN, 0.0, fireTime, fireTime, 0.0, 0.0,
                    expectedBulletDamageTaken, expectedEnemyEnergyGain, 0.0,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    0.0, false, baseWaveHitProbabilities(pathIntersections));
        }
        Wave plannedWave = Double.isNaN(firingAngle)
                ? null
                : new Wave(shooterX, shooterY, Wave.bulletSpeed(power), fireTime, false, firingAngle);

        double hitProbabilityNoShadow = hitProbabilityFromExpectedDamage(rawExpectedDamage, power);
        CollisionEffects collisionEffects = evaluateCollisionEffects(
                plannedWave,
                enemyAtFireTime,
                pathIntersections,
                enemyWavesAtFireTime,
                expectedBulletDamageTaken,
                expectedEnemyEnergyGain);
        double hitProbability = hitProbabilityNoShadow * collisionEffects.bulletSurvivalProbability;
        double bulletDamage = Rules.getBulletDamage(power);
        double expectedDamageDealt = hitProbability * Math.min(bulletDamage, enemyEnergyForScoring);
        double firingEnergyDelta = -power + hitProbability * 3.0 * power;
        long hitTime = fireTime;
        if (plannedWave != null) {
            double distanceToPredictedTarget = Point2D.distance(
                    plannedWave.originX, plannedWave.originY, enemyAtFireTime.x, enemyAtFireTime.y);
            hitTime += (long) Math.ceil(distanceToPredictedTarget / plannedWave.speed);
        }
        ShotSelection candidate = new ShotSelection(
                power,
                firingAngle,
                hitProbability,
                fireTime,
                hitTime,
                bulletDamage,
                expectedDamageDealt,
                collisionEffects.expectedBulletDamageTaken,
                collisionEffects.expectedEnemyEnergyGain,
                firingEnergyDelta,
                0.0,
                0.0,
                continuationFirePower,
                shadowShot,
                collisionEffects.enemyWaveHitProbabilities);
        ShotScore shotScore = scoreEvaluator.scoreShot(path, pathIntersections, candidate, currentOurEnergy);
        return candidate.withScore(shotScore);
    }

    // Finds the best firing angle for a shadow bullet — a min-power bullet aimed to collide
    // with the first incoming enemy wave, blocking the most dangerous GF region. Scans
    // candidate GFs within the exposed intervals at first contact, projects each to a firing
    // angle, checks gun reachability, and picks the angle that blocks the most probability mass.
    private double chooseShadowFiringAngle(CandidatePath path,
                                           double shooterX,
                                           double shooterY,
                                           EnemyInfo.PredictedPosition enemyAtFireTime,
                                           double gunHeadingAtDecision,
                                           int ticksUntilFire,
                                           long fireTime,
                                           List<PathWaveIntersection> pathIntersections) {
        if (path == null
                || enemyAtFireTime == null
                || pathIntersections == null
                || pathIntersections.isEmpty()) {
            return Double.NaN;
        }

        PathWaveIntersection firstIntersection = findFirstFutureIntersection(
                pathIntersections, fireTime);
        if (firstIntersection == null) {
            return Double.NaN;
        }

        List<BulletShadowUtil.WeightedGfInterval> candidateIntervals =
                firstContactCandidateIntervals(path, firstIntersection);
        if (candidateIntervals.isEmpty()) {
            return Double.NaN;
        }

        double firstWaveRadius = firstContactShadowRadius(path, firstIntersection);
        if (!(firstWaveRadius > 0.0)) {
            return Double.NaN;
        }

        double distanceToPredictedTarget = Point2D.distance(
                shooterX, shooterY, enemyAtFireTime.x, enemyAtFireTime.y);
        long hitTimeCeiling = fireTime + (long) Math.ceil(distanceToPredictedTarget / Wave.bulletSpeed(SHADOW_FIRE_POWER));

        if (ticksUntilFire <= 0) {
            double fireNowAngle = MathUtils.normalizeAngle(gunHeadingAtDecision);
            double blockedMass = blockedShadowMassForFiringAngle(
                    shooterX,
                    shooterY,
                    fireTime,
                    hitTimeCeiling,
                    firstIntersection.firstContactTime,
                    firstIntersection,
                    candidateIntervals,
                    fireNowAngle);
            return blockedMass > SHADOW_CANDIDATE_EPS ? fireNowAngle : Double.NaN;
        }

        double bestBlockedMass = Double.NEGATIVE_INFINITY;
        double bestFiringAngle = Double.NaN;
        for (double candidateGf : buildShadowAimCandidateGfs(candidateIntervals)) {
            double waveAngle = firstIntersection.referenceBearing + candidateGf * firstIntersection.mea;
            double targetX = firstIntersection.wave.originX + FastTrig.sin(waveAngle) * firstWaveRadius;
            double targetY = firstIntersection.wave.originY + FastTrig.cos(waveAngle) * firstWaveRadius;
            double desiredFiringAngle = Math.atan2(targetX - shooterX, targetY - shooterY);
            double reachableFiringAngle = clampReachableFiringAngle(
                    desiredFiringAngle, gunHeadingAtDecision, ticksUntilFire);
            if (Double.isNaN(reachableFiringAngle)) {
                continue;
            }

            double blockedMass = blockedShadowMassForFiringAngle(
                    shooterX,
                    shooterY,
                    fireTime,
                    hitTimeCeiling,
                    firstIntersection.firstContactTime,
                    firstIntersection,
                    candidateIntervals,
                    reachableFiringAngle);
            if (blockedMass > bestBlockedMass + SHADOW_CANDIDATE_EPS) {
                bestBlockedMass = blockedMass;
                bestFiringAngle = reachableFiringAngle;
            }
        }

        return bestBlockedMass > SHADOW_CANDIDATE_EPS ? bestFiringAngle : Double.NaN;
    }

    private static List<BulletShadowUtil.WeightedGfInterval> firstContactCandidateIntervals(
            CandidatePath path,
            PathWaveIntersection intersection) {
        if (path == null || path.trajectory == null || intersection == null) {
            return java.util.Collections.emptyList();
        }
        PhysicsUtil.PositionState contactState = path.trajectory.stateAt((int) (intersection.firstContactTime - path.startTime));
        double innerRadius = Math.max(0.0, intersection.wave.getRadius(intersection.firstContactTime));
        double outerRadius = innerRadius + intersection.wave.speed;
        double[] angularInterval = RobotHitbox.annulusAngularInterval(
                intersection.wave.originX,
                intersection.wave.originY,
                innerRadius,
                outerRadius,
                contactState.x,
                contactState.y);
        if (angularInterval == null) {
            return java.util.Collections.emptyList();
        }
        double[] gfInterval = RobotHitbox.toGuessFactorInterval(
                angularInterval[0],
                angularInterval[1],
                intersection.referenceBearing,
                intersection.mea);
        return BulletShadowUtil.mergeAndClipWeightedIntervals(
                intersection.exposedGfIntervals,
                gfInterval[0],
                gfInterval[1]);
    }

    private static double firstContactShadowRadius(CandidatePath path,
                                                   PathWaveIntersection intersection) {
        if (path == null || path.trajectory == null || intersection == null) {
            return Double.NaN;
        }
        PhysicsUtil.PositionState contactState = path.trajectory.stateAt((int) (intersection.firstContactTime - path.startTime));
        double innerRadius = Math.max(0.0, intersection.wave.getRadius(intersection.firstContactTime));
        double outerRadius = innerRadius + intersection.wave.speed;
        double contactRadius = intersection.wave.minDistanceToBody(contactState.x, contactState.y);
        return Math.max(innerRadius, Math.min(outerRadius, contactRadius));
    }

    private static PathWaveIntersection findFirstFutureIntersection(List<PathWaveIntersection> pathIntersections,
                                                                    long fireTime) {
        for (PathWaveIntersection intersection : pathIntersections) {
            if (intersection != null
                    && intersection.firstContactTime > fireTime) {
                return intersection;
            }
        }
        return null;
    }

    private double blockedShadowMassForFiringAngle(double shooterX,
                                                   double shooterY,
                                                   long fireTime,
                                                   long hitTimeCeiling,
                                                   long shadowCutoffTime,
                                                   PathWaveIntersection firstIntersection,
                                                   List<BulletShadowUtil.WeightedGfInterval> candidateIntervals,
                                                   double firingAngle) {
        Wave plannedWave = new Wave(
                shooterX, shooterY, Wave.bulletSpeed(SHADOW_FIRE_POWER), fireTime, false, firingAngle);
        List<BulletShadowUtil.WeightedGfInterval> activeShadowIntervals = activeCollisionShadowIntervals(
                plannedWave,
                hitTimeCeiling,
                shadowCutoffTime,
                firstIntersection.wave,
                firstIntersection.referenceBearing,
                firstIntersection.mea);
        if (activeShadowIntervals.isEmpty()) {
            return 0.0;
        }
        return overlapWeightedProbabilityMass(
                firstIntersection.distribution,
                candidateIntervals,
                activeShadowIntervals);
    }

    private static List<Double> buildShadowAimCandidateGfs(List<BulletShadowUtil.WeightedGfInterval> intervals) {
        List<Double> candidates = new ArrayList<>();
        for (BulletShadowUtil.WeightedGfInterval interval : intervals) {
            double start = interval.startGf;
            double end = interval.endGf;
            if (!(end > start)) {
                continue;
            }
            appendUniqueShadowCandidate(candidates, start);
            appendUniqueShadowCandidate(candidates, end);
            appendUniqueShadowCandidate(candidates, (start + end) * 0.5);
            for (double gf = start + SHADOW_GF_SCAN_STEP; gf < end - SHADOW_CANDIDATE_EPS; gf += SHADOW_GF_SCAN_STEP) {
                appendUniqueShadowCandidate(candidates, gf);
            }
        }
        return candidates;
    }

    private static void appendUniqueShadowCandidate(List<Double> candidates, double gf) {
        for (double existing : candidates) {
            if (Math.abs(existing - gf) <= SHADOW_CANDIDATE_EPS) {
                return;
            }
        }
        candidates.add(gf);
    }

    private static double clampReachableFiringAngle(double desiredFiringAngle,
                                                    double gunHeadingAtDecision,
                                                    int ticksUntilFire) {
        double center = MathUtils.normalizeAngle(gunHeadingAtDecision);
        double turnBudget = Math.max(0, ticksUntilFire) * Rules.GUN_TURN_RATE_RADIANS;
        if (turnBudget >= Math.PI) {
            return MathUtils.normalizeAngle(desiredFiringAngle);
        }

        double delta = MathUtils.normalizeAngle(desiredFiringAngle - center);
        if (Math.abs(delta) <= turnBudget + SHADOW_REACHABILITY_EPSILON) {
            return MathUtils.normalizeAngle(desiredFiringAngle);
        }
        return Double.NaN;
    }

    // Computes how our planned bullet interacts with enemy waves in flight. Two effects:
    // 1. Our bullet may be destroyed by an enemy bullet before reaching the target,
    //    reducing our effective hit probability (bulletSurvivalProbability).
    // 2. Our bullet casts a shadow on enemy waves it crosses, reducing the probability
    //    of those waves hitting us (adjusting expectedBulletDamageTaken downward).
    private CollisionEffects evaluateCollisionEffects(Wave plannedWave,
                                                      EnemyInfo.PredictedPosition enemyAtFireTime,
                                                      List<PathWaveIntersection> pathIntersections,
                                                      List<Wave> enemyWavesAtFireTime,
                                                      double expectedBulletDamageTaken,
                                                      double expectedEnemyEnergyGain) {
        Map<Wave, Double> enemyWaveHitProbabilities = baseWaveHitProbabilities(pathIntersections);
        if (plannedWave == null || Double.isNaN(plannedWave.heading)) {
            return new CollisionEffects(
                    0.0,
                    expectedBulletDamageTaken,
                    expectedEnemyEnergyGain,
                    enemyWaveHitProbabilities);
        }

        double distanceToPredictedTarget = Point2D.distance(
                plannedWave.originX, plannedWave.originY, enemyAtFireTime.x, enemyAtFireTime.y);
        long hitTimeCeiling = plannedWave.fireTime + (long) Math.ceil(distanceToPredictedTarget / plannedWave.speed);
        long collisionCutoffTime = hitTimeCeiling + 1L;

        double bulletSurvivalProbability = 1.0;
        for (Wave enemyWave : enemyWavesAtFireTime) {
            double collisionProbability = collisionProbabilityForEnemyWave(
                    plannedWave,
                    hitTimeCeiling,
                    collisionCutoffTime,
                    enemyWave,
                    distributionForEnemyWave(enemyWave, pathIntersections));
            bulletSurvivalProbability *= (1.0 - collisionProbability);
            if (bulletSurvivalProbability <= 0.0) {
                bulletSurvivalProbability = 0.0;
                break;
            }
        }

        double adjustedExpectedBulletDamageTaken = expectedBulletDamageTaken;
        double adjustedExpectedEnemyEnergyGain = expectedEnemyEnergyGain;
        if (pathIntersections != null) {
            for (PathWaveIntersection intersection : pathIntersections) {
                if (intersection == null || plannedWave.fireTime >= intersection.firstContactTime) {
                    continue;
                }
                List<BulletShadowUtil.WeightedGfInterval> activeShadowIntervals = activeCollisionShadowIntervals(
                        plannedWave,
                        hitTimeCeiling,
                        collisionCutoffTime,
                        intersection.wave,
                        intersection.referenceBearing,
                        intersection.mea);
                if (activeShadowIntervals.isEmpty()) {
                    continue;
                }
                double blockedProbability = overlapWeightedProbabilityMass(
                        intersection.distribution,
                        intersection.exposedGfIntervals,
                        activeShadowIntervals);
                if (blockedProbability <= 0.0) {
                    continue;
                }
                double enemyFirePower = intersection.wave.getFirepower();
                double baseHitProbability = hitProbabilityForWave(enemyWaveHitProbabilities, intersection);
                enemyWaveHitProbabilities.put(
                        intersection.wave,
                        Math.max(0.0, baseHitProbability - blockedProbability));
                adjustedExpectedBulletDamageTaken -=
                        blockedProbability * Rules.getBulletDamage(enemyFirePower);
                adjustedExpectedEnemyEnergyGain -=
                        blockedProbability * 3.0 * enemyFirePower;
            }
        }

        return new CollisionEffects(
                Math.max(0.0, Math.min(1.0, bulletSurvivalProbability)),
                Math.max(0.0, adjustedExpectedBulletDamageTaken),
                Math.max(0.0, adjustedExpectedEnemyEnergyGain),
                enemyWaveHitProbabilities);
    }

    private double collisionProbabilityForEnemyWave(Wave plannedWave,
                                                    long hitTimeCeiling,
                                                    long collisionCutoffTime,
                                                    Wave enemyWave,
                                                    GuessFactorDistribution movementDistribution) {
        if (enemyWave.hasHit(plannedWave.originX, plannedWave.originY, plannedWave.fireTime)) {
            return 0.0;
        }
        List<BulletShadowUtil.WeightedGfInterval> activeShadowIntervals = activeCollisionShadowIntervals(
                plannedWave,
                hitTimeCeiling,
                collisionCutoffTime,
                enemyWave,
                Double.NaN,
                Double.NaN);
        if (activeShadowIntervals.isEmpty()) {
            return 0.0;
        }
        double collisionProbability = BulletShadowUtil.integrateWeightedIntervals(
                movementDistribution, activeShadowIntervals, -1.0, 1.0);
        return Math.max(0.0, Math.min(1.0, collisionProbability));
    }

    private List<BulletShadowUtil.WeightedGfInterval> activeCollisionShadowIntervals(Wave plannedWave,
                                                                                      long hitTimeCeiling,
                                                                                      long collisionCutoffTime,
                                                                                      Wave enemyWave,
                                                                                      double referenceBearingOverride,
                                                                                      double meaOverride) {
        if (enemyWave.fireTime > hitTimeCeiling) {
            return java.util.Collections.emptyList();
        }
        List<BulletShadowUtil.ShadowInterval> shadows =
                BulletShadowUtil.buildShadowsOnEnemyWave(enemyWave, plannedWave);
        if (shadows.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        double referenceBearing;
        double mea;
        if (Double.isFinite(referenceBearingOverride) && Double.isFinite(meaOverride)) {
            referenceBearing = referenceBearingOverride;
            mea = meaOverride;
        } else {
            referenceBearing = Math.atan2(
                    enemyWave.targetX - enemyWave.originX,
                    enemyWave.targetY - enemyWave.originY);
            mea = MathUtils.maxEscapeAngle(enemyWave.speed);
        }
        return BulletShadowUtil.mergeAndClipWeightedIntervals(
                BulletShadowUtil.activeGfIntervals(shadows, collisionCutoffTime, referenceBearing, mea),
                -1.0,
                1.0);
    }

    // Computes the probability mass in a distribution that falls within the overlap of two
    // sets of weighted GF intervals, using a merge-join over the sorted interval lists.
    private static double overlapWeightedProbabilityMass(
            GuessFactorDistribution distribution,
            List<BulletShadowUtil.WeightedGfInterval> baseIntervals,
            List<BulletShadowUtil.WeightedGfInterval> overlapIntervals) {
        if (baseIntervals == null || baseIntervals.isEmpty()
                || overlapIntervals == null || overlapIntervals.isEmpty()) {
            return 0.0;
        }
        List<BulletShadowUtil.WeightedGfInterval> mergedBase =
                BulletShadowUtil.mergeAndClipWeightedIntervals(baseIntervals, -1.0, 1.0);
        if (mergedBase.isEmpty()) {
            return 0.0;
        }
        List<BulletShadowUtil.WeightedGfInterval> mergedOverlap =
                BulletShadowUtil.mergeAndClipWeightedIntervals(overlapIntervals, -1.0, 1.0);
        if (mergedOverlap.isEmpty()) {
            return 0.0;
        }

        double mass = 0.0;
        int baseIndex = 0;
        int overlapIndex = 0;
        while (baseIndex < mergedBase.size() && overlapIndex < mergedOverlap.size()) {
            BulletShadowUtil.WeightedGfInterval baseInterval = mergedBase.get(baseIndex);
            BulletShadowUtil.WeightedGfInterval overlapInterval = mergedOverlap.get(overlapIndex);
            double start = Math.max(baseInterval.startGf, overlapInterval.startGf);
            double end = Math.min(baseInterval.endGf, overlapInterval.endGf);
            if (end > start) {
                double blockedWeight = Math.min(baseInterval.weight, overlapInterval.weight);
                if (blockedWeight > 0.0) {
                    mass += blockedWeight * distribution.integrate(start, end);
                }
            }

            if (baseInterval.endGf <= overlapInterval.endGf) {
                baseIndex++;
            } else {
                overlapIndex++;
            }
        }
        return mass;
    }

    private static Map<Wave, Double> baseWaveHitProbabilities(
            List<PathWaveIntersection> pathIntersections) {
        Map<Wave, Double> hitProbabilities = new IdentityHashMap<>();
        if (pathIntersections == null) {
            return hitProbabilities;
        }
        for (PathWaveIntersection intersection : pathIntersections) {
            if (intersection == null || intersection.wave == null) {
                continue;
            }
            hitProbabilities.put(intersection.wave, baseHitProbability(intersection));
        }
        return hitProbabilities;
    }

    private static double hitProbabilityForWave(Map<Wave, Double> hitProbabilities,
                                                PathWaveIntersection intersection) {
        if (intersection == null || intersection.wave == null || hitProbabilities == null) {
            return 0.0;
        }
        Double hitProbability = hitProbabilities.get(intersection.wave);
        return hitProbability != null ? hitProbability.doubleValue() : baseHitProbability(intersection);
    }

    private static double baseHitProbability(PathWaveIntersection intersection) {
        if (intersection == null
                || intersection.exposedGfIntervals == null
                || intersection.exposedGfIntervals.isEmpty()) {
            return 0.0;
        }
        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        BulletShadowUtil.integrateWeightedIntervals(
                                intersection.distribution,
                                intersection.exposedGfIntervals,
                                -1.0,
                                1.0)));
    }

    private GuessFactorDistribution distributionForEnemyWave(
            Wave enemyWave,
            List<PathWaveIntersection> pathIntersections) {
        if (enemyWave.fireTimeContext != null) {
            return movement.queryDistributionForEnemyWaveAtFireTime(enemyWave);
        }
        if (pathIntersections != null) {
            for (PathWaveIntersection intersection : pathIntersections) {
                if (intersection.wave == enemyWave && intersection.distribution != null) {
                    return intersection.distribution;
                }
            }
        }
        throw new IllegalStateException("Enemy wave missing fire-time distribution for shadow scoring");
    }

    private List<Wave> getScoringWavesAtFireTime(CandidatePath path,
                                                 int tickOffset) {
        if (scoringWaveCacheValid
                && scoringWaveCachePath == path
                && scoringWaveCacheTickOffset == tickOffset) {
            return scoringWaveCacheWaves;
        }
        List<Wave> waves = movement.getScoringWavesForPathState(path, tickOffset);
        scoringWaveCacheValid = true;
        scoringWaveCachePath = path;
        scoringWaveCacheTickOffset = tickOffset;
        scoringWaveCacheWaves = waves;
        return waves;
    }

    private static double hitProbabilityFromExpectedDamage(double expectedDamage, double firePower) {
        double bulletDamage = Rules.getBulletDamage(firePower);
        if (bulletDamage <= 0) {
            return 0.0;
        }
        double probability = expectedDamage / bulletDamage;
        return Math.max(0.0, Math.min(1.0, probability));
    }
}




