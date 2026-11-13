package oog.mega.saguaro.info.learning.rule;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.MirroredGuessFactorDistribution;

/**
 * The opponent model layer: one rule model of how the opponent moves given what it has
 * seen of our fire, one of how it aims given what it has seen of our movement, each
 * trained on every resolved wave and exposed to Saguaro as calibrated hit-chance
 * distributions.
 *
 * All guess factors here are canonical: Saguaro's frame mirrored so the target's
 * momentum points positive. The target's reachable range on a wave is then
 * [-wallReverse, +wallAhead] from its context.
 */
public final class RuleProfile {
    public static final RuleProfile INSTANCE = new RuleProfile();

    private static final int BINS = RuleModel.BINS;
    private static final double SPACING = 2.0 / (BINS - 1);
    private static final double HALF_BODY = 18.0;
    private static final int MIN_OBSERVATIONS = 2;

    /**
     * The situation a wave was fired in, as the model segments it: the mover's lateral and
     * advancing speed, range, room ahead and behind, how long since it reversed, its
     * acceleration, the bullet's flight time, how long since its speed changed, and how
     * far it has actually got lately -- the segments, scales and starting weights the
     * model was developed with -- then how fast its lateral speed is changing, turning
     * included, and what a mover that reacts to fire sees: how long since the shooter's
     * last wave, and how soon the nearest one in the air reaches it. Then where it sits on
     * the nearest of those waves, which a surfer carries into the next one and which top
     * guns segment on; how far it is from the shooter's mirror point, which movements
     * defined by the shooter's position follow; and how far it got over a short window.
     * Then its heading apart from its speed, which a slow or stopped mover still shows --
     * the axis of its travel against the line from the shooter, and how fast that axis is
     * swinging -- its lateral velocity smoothed over the last ticks, and how far it can go
     * along its course, ahead and behind, before it meets a wall.
     * The timers are measured in flight times: what matters to where a bullet catches a
     * mover is whether it will reverse before the bullet arrives, and twelve ticks since
     * a reversal means opposite things at fifteen ticks of flight and at forty-five. Each
     * weight is learned per opponent, so a feature that does not predict an opponent
     * fades. Saguaro's own normalised context was built
     * for a wave log that learns a bias and exponent per feature, which this model does
     * not, and its binary features dominate an untransformed distance.
     */
    private static final int FEATURES = 21;

    static double[] features(WaveContextFeatures.WaveContext context) {
        double distance = oog.mega.saguaro.math.MathUtils.hypot(context.targetX - context.sourceX, context.targetY - context.sourceY);
        double[] f = new double[FEATURES];
        f[0] = Math.min(1, Math.abs(context.lateralVelocity) / 8.0);
        f[1] = Math.max(-1, Math.min(1, context.advancingVelocity / 8.0));
        f[2] = Math.min(1, distance / 900.0);
        f[3] = Math.max(0, Math.min(1, context.wallAhead));
        f[4] = Math.max(0, Math.min(1, context.wallReverse));
        double flight = Math.max(1, context.flightTicks);
        f[5] = Math.min(1, context.ticksSinceVelocityReversal / flight);
        f[6] = Math.max(-1, Math.min(1, context.targetVelocityDelta / 2.0));
        f[7] = Math.min(1, context.flightTicks / 50.0);
        f[8] = Math.min(1, context.ticksSinceDecel / flight);
        f[9] = Math.min(1, context.distanceLast20 / (8.0 * 20));
        double lateralAcceleration = context.targetVelocityDelta * Math.sin(context.relativeHeading)
                + context.targetVelocity * Math.cos(context.relativeHeading) * context.targetHeadingDelta;
        f[10] = Math.max(-1, Math.min(1, context.lateralDirectionSign * lateralAcceleration / 2.0));
        f[11] = context.ticksSincePreviousWave < 0 ? 1 : Math.min(1, context.ticksSincePreviousWave / 40.0);
        f[12] = context.ticksToNearestArrival < 0 ? 1 : Math.min(1, context.ticksToNearestArrival / 50.0);
        f[13] = Math.max(-1, Math.min(1, context.lateralDirectionSign * context.currentGF));
        f[14] = mirrorOffset(context);
        f[15] = Math.min(1, context.distanceLast10 / (8.0 * 10));
        // The direction of travel, which reverses with the velocity.
        double travel = context.targetVelocity < 0 ? context.targetHeading + Math.PI : context.targetHeading;
        double bearing = Math.atan2(context.targetX - context.sourceX, context.targetY - context.sourceY);
        double axis = travel - bearing;
        f[16] = Math.cos(axis);
        f[17] = Math.max(-1, Math.min(1, -Math.sin(axis) * context.targetHeadingDelta / MAX_TURN));
        f[18] = Math.max(-1, Math.min(1, context.lateralDirectionSign * context.momentumLateralVelocity / 8.0));
        double reach = 8.0 * flight;
        f[19] = Math.min(1, wallDistance(context, travel) / reach);
        f[20] = Math.min(1, wallDistance(context, travel + Math.PI) / reach);
        return f;
    }

    private static final double MAX_TURN = Math.toRadians(10);

    /** How far the mover can go along a heading before its body meets a wall. */
    private static double wallDistance(WaveContextFeatures.WaveContext context, double heading) {
        double dx = Math.sin(heading), dy = Math.cos(heading);
        double x = context.targetX, y = context.targetY;
        double distance = Double.POSITIVE_INFINITY;
        if (dx > 1e-9) distance = Math.min(distance, (context.battlefieldWidth - HALF_BODY - x) / dx);
        if (dx < -1e-9) distance = Math.min(distance, (HALF_BODY - x) / dx);
        if (dy > 1e-9) distance = Math.min(distance, (context.battlefieldHeight - HALF_BODY - y) / dy);
        if (dy < -1e-9) distance = Math.min(distance, (HALF_BODY - y) / dy);
        return Math.max(0, distance);
    }

    /**
     * The bearing from the shooter to its mirror point through the field's centre, less
     * the bearing to the mover, in escape angles and in the mover's direction of travel.
     */
    private static double mirrorOffset(WaveContextFeatures.WaveContext context) {
        double mirrorX = context.battlefieldWidth - context.sourceX;
        double mirrorY = context.battlefieldHeight - context.sourceY;
        if (oog.mega.saguaro.math.MathUtils.hypot(mirrorX - context.sourceX, mirrorY - context.sourceY) < 1) return 0;
        double toMover = Math.atan2(context.targetX - context.sourceX, context.targetY - context.sourceY);
        double toMirror = Math.atan2(mirrorX - context.sourceX, mirrorY - context.sourceY);
        double offset = MathUtils.normalizeAngle(toMirror - toMover)
                / MathUtils.maxEscapeAngle(context.bulletSpeed);
        return Math.max(-1, Math.min(1, context.lateralDirectionSign * offset));
    }

    private final int featureCount = FEATURES;
    /**
     * Targeting starts as a surfer without a flattener: danger from where it was hit,
     * almost none from where it passed, a kernel about one cell wide.
     */
    private final RuleModel movementOfOpponent = new RuleModel(
            new double[]{3.0, 0.5, 1.5, 0.8, 0.8, 0.3, 0.4, 0.6, 0.4, 0.4, 0.4, 0.4, 0.4, 0.6, 0.4, 0.4,
                    0.5, 0.4, 0.5, 0.5, 0.3},
            SPACING, 1.0, 0.01, 0.05);
    /** Movement starts as the usual gun: logs every wave, a broad kernel. */
    private final RuleModel gunOfOpponent = new RuleModel(
            new double[]{3.0, 1.0, 2.0, 1.0, 1.0, 0.5, 0.5, 1.5, 1.0, 1.0, 0.5, 0.5, 0.5, 0.8, 0.5, 0.5,
                    0.5, 0.5, 0.5, 0.5, 0.3},
            0.10, 1.0, 1.0, 1.0);
    private final RuleLog crossings = new RuleLog(1600, featureCount);
    private final RuleLog arrivals = new RuleLog(900, featureCount);
    private final RuleAim aim = new RuleAim(movementOfOpponent, crossings);
    /** How each side chooses its bullet power, from the shots it has fired. */
    private final PowerModel enemyPower = new PowerModel();
    private final PowerModel ourPower = new PowerModel();
    private final HitCalibration shotMap = new HitCalibration();
    private final HitCalibration dodgeMap = new HitCalibration();
    /**
     * From the planner's predicted chance for the shot it chose to the chance that shot
     * realises: what choosing does to a prediction, which no calibration at fire time can
     * see, because it is the act of choosing that biases it.
     */
    private final HitCalibration chosenShotMap = new HitCalibration();
    private long plannedShotTime = Long.MIN_VALUE;
    private double plannedShotRaw = Double.NaN;
    private final java.util.Map<Wave, Double> chosenShots = new java.util.IdentityHashMap<>();

    private final double[] points = new double[BINS];
    private final double[] masses = new double[BINS];
    private final double[] covered = new double[BINS];

    /** Shots scored on the map: their uniform-over-reach chance, summed, and their hits. */
    private double uniformSum;
    private final int[] reachShots = new int[4], reachHits = new int[4];
    private final double[] reachCalibrated = new double[4], reachRaw = new double[4];
    private int uniformShots, uniformHits;

    /** Cost accounting: requests and nanoseconds per factory, and training steps. */
    private long gunCalls, gunNanos, movementCalls, movementNanos, trainCalls, trainNanos;

    private RuleProfile() { }

    public void startBattle() {
        crossings.clearBattle();
        arrivals.clearBattle();
        movementOfOpponent.reset();
        gunOfOpponent.reset();
        shotMap.clearPrior();
        dodgeMap.clearPrior();
        chosenShotMap.clearPrior();
        HorizonCalibration.INSTANCE.startBattle();
        chosenShots.clear();
        aim.startBattle();
        enemyPower.clear();
        ourPower.clear();
        firedPowerSum = 0;
        firedPowerWeight = 0;
        enemyPowerSum = 0;
        enemyPowerWeight = 0;
        powerErrorModel = 0;
        powerErrorLast = 0;
        powerErrors = 0;
        lastEnemyPower = Double.NaN;
        uniformSum = 0;
        uniformShots = 0;
        uniformHits = 0;
    }

    public void startRound() {
        crossings.newRound();
        arrivals.newRound();
        aim.startRound();
        sceneTime = Long.MIN_VALUE;
    }

    // ---- frames ------------------------------------------------------------------

    private static boolean mirrored(WaveContextFeatures.WaveContext context) {
        return WaveContextFeatures.normalizeDirectionSign(context.momentumDirectionSign) < 0;
    }

    private static double canonical(WaveContextFeatures.WaveContext context, double gf) {
        return mirrored(context) ? -gf : gf;
    }

    private static GuessFactorDistribution expose(WaveContextFeatures.WaveContext context,
                                                  RuleDistribution distribution) {
        return mirrored(context) ? new MirroredGuessFactorDistribution(distribution) : distribution;
    }

    /** The target's body width on this wave, in guess-factor units. */
    static double bodyWidth(WaveContextFeatures.WaveContext context) {
        double distance = oog.mega.saguaro.math.MathUtils.hypot(context.targetX - context.sourceX, context.targetY - context.sourceY);
        return 2 * Math.atan2(HALF_BODY, Math.max(1, distance)) / MathUtils.maxEscapeAngle(context.bulletSpeed);
    }

    // ---- targeting: where the opponent will be ----------------------------------

    private oog.mega.saguaro.info.Info info;

    /** The battle state the chain reads our waves in flight and the opponent from. */
    public void bind(oog.mega.saguaro.info.Info info) { this.info = info; }

    private long sceneTime = Long.MIN_VALUE;
    private RuleAim.Scene scene;

    /** The opponent's last scan and where we are, once per tick; null before a scan. */
    private RuleAim.Scene scene() {
        if (info == null) return null;
        long now = info.getRobot().getTime();
        if (sceneTime == now) return scene;
        sceneTime = now;
        scene = null;
        oog.mega.saguaro.info.state.EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound || enemy.lastScanTime < 0) return null;
        WaveReachability.Position anchor = new WaveReachability.Position(enemy.x, enemy.y, enemy.lastScanTime);
        scene = new RuleAim.Scene(now, anchor,
                new ChainWave.Mover(enemy.x, enemy.y, enemy.heading, enemy.velocity),
                info.getRobot().getX(), info.getRobot().getY(),
                info.getBattlefieldWidth(), info.getBattlefieldHeight());
        return scene;
    }

    /** A shot of this speed fired now from where we are, at the opponent as it is. */
    private WaveContextFeatures.WaveContext nowContext(double speed) {
        oog.mega.saguaro.info.state.EnemyInfo enemy = info.getEnemy();
        long now = info.getRobot().getTime();
        oog.mega.saguaro.info.state.EnemyInfo.PredictedPosition at = enemy.predictPositionAtTime(
                now, info.getBattlefieldWidth(), info.getBattlefieldHeight());
        WaveContextFeatures.WaveContext context = WaveContextFeatures.createWaveContext(
                info.getRobot().getX(), info.getRobot().getY(), speed, now,
                at.x, at.y, at.heading, at.velocity,
                enemy.getPredictedHeadingDelta(), enemy.getPredictedVelocityDelta(),
                at.accelerationSign, at.ticksSinceVelocityReversal, at.ticksSinceDecel,
                enemy.getDistanceLastTicks(10), enemy.getDistanceLastTicks(20),
                info.didLastMyWaveHitOpponent(),
                at.lastNonZeroLateralDirectionSign, at.momentumLateralVelocity, at.momentumDirectionSign,
                info.getBattlefieldWidth(), info.getBattlefieldHeight(),
                info.getMyWaves(), null, info.getOurShotsFiredThisBattle());
        return context;
    }

    public GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        if (context == null || crossings.size() < MIN_OBSERVATIONS) return null;
        long start = System.nanoTime();
        try { return gunDistribution(context); }
        finally { gunCalls++; gunNanos += System.nanoTime() - start; }
    }

    private GuessFactorDistribution gunDistribution(WaveContextFeatures.WaveContext context) {
        RuleAim.Scene scene = scene();
        if (scene == null) return null;
        ChainWave shot = new ChainWave(context, context.battleTime, Double.NaN);
        RuleAim.Prediction prediction = aim.predict(scene, shot, true);
        if (prediction == null) return null;
        double width = bodyWidth(context);
        return expose(context, new RuleDistribution(prediction.masses, width, shotMap,
                RuleDistribution.uniformChance(width, prediction.reachLower, prediction.reachUpper)));
    }

    /** Recent fired powers, decayed like the hit rate. */
    private double firedPowerSum, firedPowerWeight;

    /**
     * How much more of the opponent's physical reach a body covers at a power than at the
     * power we have been firing: the factor our hit rate changes by between them. Read for
     * both powers at the same instant, so the moment's particulars cancel. NaN before we
     * have fired or while the reach is unknown.
     */
    public double reachRatio(double power) {
        if (info == null || !(power >= 0.1) || !(firedPowerWeight > 0)) return Double.NaN;
        double reference = uniformAt(firedPowerSum / firedPowerWeight);
        double at = uniformAt(power);
        if (!(reference > 0) || !(at > 0)) return Double.NaN;
        return at / reference;
    }

    /** The uniform-over-reach hit chance of a shot of this power fired now from where we are. */
    private double uniformAt(double power) {
        RuleAim.Scene scene = scene();
        if (scene == null) return Double.NaN;
        double speed = robocode.Rules.getBulletSpeed(power);
        WaveContextFeatures.WaveContext context = nowContext(speed);
        RuleAim.Prediction reach = aim.predict(scene, new ChainWave(context, scene.now, Double.NaN), false);
        if (reach == null) return Double.NaN;
        return RuleDistribution.uniformChance(bodyWidth(context), reach.reachLower, reach.reachUpper);
    }

    /** The chance a planned shot realises, from the planner's prediction for it. */
    public double chosenShotChance(double raw) {
        if (!(raw > 0)) return raw;
        return chosenShotMap.calibrate(Math.min(0.999, raw));
    }

    /** The plan chosen this tick fires: its uncalibrated chance, to fit against the outcome. */
    public void notePlannedShot(long time, double raw) {
        plannedShotTime = time;
        plannedShotRaw = raw;
    }

    /** One of our shots was fired: keep what it was aimed from until its crossing is seen. */
    public void onMyWaveFired(Wave wave) {
        if (wave.fireTime >= plannedShotTime && wave.fireTime - plannedShotTime <= 1 && plannedShotRaw > 0) {
            chosenShots.put(wave, plannedShotRaw);
        }
        if (wave.fireTimeContext == null) return;
        if (info != null && info.getEnemy() != null) {
            WaveContextFeatures.WaveContext c = wave.fireTimeContext;
            ourPower.onShot(oog.mega.saguaro.math.MathUtils.hypot(c.targetX - c.sourceX, c.targetY - c.sourceY),
                    info.getRobot().getEnergy(), info.getEnemy().energy, enemyRecentPower(), wave.getFirepower());
            firedPowerSum = 0.98 * firedPowerSum + wave.getFirepower();
            firedPowerWeight = 0.98 * firedPowerWeight + 1;
        }
        long start = System.nanoTime();
        try {
            RuleAim.Scene scene = scene();
            if (scene == null) return;
            ChainWave view = new ChainWave(wave.fireTimeContext, wave.fireTime, wave.heading);
            aim.onFired(wave, scene, view, crossings.size() >= MIN_OBSERVATIONS);
        } finally { trainCalls++; trainNanos += System.nanoTime() - start; }
    }

    /**
     * One of our waves passed the opponent. Its crossing is a choice among the guess
     * factors it could reach, scored over its body interval; a hit or an unhit pass is
     * then an observation it may react to. Our shot's predicted hit chance is scored
     * against the outcome first, for the realised-outcome map.
     */
    public void onMyWaveResolved(Wave wave, boolean hit, double gfMin, double gfMax, long time) {
        long start = System.nanoTime();
        try { myWaveResolved(wave, hit, gfMin, gfMax, time); }
        finally { trainCalls++; trainNanos += System.nanoTime() - start; }
    }

    private void myWaveResolved(Wave wave, boolean hit, double gfMin, double gfMax, long time) {
        WaveContextFeatures.WaveContext context = wave.fireTimeContext;
        RuleAim.Record record = aim.take(wave);
        Double chosen = chosenShots.remove(wave);
        // A retained shield wave still shows where the opponent crossed, but its
        // intercepted bullet cannot tell us whether an attack would have hit.
        if (!wave.bulletCollided) {
            ourPower.onOutcome(hit);
            if (chosen != null) chosenShotMap.observe(chosen, hit);
        }
        if (context == null || !Double.isFinite(gfMin) || !Double.isFinite(gfMax)) return;
        double[] features = features(context);
        double a = canonical(context, gfMin), b = canonical(context, gfMax);
        double lower = Math.min(a, b), upper = Math.max(a, b);
        double centre = (lower + upper) / 2, half = Math.max(1e-3, (upper - lower) / 2);
        long asOf = crossings.clock(time);
        ChainWave view = new ChainWave(context, wave.fireTime, wave.heading);

        // The shot's raw predicted chance, from the distribution it was aimed from. The map is
        // part of the saved model, so it learns only where the targeting model may.
        if (!wave.bulletCollided && wave.allowTargetingModelUpdate && record != null && record.masses != null
                && Double.isFinite(wave.heading)) {
            double fired = view.gf(wave.heading);
            double width = bodyWidth(context);
            RuleDistribution prediction = new RuleDistribution(record.masses, width, null);
            double uniform = RuleDistribution.uniformChance(width, record.reachLower, record.reachUpper);
            shotMap.observe(prediction.rawIntegrate(fired - width / 2, fired + width / 2), hit, uniform);
            uniformSum += uniform;
            uniformShots++;
            if (hit) uniformHits++;
            // DIAGNOSTIC ONLY: calibrated chance and outcome by the uniform chance of the reach.
            double raw = prediction.rawIntegrate(fired - width / 2, fired + width / 2);
            int ub = uniform < 0.15 ? 0 : uniform < 0.3 ? 1 : uniform < 0.6 ? 2 : 3;
            reachShots[ub]++;
            reachCalibrated[ub] += shotMap.calibrate(raw, uniform);
            reachRaw[ub] += raw;
            if (hit) reachHits[ub]++;
        }

        // A choice among the crossings it could reach, as the shot's chain bounded them
        // when it was fired. Without that there is no choice set to score it against.
        if (record != null && wave.allowTargetingModelUpdate) {
            double reachLower = Math.max(-1, Math.min(record.reachLower, centre));
            double reachUpper = Math.min(1, Math.max(record.reachUpper, centre));
            int count = 0;
            double total = 0;
            for (int i = 0; i < BINS && count < BINS; i++) {
                double g = RuleModel.gridPoint(i);
                if (g < reachLower - SPACING / 2 || g > reachUpper + SPACING / 2) continue;
                double overlap = Math.min(g + SPACING / 2, centre + half) - Math.max(g - SPACING / 2, centre - half);
                points[count] = g;
                covered[count] = Math.max(0, Math.min(1, overlap / SPACING));
                total += covered[count];
                count++;
            }
            if (total > 0 && count >= 2) {
                movementOfOpponent.updateChoice(features, crossings.entries(), points, covered, count, asOf);
            }
        }
        crossings.add(features, centre, hit ? RuleLog.Kind.HIT : RuleLog.Kind.PASSED, time);
    }

    // ---- movement: where the opponent's bullet will be ---------------------------

    private void aimMasses(double[] features, long asOf) {
        for (int i = 0; i < BINS; i++) points[i] = RuleModel.gridPoint(i);
        gunOfOpponent.probabilities(features, arrivals.entries(), points, BINS, 1.0, masses, asOf);
    }

    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        if (context == null || arrivals.size() < MIN_OBSERVATIONS) return null;
        long start = System.nanoTime();
        try { return movementDistribution(context); }
        finally { movementCalls++; movementNanos += System.nanoTime() - start; }
    }

    private GuessFactorDistribution movementDistribution(WaveContextFeatures.WaveContext context) {
        aimMasses(features(context), arrivals.clock(context.battleTime));
        return expose(context, new RuleDistribution(masses, bodyWidth(context), dodgeMap));
    }

    /**
     * An enemy wave resolved against us: a revealed bullet (a hit, or a collision with
     * one of ours), or a pass. The gun model is scored on whether its bullet was within
     * our body interval, as of the moment it fired; the realised-outcome map on the raw
     * chance it gave the interval we actually occupied.
     */
    public void onEnemyWaveResolved(Wave wave, boolean revealed, boolean collided, double bulletGf,
                                    double gfMin, double gfMax, long time) {
        long start = System.nanoTime();
        try { enemyWaveResolved(wave, revealed, collided, bulletGf, gfMin, gfMax, time); }
        finally { trainCalls++; trainNanos += System.nanoTime() - start; }
    }

    private void enemyWaveResolved(Wave wave, boolean revealed, boolean collided, double bulletGf,
                                   double gfMin, double gfMax, long time) {
        if (!collided && wave.allowMovementObservationLogging) enemyPower.onOutcome(revealed);
        WaveContextFeatures.WaveContext context = wave.fireTimeContext;
        if (context == null || !wave.allowMovementObservationLogging) return;
        double[] features = features(context);
        double half, centre;
        if (Double.isFinite(gfMin) && Double.isFinite(gfMax)) {
            double a = canonical(context, gfMin), b = canonical(context, gfMax);
            centre = (a + b) / 2;
            half = Math.max(1e-3, Math.abs(b - a) / 2);
        } else {
            half = bodyWidth(context) / 2;
            centre = Double.isFinite(bulletGf) ? canonical(context, bulletGf) : Double.NaN;
        }
        long asOf = arrivals.clock(wave.fireTime);

        if (!collided && Double.isFinite(centre) && arrivals.size() >= MIN_OBSERVATIONS) {
            aimMasses(features, asOf);
            RuleDistribution prediction = new RuleDistribution(masses, 2 * half, null);
            double raw = prediction.rawIntegrate(centre - half, centre + half);
            // DIAGNOSTIC ONLY: the map on what it is fitted on, and the planner's view of the same interval.
            noteDodge(dodgeSelfMap, dodgeMap.calibrate(Math.min(0.999, Math.max(1e-4, raw))), revealed);
            noteDodge(dodgeSelfPlanner, new RuleDistribution(masses, bodyWidth(context), dodgeMap)
                    .integrate(centre - half, centre + half), revealed);
            dodgeMap.observe(raw, revealed);
        }

        if (revealed) {
            if (!Double.isFinite(bulletGf)) return;
            double gf = canonical(context, Math.max(-1, Math.min(1, bulletGf)));
            if (wave.allowMovementModelUpdate) {
                gunOfOpponent.update(features, arrivals.entries(), gf, half, false, asOf);
            }
            arrivals.add(features, gf, collided ? RuleLog.Kind.COLLIDED : RuleLog.Kind.HIT, time);
        } else {
            if (!Double.isFinite(centre)) return;
            if (wave.allowMovementModelUpdate) {
                gunOfOpponent.update(features, arrivals.entries(), centre, half, true, asOf);
            }
            arrivals.add(features, centre, RuleLog.Kind.PASSED, time);
        }
    }

    /** The opponent fired: the power, at this range, with its energy before the shot and ours. */
    public void onEnemyFired(double distance, double enemyEnergy, double ourEnergy, double power) {
        double predicted = enemyPower.predictFromLast(distance, enemyEnergy, ourEnergy, ourRecentPower());
        if (Double.isFinite(predicted) && Double.isFinite(lastEnemyPower)) {
            powerErrorModel += Math.abs(Math.min(predicted, enemyEnergy) - power);
            powerErrorLast += Math.abs(Math.min(lastEnemyPower, enemyEnergy) - power);
            powerErrors++;
        }
        lastEnemyPower = power;
        enemyPower.onShot(distance, enemyEnergy, ourEnergy, ourRecentPower(), power);
        enemyPowerSum = 0.98 * enemyPowerSum + power;
        enemyPowerWeight = 0.98 * enemyPowerWeight + 1;
    }

    /** The opponent's recent powers, decayed like the hit rates. */
    private double enemyPowerSum, enemyPowerWeight;

    /** The power we have lately been firing, about fifty shots' memory; NaN before our first. */
    public double ourRecentPower() {
        return firedPowerWeight > 0 ? firedPowerSum / firedPowerWeight : Double.NaN;
    }

    /** The power the opponent has lately been firing; NaN before its first shot. */
    public double enemyRecentPower() {
        return enemyPowerWeight > 0 ? enemyPowerSum / enemyPowerWeight : Double.NaN;
    }

    private double powerErrorModel, powerErrorLast, lastEnemyPower = Double.NaN;
    private int powerErrors;

    /**
     * The power the opponent would fire at this range with these energies, at its present
     * hit rate on us, by the rule its own shots show; NaN before it has fired.
     */
    public double predictEnemyPower(double distance, double enemyEnergy, double ourEnergy, double ourPowerNow) {
        powerCalls++;
        return enemyPower.predictFromLast(distance, enemyEnergy, ourEnergy,
                Double.isFinite(ourPowerNow) ? ourPowerNow : ourRecentPower());
    }

    private long powerCalls;

    /** The opponent's fitted response to our power, read at the present situation. */
    private double powerSlope() {
        if (info == null || info.getEnemy() == null || enemyPower.size() == 0) return Double.NaN;
        oog.mega.saguaro.info.state.EnemyInfo enemy = info.getEnemy();
        enemyPower.predict(Math.max(1, enemy.distance) + 0.5, enemy.energy + 0.25, info.getRobot().getEnergy() + 0.25,
                ourRecentPower() + 0.01);
        return enemyPower.lastSlope();
    }

    // ---- what carries to the next battle ------------------------------------------

    /**
     * Saved per opponent: what the models learned about how it works, so each battle goes
     * on from where the last one stopped instead of from the starting priors -- both
     * models' parameters (which situations matter, how its own hits and misses move it,
     * how sharply it chooses, how much it does at random), both calibration maps, and the
     * share of its crossings the surfing bank explains. The logs themselves are far too
     * large for the record and are rebuilt within a battle.
     *
     * Layout: one byte of battles learned from, then each model's parameters and the two
     * maps' corrections as fixed-point shorts in thousandths, then the bank's share in
     * 250ths, then each model's prediction score over the battle just ended, in
     * thousandths. 90 bytes.
     */
    public static final int PERSISTENCE_VERSION = 109;
    private static final double FIXED = 1000.0;
    private boolean persistedLoaded;
    private int battlesLearned;
    /** The prediction scores the record carried, from the battle before this one. */
    private double[] previousLift;

    /** Battle start for the record: nothing loaded yet. */
    public void startBattlePersistence() {
        persistedLoaded = false;
        battlesLearned = 0;
        previousLift = null;
    }

    /** Prediction scores, this battle so far and in the record's last battle, for the end-of-round line. */
    public String recordDiagnostics() {
        return String.format(java.util.Locale.ROOT,
                "record battlesLearned=%d lift targeting=%.3f (n=%d, last battle %s) movement=%.3f (n=%d, last battle %s)",
                battlesLearned(), movementOfOpponent.meanLift(), movementOfOpponent.liftCount(),
                previousLift == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", previousLift[0]),
                gunOfOpponent.meanLift(), gunOfOpponent.liftCount(),
                previousLift == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", previousLift[1]));
    }

    public boolean hasPersistedData() {
        return persistedLoaded || movementOfOpponent.updateCount() > 0 || gunOfOpponent.updateCount() > 0;
    }

    public void loadPersisted(int sectionVersion, byte[] payload) {
        if (payload == null || sectionVersion != PERSISTENCE_VERSION) return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))) {
            int battles = in.readUnsignedByte();
            double[] targeting = readParameters(in, movementOfOpponent.exportParameters().length, WEIGHTS);
            double[] movement = readParameters(in, gunOfOpponent.exportParameters().length, WEIGHTS);
            double[] shot = readShorts(in, 2);
            double[] dodge = readShorts(in, 2);
            double[] lifts = readShorts(in, 2);
            double[] roundWin = readShorts(in, RoundWinCalibration.exportedLength());
            double[] horizon = readShorts(in, HorizonCalibration.EXPORTED_LENGTH);
            movementOfOpponent.importParameters(targeting);
            gunOfOpponent.importParameters(movement);
            movementOfOpponent.setExperience(battles);
            gunOfOpponent.setExperience(battles);
            previousLift = lifts;
            shotMap.importCorrection(shot);
            dodgeMap.importCorrection(dodge);
            RoundWinCalibration.INSTANCE.importParameters(roundWin);
            HorizonCalibration.INSTANCE.importCorrections(horizon);
            battlesLearned = battles;
            persistedLoaded = true;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Malformed rule model record", e);
        }
    }

    public byte[] persistedPayload(int maxPayloadBytes) {
        double[] targeting = movementOfOpponent.exportParameters();
        double[] movement = gunOfOpponent.exportParameters();
        int size = 1 + 2 * (targeting.length + movement.length + 4) - 2 * WEIGHTS + 4
                + 2 * RoundWinCalibration.exportedLength() + 2 * HorizonCalibration.EXPORTED_LENGTH;
        if (maxPayloadBytes < size) return null;
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream(size);
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(raw)) {
            out.writeByte(Math.min(255, battlesLearned + 1));
            writeParameters(out, targeting, WEIGHTS);
            writeParameters(out, movement, WEIGHTS);
            writeShorts(out, shotMap.exportCorrection());
            writeShorts(out, dodgeMap.exportCorrection());
            writeShorts(out, new double[]{movementOfOpponent.meanLift(), gunOfOpponent.meanLift()});
            writeShorts(out, RoundWinCalibration.INSTANCE.exportParameters());
            writeShorts(out, HorizonCalibration.INSTANCE.exportCorrections());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to build rule model record", e);
        }
        return raw.toByteArray();
    }

    /**
     * The dimension weights lead each model's parameters, as logs clamped to about +-3:
     * a byte each, in steps of 1/40, keeps the record within its budget as features are
     * added. The rest need the precision of a short.
     */
    private static final int WEIGHTS = FEATURES + 2;
    private static final double WEIGHT_STEP = 40.0;

    private static void writeParameters(java.io.DataOutputStream out, double[] values, int weights)
            throws java.io.IOException {
        for (int i = 0; i < weights; i++) {
            out.writeByte((int) Math.round(Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, values[i] * WEIGHT_STEP))));
        }
        writeShorts(out, java.util.Arrays.copyOfRange(values, weights, values.length));
    }

    private static double[] readParameters(java.io.DataInputStream in, int count, int weights)
            throws java.io.IOException {
        double[] values = new double[count];
        for (int i = 0; i < weights; i++) values[i] = in.readByte() / WEIGHT_STEP;
        double[] rest = readShorts(in, count - weights);
        System.arraycopy(rest, 0, values, weights, rest.length);
        return values;
    }

    private static void writeShorts(java.io.DataOutputStream out, double[] values) throws java.io.IOException {
        for (double v : values) {
            out.writeShort((int) Math.round(Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v * FIXED))));
        }
    }

    private static double[] readShorts(java.io.DataInputStream in, int count) throws java.io.IOException {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) values[i] = in.readShort() / FIXED;
        return values;
    }

    /** How many earlier battles the loaded record was learned over; 0 for a new opponent. */
    public int battlesLearned() { return persistedLoaded ? battlesLearned : 0; }

    /** Our waves' crossings observed this battle: how much the targeting model has seen. */
    public int targetingSampleCount() { return crossings.size(); }

    /** Cumulative cost this battle: calls and milliseconds per kind. */
    public String costs() {
        return String.format(java.util.Locale.ROOT,
                "gun=%d calls %.1f ms (%.1f us each) movement=%d calls %.1f ms (%.1f us each) training=%d calls %.1f ms",
                gunCalls, gunNanos / 1e6, gunCalls == 0 ? 0 : gunNanos / 1e3 / gunCalls,
                movementCalls, movementNanos / 1e6, movementCalls == 0 ? 0 : movementNanos / 1e3 / movementCalls,
                trainCalls, trainNanos / 1e6)
                + String.format(java.util.Locale.ROOT,
                        " reachRejected=%.3f ms reach=%.0f",
                        aim.terminalRequests == 0 ? 0 : (double) aim.terminalRejected / aim.terminalRequests,
                        aim.terminalNanos / 1e6);
    }

    /** DIAGNOSTIC ONLY: enemy waves by calibrated prediction (n, summed prediction, hits). */
    private final double[][] dodgeSelfMap = new double[6][3], dodgeSelfPlanner = new double[6][3];
    private static final double[] DODGE_EDGES = {0.02, 0.05, 0.1, 0.2, 0.4};

    private static void noteDodge(double[][] table, double predicted, boolean hit) {
        int b = 0;
        while (b < DODGE_EDGES.length && predicted >= DODGE_EDGES[b]) b++;
        table[b][0]++;
        table[b][1] += predicted;
        if (hit) table[b][2]++;
    }

    /** DIAGNOSTIC ONLY: the dodge map's calibration on its own data, and as the planner integrates it. */
    public String dodgeDiagnostics() {
        StringBuilder text = new StringBuilder("dodgeSelf");
        for (double[][] table : new double[][][]{dodgeSelfMap, dodgeSelfPlanner}) {
            text.append(table == dodgeSelfMap ? " map[" : " planner[");
            double n = 0, p = 0, h = 0;
            for (double[] row : table) {
                text.append(String.format(java.util.Locale.ROOT, "%.0f:%.3f/%.3f ", row[0],
                        row[0] == 0 ? 0 : row[1] / row[0], row[0] == 0 ? 0 : row[2] / row[0]));
                n += row[0]; p += row[1]; h += row[2];
            }
            text.append(String.format(java.util.Locale.ROOT, "all %.0f:%.3f/%.3f]", n, n == 0 ? 0 : p / n, n == 0 ? 0 : h / n));
        }
        return text.toString();
    }

    /** DIAGNOSTIC ONLY: shots by the uniform chance of their reach. */
    public String reachDiagnostics() {
        String[] names = {"u<0.15", "0.15-0.3", "0.3-0.6", "0.6+"};
        StringBuilder text = new StringBuilder("byReach");
        for (int b = 0; b < 4; b++) {
            double n = Math.max(1, reachShots[b]);
            text.append(String.format(java.util.Locale.ROOT, " %s[n=%d raw=%.3f cal=%.3f hit=%.3f]", names[b],
                    reachShots[b], reachRaw[b] / n, reachCalibrated[b] / n, reachHits[b] / n));
        }
        return text.toString();
    }

    /** Each model's learned weight per dimension: the features in order, then hit and pass recency. */
    public String weightDiagnostics() {
        return "weights targeting=" + movementOfOpponent.weightDiagnostics()
                + " movement=" + gunOfOpponent.weightDiagnostics();
    }

    public String diagnostics() {
        return String.format(java.util.Locale.ROOT,
                "chosenShotMap=%.2f,%.2f enemyPower[errModel=%.3f errLast=%.3f n=%d calls=%d slopeOnOurs=%.2f ours=%.2f theirs=%.2f] targeting[%s shotMap=%.2f,%.2f uniform=%.4f realised=%.4f n=%d] movement[%s dodgeMap=%.2f,%.2f]",
                chosenShotMap.intercept(), chosenShotMap.slope(),
                powerErrors == 0 ? 0 : powerErrorModel / powerErrors, powerErrors == 0 ? 0 : powerErrorLast / powerErrors,
                powerErrors, powerCalls, powerSlope(), ourRecentPower(), enemyRecentPower(),
                movementOfOpponent.diagnostics(), shotMap.intercept(), shotMap.slope(),
                uniformShots == 0 ? 0 : uniformSum / uniformShots,
                uniformShots == 0 ? 0 : (double) uniformHits / uniformShots, uniformShots,
                gunOfOpponent.diagnostics(), dodgeMap.intercept(), dodgeMap.slope());
    }
}
