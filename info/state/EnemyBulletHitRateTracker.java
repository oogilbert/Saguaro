package oog.mega.saguaro.info.state;

import java.util.IdentityHashMap;
import java.util.Map;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.Wave;

public final class EnemyBulletHitRateTracker {
    private static final int FLIGHT_BIN_COUNT = 8;
    private static final double[] FLIGHT_BUCKET_UPPER_BOUNDS = new double[]{
            8.0, 12.0, 16.0, 22.0, 30.0, 40.0, 55.0, Double.POSITIVE_INFINITY
    };
    private static final double[] FLIGHT_BUCKET_REPRESENTATIVE_TICKS = new double[]{
            6.0, 10.0, 14.0, 19.0, 26.0, 35.0, 47.0, 65.0
    };

    private static final class ShotRecord {
        final int flightBucket;
        final double[] featurePoint;

        ShotRecord(int flightBucket, double[] featurePoint) {
            this.flightBucket = flightBucket;
            this.featurePoint = featurePoint;
        }
    }

    private final Map<Wave, ShotRecord> activeShots = new IdentityHashMap<>();
    private final LocalHitRateKnnModel localModel = new LocalHitRateKnnModel();
    private final double[] battleFlightShots = new double[FLIGHT_BIN_COUNT];
    private final double[] battleFlightHits = new double[FLIGHT_BIN_COUNT];
    private double battleGlobalShots;
    private double battleGlobalHits;
    private boolean trackingEnabled;

    public void startBattle() {
        battleGlobalShots = 0.0;
        battleGlobalHits = 0.0;
        trackingEnabled = false;
        activeShots.clear();
        clearArray(battleFlightShots);
        clearArray(battleFlightHits);
        localModel.clear();
    }

    public void startRound() {
        activeShots.clear();
    }

    public void setTrackingEnabled(boolean trackingEnabled) {
        this.trackingEnabled = trackingEnabled;
    }

    public void onEnemyWaveFired(Wave wave) {
        if (!trackingEnabled || wave == null || !wave.isEnemy || wave.fireTimeContext == null) {
            return;
        }
        int flightBucket = LocalHitRateKnnModel.flightBucket(
                wave.fireTimeContext.flightTicks,
                FLIGHT_BUCKET_UPPER_BOUNDS);
        activeShots.put(wave, new ShotRecord(flightBucket, LocalHitRateKnnModel.pointForWave(wave)));
    }

    public void onEnemyWaveHit(Wave wave) {
        recordOutcome(wave, true);
    }

    public void onEnemyWaveMiss(Wave wave) {
        recordOutcome(wave, false);
    }

    public void onEnemyWaveInvalidated(Wave wave) {
        if (wave != null) {
            activeShots.remove(wave);
        }
    }

    public double estimateHitRate(int flightTicks) {
        return estimateHitRate(new double[]{
                clamp(Math.max(1.0, flightTicks) / 70.0, 0.0, 1.0),
                0.0,
                1.0
        }, flightTicks);
    }

    public double estimateHitRate(double sourceX,
                                  double sourceY,
                                  double targetX,
                                  double targetY,
                                  double bulletSpeed,
                                  double battlefieldWidth,
                                  double battlefieldHeight) {
        double[] featurePoint = LocalHitRateKnnModel.pointForGeometry(
                sourceX,
                sourceY,
                targetX,
                targetY,
                bulletSpeed,
                battlefieldWidth,
                battlefieldHeight);
        int flightTicks = Wave.nominalFlightTicks(
                Math.hypot(targetX - sourceX, targetY - sourceY),
                bulletSpeed);
        return estimateHitRate(featurePoint, flightTicks);
    }

    private void recordOutcome(Wave wave, boolean hit) {
        ShotRecord record = wave != null ? activeShots.remove(wave) : null;
        if (record == null) {
            return;
        }
        battleGlobalShots += 1.0;
        if (hit) {
            battleGlobalHits += 1.0;
        }
        battleFlightShots[record.flightBucket] += 1.0;
        if (hit) {
            battleFlightHits[record.flightBucket] += 1.0;
        }
        localModel.addObservation(record.featurePoint, hit);
    }

    private static double priorMeanForFlightBucket(int flightBucket) {
        double representativeTicks = FLIGHT_BUCKET_REPRESENTATIVE_TICKS[flightBucket];
        double centeredTicks = representativeTicks - 16.0;
        return clamp(
                0.19 - 0.0015 * centeredTicks,
                BotConfig.Learning.ENEMY_HIT_RATE_MIN,
                BotConfig.Learning.ENEMY_HIT_RATE_MAX);
    }

    private static double posteriorMean(double hits, double shots, double priorMean, double priorStrength) {
        double totalShots = Math.max(0.0, shots) + priorStrength;
        if (!(totalShots > 0.0)) {
            return priorMean;
        }
        double totalHits = Math.max(0.0, hits) + priorMean * priorStrength;
        return totalHits / totalShots;
    }

    private static void clearArray(double[] values) {
        for (int i = 0; i < values.length; i++) {
            values[i] = 0.0;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double estimateHitRate(double[] featurePoint, int flightTicks) {
        int flightBucket = LocalHitRateKnnModel.flightBucket(flightTicks, FLIGHT_BUCKET_UPPER_BOUNDS);
        double globalMean = posteriorMean(
                battleGlobalHits,
                battleGlobalShots,
                priorMeanForFlightBucket(flightBucket),
                BotConfig.Learning.ENEMY_HIT_RATE_GLOBAL_PRIOR_STRENGTH);
        double flightMean = posteriorMean(
                battleFlightHits[flightBucket],
                battleFlightShots[flightBucket],
                globalMean,
                BotConfig.Learning.ENEMY_HIT_RATE_FLIGHT_PRIOR_STRENGTH);
        return localModel.estimate(
                featurePoint,
                flightMean,
                BotConfig.Learning.ENEMY_HIT_RATE_FLIGHT_PRIOR_STRENGTH,
                BotConfig.Learning.ENEMY_HIT_RATE_MIN,
                BotConfig.Learning.ENEMY_HIT_RATE_MAX);
    }
}
