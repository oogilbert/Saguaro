package oog.mega.saguaro.gun;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.ConstantDeltaPredictor;
import oog.mega.saguaro.math.DefaultDistribution;
import oog.mega.saguaro.math.GuessFactorDistributionHandle;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;
import robocode.Rules;

public class HitChanceAimer implements GunController {
    // Keep this narrow: only to absorb tiny planner/execution drift.
    private static final double REACHABILITY_ANGLE_EPSILON = 2.5e-3;
    private static final int OPTIMAL_SHOT_CACHE_CAPACITY = 24;
    private static final int FORCED_SHOT_CACHE_CAPACITY = 64;
    private static final int DISTRIBUTION_CACHE_CAPACITY = 24;
    private static final double CACHE_POWER_EPSILON = 1e-9;
    private static final double CACHE_ANGLE_EPSILON = 1e-9;
    private static final int UNCONSTRAINED_TICKS_SENTINEL = Integer.MIN_VALUE;

    private final Info info;
    private final TargetingComputer targetingComputer = new TargetingComputer();
    private boolean shotCacheGeometryValid;
    private double shotCacheShooterX;
    private double shotCacheShooterY;
    private double shotCacheTargetX;
    private double shotCacheTargetY;
    private double shotCacheGunHeadingAtDecision;
    private int shotCacheTicksUntilFire;
    private int optimalShotCacheSize;
    private int forcedShotCacheSize;
    private final double[] optimalShotCachePowers = new double[OPTIMAL_SHOT_CACHE_CAPACITY];
    private final ShotSolution[] optimalShotCacheSolutions = new ShotSolution[OPTIMAL_SHOT_CACHE_CAPACITY];
    private final double[] forcedShotCachePowers = new double[FORCED_SHOT_CACHE_CAPACITY];
    private final double[] forcedShotCacheAngles = new double[FORCED_SHOT_CACHE_CAPACITY];
    private final ShotSolution[] forcedShotCacheSolutions = new ShotSolution[FORCED_SHOT_CACHE_CAPACITY];
    private long distributionCacheTime = Long.MIN_VALUE;
    private int distributionCacheSize;
    private final WaveContextFeatures.WaveContext[] distributionCacheContexts =
            new WaveContextFeatures.WaveContext[DISTRIBUTION_CACHE_CAPACITY];
    private final GuessFactorDistributionHandle[] distributionCacheHandles =
            new GuessFactorDistributionHandle[DISTRIBUTION_CACHE_CAPACITY];

    public HitChanceAimer(Info info) {
        this.info = info;
    }

    private static final class TargetingFrame {
        final double bearing;
        final double distance;
        final GuessFactorDistribution distribution;
        final double preciseMinGf;
        final double preciseMaxGf;

        TargetingFrame(double bearing,
                       double distance,
                       GuessFactorDistribution distribution,
                       double preciseMinGf,
                       double preciseMaxGf) {
            this.bearing = bearing;
            this.distance = distance;
            this.distribution = distribution;
            this.preciseMinGf = preciseMinGf;
            this.preciseMaxGf = preciseMaxGf;
        }
    }

    private GuessFactorDistribution getDistributionForEnemyContext(
            WaveContextFeatures.WaveContext context,
            double shooterX,
            double shooterY,
            EnemyInfo.PredictedPosition enemyAtFireTime,
            double bulletSpeed) {
        GuessFactorDistributionHandle handle = distributionHandleForEnemyContext(context);
        if (handle != null) {
            return handle.exact();
        }
        if (enemyAtFireTime == null) {
            return new DefaultDistribution(BotConfig.Gun.DEFAULT_OPPONENT_MOVEMENT_BANDWIDTH);
        }
        return createDefaultGunDistribution(shooterX, shooterY, enemyAtFireTime, bulletSpeed);
    }

    private GuessFactorDistributionHandle distributionHandleForEnemyContext(
            WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Gun distribution context must be non-null");
        }
        ensureDistributionCacheTime(info.getRobot().getTime());
        int cacheIndex = findDistributionCacheIndex(context);
        if (cacheIndex >= 0) {
            return distributionCacheHandles[cacheIndex];
        }

        GuessFactorDistribution exactDistribution = info.getObservationProfile().createGunDistribution(context);
        if (exactDistribution == null) {
            return null;
        }
        GuessFactorDistributionHandle handle = new GuessFactorDistributionHandle(exactDistribution);
        if (distributionCacheSize < DISTRIBUTION_CACHE_CAPACITY) {
            distributionCacheContexts[distributionCacheSize] = context;
            distributionCacheHandles[distributionCacheSize] = handle;
            distributionCacheSize++;
        }
        return handle;
    }

    /**
     * Finds the optimal firing angle for a given bullet power.
     *
     * @param firePower the bullet power to use
     * @return the optimal absolute firing angle in radians
     */
    public double getOptimalFiringAngle(double firePower) {
        return selectCurrentShot(firePower).firingAngle;
    }

    public double getOptimalUnconstrainedFiringAngle(double firePower) {
        return selectCurrentUnconstrainedShot(firePower).firingAngle;
    }

    /**
     * Computes the best current-tick shot from our live state.
     */
    public ShotSolution selectCurrentShot(double firePower) {
        if (firePower < 0.1) {
            return new ShotSolution(0.0, Double.NaN);
        }
        Saguaro robot = info.getRobot();
        EnemyInfo.PredictedPosition enemyAtFireTime = predictEnemyAtFireTime(robot.getTime());
        if (enemyAtFireTime == null) {
            return new ShotSolution(0.0, Double.NaN);
        }
        return selectOptimalShotFromPosition(
                robot.getX(),
                robot.getY(),
                enemyAtFireTime.x,
                enemyAtFireTime.y,
                firePower,
                robot.getGunHeadingRadians(),
                0);
    }

    public ShotSolution selectCurrentUnconstrainedShot(double firePower) {
        if (firePower < 0.1) {
            return new ShotSolution(0.0, Double.NaN);
        }
        Saguaro robot = info.getRobot();
        EnemyInfo.PredictedPosition enemyAtFireTime = predictEnemyAtFireTime(robot.getTime());
        if (enemyAtFireTime == null) {
            return new ShotSolution(0.0, Double.NaN);
        }
        return selectOptimalUnconstrainedShotFromPosition(
                robot.getX(),
                robot.getY(),
                enemyAtFireTime.x,
                enemyAtFireTime.y,
                firePower,
                0);
    }

    /**
     * Computes the best reachable firing angle and expected damage if we were
     * firing from an arbitrary position.
     */
    public ShotSolution selectOptimalShotFromPosition(double shooterX, double shooterY,
                                                      double targetX, double targetY,
                                                      double firePower,
                                                      double gunHeadingAtDecision,
                                                      int ticksUntilFire) {
        if (firePower < 0.1) {
            return new ShotSolution(0.0, Double.NaN);
        }
        double bulletDamage = Rules.getBulletDamage(firePower);
        if (isEnemyDisabledForTargeting()) {
            return disabledEnemyConstrainedHeadOnShot(
                    shooterX,
                    shooterY,
                    targetX,
                    targetY,
                    bulletDamage,
                    gunHeadingAtDecision,
                    ticksUntilFire);
        }
        ensureShotCacheGeometry(
                shooterX, shooterY, targetX, targetY, gunHeadingAtDecision, ticksUntilFire);
        ShotSolution cached = lookupOptimalShotCache(firePower);
        if (cached != null) {
            return cached;
        }

        setupTargetingComputerFromPosition(
                shooterX,
                shooterY,
                targetX,
                targetY,
                Wave.bulletSpeed(firePower),
                bulletDamage,
                ticksUntilFire);
        ShotSolution solution = constrainedOptimalShot(gunHeadingAtDecision, ticksUntilFire);
        storeOptimalShotCache(firePower, solution);
        return solution;
    }

    public ShotSolution selectOptimalUnconstrainedShotFromPosition(double shooterX,
                                                                   double shooterY,
                                                                   double targetX,
                                                                   double targetY,
                                                                   double firePower,
                                                                   int ticksUntilFire) {
        if (firePower < 0.1) {
            return new ShotSolution(0.0, Double.NaN);
        }
        double bulletDamage = Rules.getBulletDamage(firePower);
        if (isEnemyDisabledForTargeting()) {
            return disabledEnemyUnconstrainedHeadOnShot(
                    shooterX,
                    shooterY,
                    targetX,
                    targetY,
                    bulletDamage);
        }
        // UNCONSTRAINED_TICKS_SENTINEL offsets ticksUntilFire into a range that can never
        // collide with constrained cache keys, keeping the two shot types in the same cache.
        ensureShotCacheGeometry(
                shooterX, shooterY, targetX, targetY, Double.NaN, UNCONSTRAINED_TICKS_SENTINEL + ticksUntilFire);
        ShotSolution cached = lookupOptimalShotCache(firePower);
        if (cached != null) {
            return cached;
        }

        setupTargetingComputerFromPosition(
                shooterX,
                shooterY,
                targetX,
                targetY,
                Wave.bulletSpeed(firePower),
                bulletDamage,
                ticksUntilFire);
        ShotSolution solution = unconstrainedOptimalShot();
        storeOptimalShotCache(firePower, solution);
        return solution;
    }

    /**
     * Evaluates expected damage at an explicit desired firing angle if reachable
     * within gun turn-rate limits before fire time.
     */
    public ShotSolution evaluateShotAtAngleFromPosition(double shooterX, double shooterY,
                                                        double targetX, double targetY,
                                                        double firePower,
                                                        double desiredFiringAngle,
                                                        double gunHeadingAtDecision,
                                                        int ticksUntilFire) {
        if (firePower < 0.1) {
            return new ShotSolution(0.0, Double.NaN);
        }

        double firingAngle = clampedReachableAngle(desiredFiringAngle, gunHeadingAtDecision, ticksUntilFire);
        if (Double.isNaN(firingAngle)) {
            return new ShotSolution(0.0, Double.NaN);
        }
        ensureShotCacheGeometry(
                shooterX, shooterY, targetX, targetY, gunHeadingAtDecision, ticksUntilFire);
        ShotSolution cached = lookupForcedShotCache(firePower, firingAngle);
        if (cached != null) {
            return cached;
        }

        double bulletSpeed = Wave.bulletSpeed(firePower);
        double bulletDamage = Rules.getBulletDamage(firePower);
        setupTargetingComputerFromPosition(
                shooterX, shooterY, targetX, targetY, bulletSpeed, bulletDamage, ticksUntilFire);
        if (targetingComputer.getTargetCount() == 0) {
            ShotSolution miss = new ShotSolution(0.0, Double.NaN);
            storeForcedShotCache(firePower, firingAngle, miss);
            return miss;
        }

        ShotSolution solution = new ShotSolution(targetingComputer.expectedDamage(firingAngle), firingAngle);
        storeForcedShotCache(firePower, firingAngle, solution);
        return solution;
    }

    private static boolean isWithinTurnBudget(double angle, double center, double turnBudget) {
        return Math.abs(MathUtils.normalizeAngle(angle - center)) <= turnBudget + REACHABILITY_ANGLE_EPSILON;
    }

    private static double turnBudgetForTicks(int ticksUntilFire) {
        int nonNegativeTicksUntilFire = Math.max(0, ticksUntilFire);
        return nonNegativeTicksUntilFire * Rules.GUN_TURN_RATE_RADIANS;
    }

    private static double clampedReachableAngle(double desiredFiringAngle,
                                                double gunHeadingAtDecision,
                                                int ticksUntilFire) {
        double center = MathUtils.normalizeAngle(gunHeadingAtDecision);
        double turnBudget = turnBudgetForTicks(ticksUntilFire);
        if (turnBudget >= Math.PI) {
            return MathUtils.normalizeAngle(desiredFiringAngle);
        }

        double delta = MathUtils.normalizeAngle(desiredFiringAngle - center);
        double absDelta = Math.abs(delta);
        if (absDelta <= turnBudget) {
            return MathUtils.normalizeAngle(desiredFiringAngle);
        }
        if (absDelta <= turnBudget + REACHABILITY_ANGLE_EPSILON) {
            return MathUtils.normalizeAngle(center + Math.copySign(turnBudget, delta));
        }
        return Double.NaN;
    }

    private static void appendUniqueAngle(List<Double> angles, double candidate) {
        for (double angle : angles) {
            if (Math.abs(MathUtils.normalizeAngle(angle - candidate)) <= 1e-9) {
                return;
            }
        }
        angles.add(candidate);
    }

    private ShotSolution unconstrainedOptimalShot() {
        double optimalAngle = targetingComputer.findOptimalAngle();
        if (Double.isNaN(optimalAngle)) {
            return new ShotSolution(0.0, Double.NaN);
        }
        return new ShotSolution(targetingComputer.expectedDamage(optimalAngle), optimalAngle);
    }

    private ShotSolution constrainedOptimalShot(double gunHeadingAtDecision, int ticksUntilFire) {
        // Robocode fires using the previous gun heading. So only ticks strictly
        // before the firing tick contribute to reachable turn budget.
        int nonNegativeTicksUntilFire = Math.max(0, ticksUntilFire);
        double turnBudget = nonNegativeTicksUntilFire * Rules.GUN_TURN_RATE_RADIANS;
        if (turnBudget >= Math.PI) {
            return unconstrainedOptimalShot();
        }

        double center = MathUtils.normalizeAngle(gunHeadingAtDecision);
        double lower = MathUtils.normalizeAngle(center - turnBudget);
        double upper = MathUtils.normalizeAngle(center + turnBudget);

        List<Double> candidateAngles = new ArrayList<>();
        appendUniqueAngle(candidateAngles, center);
        appendUniqueAngle(candidateAngles, lower);
        appendUniqueAngle(candidateAngles, upper);
        for (double peakAngle : targetingComputer.findAllPeakAngles()) {
            if (isWithinTurnBudget(peakAngle, center, turnBudget)) {
                appendUniqueAngle(candidateAngles, peakAngle);
            }
        }

        double bestExpectedDamage = 0.0;
        double bestAngle = Double.NaN;
        for (double candidateAngle : candidateAngles) {
            double expectedDamage = targetingComputer.expectedDamage(candidateAngle);
            if (expectedDamage > bestExpectedDamage) {
                bestExpectedDamage = expectedDamage;
                bestAngle = candidateAngle;
            }
        }
        return new ShotSolution(bestExpectedDamage, bestAngle);
    }

    private void ensureShotCacheGeometry(double shooterX, double shooterY,
                                         double targetX, double targetY,
                                         double gunHeadingAtDecision,
                                         int ticksUntilFire) {
        if (shotCacheGeometryValid
                && sameDoubleBits(shotCacheShooterX, shooterX)
                && sameDoubleBits(shotCacheShooterY, shooterY)
                && sameDoubleBits(shotCacheTargetX, targetX)
                && sameDoubleBits(shotCacheTargetY, targetY)
                && sameDoubleBits(shotCacheGunHeadingAtDecision, gunHeadingAtDecision)
                && shotCacheTicksUntilFire == ticksUntilFire) {
            return;
        }

        shotCacheGeometryValid = true;
        shotCacheShooterX = shooterX;
        shotCacheShooterY = shooterY;
        shotCacheTargetX = targetX;
        shotCacheTargetY = targetY;
        shotCacheGunHeadingAtDecision = gunHeadingAtDecision;
        shotCacheTicksUntilFire = ticksUntilFire;
        optimalShotCacheSize = 0;
        forcedShotCacheSize = 0;
    }

    private ShotSolution lookupOptimalShotCache(double firePower) {
        for (int i = 0; i < optimalShotCacheSize; i++) {
            if (Math.abs(optimalShotCachePowers[i] - firePower) <= CACHE_POWER_EPSILON) {
                return optimalShotCacheSolutions[i];
            }
        }
        return null;
    }

    private void storeOptimalShotCache(double firePower, ShotSolution solution) {
        if (optimalShotCacheSize >= OPTIMAL_SHOT_CACHE_CAPACITY) {
            return;
        }
        optimalShotCachePowers[optimalShotCacheSize] = firePower;
        optimalShotCacheSolutions[optimalShotCacheSize] = solution;
        optimalShotCacheSize++;
    }

    private ShotSolution lookupForcedShotCache(double firePower, double firingAngle) {
        for (int i = 0; i < forcedShotCacheSize; i++) {
            if (Math.abs(forcedShotCachePowers[i] - firePower) <= CACHE_POWER_EPSILON
                    && Math.abs(MathUtils.normalizeAngle(forcedShotCacheAngles[i] - firingAngle))
                    <= CACHE_ANGLE_EPSILON) {
                return forcedShotCacheSolutions[i];
            }
        }
        return null;
    }

    private void storeForcedShotCache(double firePower, double firingAngle, ShotSolution solution) {
        if (forcedShotCacheSize >= FORCED_SHOT_CACHE_CAPACITY) {
            return;
        }
        forcedShotCachePowers[forcedShotCacheSize] = firePower;
        forcedShotCacheAngles[forcedShotCacheSize] = firingAngle;
        forcedShotCacheSolutions[forcedShotCacheSize] = solution;
        forcedShotCacheSize++;
    }

    private static boolean sameDoubleBits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    private boolean isEnemyDisabledForTargeting() {
        EnemyInfo enemy = info.getEnemy();
        return enemy != null && enemy.alive && enemy.seenThisRound && enemy.energy <= 0.0;
    }

    private static ShotSolution disabledEnemyConstrainedHeadOnShot(double shooterX,
                                                                   double shooterY,
                                                                   double targetX,
                                                                   double targetY,
                                                                   double bulletDamage,
                                                                   double gunHeadingAtDecision,
                                                                   int ticksUntilFire) {
        double directAngle = Math.atan2(targetX - shooterX, targetY - shooterY);
        double firingAngle = clampedReachableAngle(directAngle, gunHeadingAtDecision, ticksUntilFire);
        if (!Double.isFinite(firingAngle)) {
            return new ShotSolution(0.0, Double.NaN);
        }
        return new ShotSolution(bulletDamage, firingAngle);
    }

    private static ShotSolution disabledEnemyUnconstrainedHeadOnShot(double shooterX,
                                                                     double shooterY,
                                                                     double targetX,
                                                                     double targetY,
                                                                     double bulletDamage) {
        double firingAngle = MathUtils.normalizeAngle(Math.atan2(targetX - shooterX, targetY - shooterY));
        return new ShotSolution(bulletDamage, firingAngle);
    }

    private void ensureDistributionCacheTime(long currentTime) {
        if (distributionCacheTime == currentTime) {
            return;
        }
        distributionCacheTime = currentTime;
        distributionCacheSize = 0;
    }

    private int findDistributionCacheIndex(WaveContextFeatures.WaveContext context) {
        for (int i = 0; i < distributionCacheSize; i++) {
            if (context.equals(distributionCacheContexts[i])) {
                return i;
            }
        }
        return -1;
    }

    private EnemyInfo.PredictedPosition predictEnemyAtFireTime(long fireTime) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }
        return enemy.predictPositionAtTime(
                fireTime, info.getBattlefieldWidth(), info.getBattlefieldHeight());
    }

    private GuessFactorDistribution createDefaultGunDistribution(double shooterX,
                                                                 double shooterY,
                                                                 EnemyInfo.PredictedPosition enemyAtFireTime,
                                                                 double bulletSpeed) {
        EnemyInfo enemy = info.getEnemy();
        double headingDelta = enemy != null ? enemy.getPredictedHeadingDelta() : 0.0;
        double velocityDelta = enemy != null ? enemy.getPredictedVelocityDelta() : 0.0;
        PhysicsUtil.PositionState targetStart = new PhysicsUtil.PositionState(
                enemyAtFireTime.x,
                enemyAtFireTime.y,
                enemyAtFireTime.heading,
                enemyAtFireTime.velocity);
        PhysicsUtil.Trajectory targetTrajectory = ConstantDeltaPredictor.simulateUntilWavePassed(
                shooterX,
                shooterY,
                bulletSpeed,
                targetStart,
                headingDelta,
                velocityDelta,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        OpponentDriveSimulator.AimSolution aimSolution = OpponentDriveSimulator.solveInterceptFromTrajectory(
                shooterX,
                shooterY,
                bulletSpeed,
                targetTrajectory);
        double bearing = Math.atan2(enemyAtFireTime.x - shooterX, enemyAtFireTime.y - shooterY);
        double meanGf = MathUtils.angleToGf(
                bearing,
                aimSolution.firingAngle,
                MathUtils.maxEscapeAngle(bulletSpeed));
        return new DefaultDistribution(
                meanGf,
                meanGf,
                BotConfig.Gun.DEFAULT_OPPONENT_MOVEMENT_BANDWIDTH);
    }

    private TargetingFrame buildTargetingFrame(double shooterX,
                                               double shooterY,
                                               double targetX,
                                               double targetY,
                                               double bulletSpeed,
                                               long fireTime,
                                               EnemyInfo.PredictedPosition enemyAtFireTime) {
        if (enemyAtFireTime == null) {
            return null;
        }
        double bearing = Math.atan2(targetX - shooterX, targetY - shooterY);
        double distance = Math.hypot(targetX - shooterX, targetY - shooterY);
        WaveContextFeatures.WaveContext context = WaveContextFeatures.createWaveContext(
                shooterX,
                shooterY,
                bulletSpeed,
                fireTime,
                enemyAtFireTime.x,
                enemyAtFireTime.y,
                enemyAtFireTime.heading,
                enemyAtFireTime.velocity,
                info.getEnemy() != null ? info.getEnemy().getPredictedHeadingDelta() : 0.0,
                info.getEnemy() != null ? info.getEnemy().getPredictedVelocityDelta() : 0.0,
                enemyAtFireTime.accelerationSign,
                enemyAtFireTime.ticksSinceVelocityReversal,
                enemyAtFireTime.ticksSinceDecel,
                enemyAtFireTime.lastNonZeroLateralDirectionSign,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight(),
                info.getMyWaves(),
                null);
        GuessFactorDistribution distribution = getDistributionForEnemyContext(
                context,
                shooterX,
                shooterY,
                enemyAtFireTime,
                bulletSpeed);
        double[] preciseGfRange = MathUtils.inFieldMaxEscapeGfRange(
                shooterX,
                shooterY,
                targetX,
                targetY,
                bulletSpeed,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        return new TargetingFrame(
                bearing,
                distance,
                distribution,
                preciseGfRange[0],
                preciseGfRange[1]);
    }

    private void addTargetingFrame(TargetingFrame frame, double bulletSpeed, double bulletDamage) {
        if (frame == null) {
            return;
        }
        targetingComputer.addTarget(new TargetingComputer.Target(
                frame.bearing,
                frame.distance,
                bulletSpeed,
                bulletDamage,
                frame.distribution,
                frame.preciseMinGf,
                frame.preciseMaxGf));
    }

    /**
     * Sets up the targeting computer using bearings/distances computed from an arbitrary position.
     *
     * <p>targetX/targetY must be the result of enemy.predictPositionAtTime(fireTime). The wave
     * context features are built from the same internal prediction, so any divergence between
     * the caller-provided coordinates and predictPositionAtTime would cause the distribution to
     * be selected based on different state than the geometry was computed from.
     */
    private void setupTargetingComputerFromPosition(double shooterX, double shooterY,
                                                     double targetX, double targetY,
                                                     double bulletSpeed, double bulletDamage,
                                                     int ticksUntilFire) {
        targetingComputer.clearTargets();
        long fireTime = info.getRobot().getTime() + Math.max(0, ticksUntilFire);
        EnemyInfo.PredictedPosition enemyAtFireTime = predictEnemyAtFireTime(fireTime);
        TargetingFrame frame = buildTargetingFrame(
                shooterX,
                shooterY,
                targetX,
                targetY,
                bulletSpeed,
                fireTime,
                enemyAtFireTime);
        addTargetingFrame(frame, bulletSpeed, bulletDamage);
    }
}


