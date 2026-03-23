package oog.mega.saguaro.info.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.Wave;

public final class BulletPowerHitRateTracker {
    // Opponent hit-rate baselines are cheap caches. When the compact layout changes, bump the
    // section version and let BattleDataStore discard stale files instead of maintaining migrations.
    private static final int SECTION_VERSION = 1;
    private static final int FLIGHT_BIN_COUNT = 8;
    private static final int SECTION_BYTES = (FLIGHT_BIN_COUNT + 1) * 2;
    private static final double[] FLIGHT_BUCKET_UPPER_BOUNDS = new double[]{
            8.0, 12.0, 16.0, 22.0, 30.0, 40.0, 55.0, Double.POSITIVE_INFINITY
    };
    private static final double[] FLIGHT_BUCKET_REPRESENTATIVE_TICKS = new double[]{
            6.0, 10.0, 14.0, 19.0, 26.0, 35.0, 47.0, 65.0
    };

    public static final BulletPowerHitRateTracker INSTANCE = new BulletPowerHitRateTracker();

    private static String trackedOpponentName;
    private static boolean persistedBaselineLoaded;
    private static double persistedGlobalShots;
    private static double persistedGlobalHits;
    private static final double[] persistedFlightShots = new double[FLIGHT_BIN_COUNT];
    private static final double[] persistedFlightHits = new double[FLIGHT_BIN_COUNT];

    private final Map<Wave, ShotRecord> activeShots = new IdentityHashMap<>();
    private final double[] currentBattleFlightShots = new double[FLIGHT_BIN_COUNT];
    private final double[] currentBattleFlightHits = new double[FLIGHT_BIN_COUNT];
    private double currentBattleGlobalShots;
    private double currentBattleGlobalHits;
    private boolean trackingEnabled;

    private static final class ShotRecord {
        final int flightBucket;

        ShotRecord(int flightBucket) {
            this.flightBucket = flightBucket;
        }
    }

    private BulletPowerHitRateTracker() {
    }

    public static int persistenceSectionVersion() {
        return SECTION_VERSION;
    }

    public static void startBattlePersistence() {
        trackedOpponentName = null;
        persistedBaselineLoaded = false;
        persistedGlobalShots = 0.0;
        persistedGlobalHits = 0.0;
        clearArray(persistedFlightShots);
        clearArray(persistedFlightHits);
        INSTANCE.resetCurrentBattle();
    }

    public static void ensureOpponentBaselineLoaded(String opponentName, int sectionVersion, byte[] payload) {
        if (opponentName == null || opponentName.isEmpty()) {
            throw new IllegalArgumentException("Bullet hit-rate tracker requires a non-empty opponent name");
        }
        if (trackedOpponentName == null) {
            trackedOpponentName = opponentName;
            persistedBaselineLoaded = false;
            persistedGlobalShots = 0.0;
            persistedGlobalHits = 0.0;
            clearArray(persistedFlightShots);
            clearArray(persistedFlightHits);
            if (payload != null) {
                loadPersistedPayload(sectionVersion, payload);
            }
            return;
        }
        if (!trackedOpponentName.equals(opponentName)) {
            throw new IllegalStateException(
                    "Bullet hit-rate tracker expected a single opponent but saw: "
                            + trackedOpponentName + " and " + opponentName);
        }
    }

    public static boolean hasPersistedSectionData() {
        return trackedOpponentName != null
                && combinedShots(true, persistedGlobalShots, INSTANCE.currentBattleGlobalShots) > 0.0;
    }

    public static boolean isPersistedBaselineLoaded() {
        return persistedBaselineLoaded;
    }

    public static double getPersistedOverallHitRate() {
        if (!persistedBaselineLoaded || !(persistedGlobalShots > 0.0)) {
            return Double.NaN;
        }
        return clamp(persistedGlobalHits / persistedGlobalShots, 0.0, 1.0);
    }

    public static byte[] createPersistedSectionPayload(int maxPayloadBytes, boolean includeCurrentBattleData) {
        if (maxPayloadBytes < SECTION_BYTES) {
            throw new IllegalStateException(
                    "Insufficient data quota to save bullet hit-rate payload: payload budget="
                            + maxPayloadBytes + " bytes");
        }
        double globalShots = combinedShots(includeCurrentBattleData, persistedGlobalShots, INSTANCE.currentBattleGlobalShots);
        if (!(globalShots > 0.0)) {
            return null;
        }
        double globalHits = combinedHits(includeCurrentBattleData, persistedGlobalHits, INSTANCE.currentBattleGlobalHits);
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            writeStats(out, globalHits, globalShots);
            for (int i = 0; i < FLIGHT_BIN_COUNT; i++) {
                double flightShots = combinedShots(
                        includeCurrentBattleData,
                        persistedFlightShots[i],
                        INSTANCE.currentBattleFlightShots[i]);
                double flightHits = combinedHits(
                        includeCurrentBattleData,
                        persistedFlightHits[i],
                        INSTANCE.currentBattleFlightHits[i]);
                writeStats(out, flightHits, flightShots);
            }
            out.flush();
            byte[] payload = raw.toByteArray();
            if (payload.length != SECTION_BYTES) {
                throw new IllegalStateException("Unexpected bullet hit-rate payload length");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build bullet hit-rate payload", e);
        }
    }

    public void startRound() {
        activeShots.clear();
    }

    public void setTrackingEnabled(boolean trackingEnabled) {
        this.trackingEnabled = trackingEnabled;
    }

    public void onMyWaveFired(Wave wave) {
        if (!trackingEnabled || wave == null || wave.isEnemy || wave.fireTimeContext == null) {
            return;
        }
        int flightBucket = flightBucket(wave.fireTimeContext.flightTicks);
        activeShots.put(wave, new ShotRecord(flightBucket));
    }

    public void onMyWaveHit(Wave wave) {
        recordOutcome(wave, true);
    }

    public void onMyWaveMiss(Wave wave) {
        recordOutcome(wave, false);
    }

    public void onMyWaveInvalidated(Wave wave) {
        if (wave != null) {
            activeShots.remove(wave);
        }
    }

    public double estimateHitRate(int flightTicks) {
        int flightBucket = flightBucket(flightTicks);

        double globalHits = persistedGlobalHits + currentBattleGlobalHits;
        double globalShots = persistedGlobalShots + currentBattleGlobalShots;
        double globalMean = posteriorMean(
                globalHits,
                globalShots,
                priorMeanForFlightBucket(flightBucket),
                BotConfig.Learning.ENEMY_HIT_RATE_GLOBAL_PRIOR_STRENGTH);

        double flightHits = persistedFlightHits[flightBucket] + currentBattleFlightHits[flightBucket];
        double flightShots = persistedFlightShots[flightBucket] + currentBattleFlightShots[flightBucket];
        double flightMean = posteriorMean(
                flightHits,
                flightShots,
                globalMean,
                BotConfig.Learning.ENEMY_HIT_RATE_FLIGHT_PRIOR_STRENGTH);
        return clamp(
                flightMean,
                BotConfig.Learning.ENEMY_HIT_RATE_MIN,
                BotConfig.Learning.ENEMY_HIT_RATE_MAX);
    }

    private void recordOutcome(Wave wave, boolean hit) {
        ShotRecord record = wave != null ? activeShots.remove(wave) : null;
        if (record == null) {
            return;
        }
        currentBattleGlobalShots += 1.0;
        if (hit) {
            currentBattleGlobalHits += 1.0;
        }
        currentBattleFlightShots[record.flightBucket] += 1.0;
        if (hit) {
            currentBattleFlightHits[record.flightBucket] += 1.0;
        }
    }

    private void resetCurrentBattle() {
        activeShots.clear();
        currentBattleGlobalShots = 0.0;
        currentBattleGlobalHits = 0.0;
        trackingEnabled = false;
        clearArray(currentBattleFlightShots);
        clearArray(currentBattleFlightHits);
    }

    private static void loadPersistedPayload(int sectionVersion, byte[] payload) {
        if (sectionVersion != SECTION_VERSION) {
            throw new IllegalStateException("Unsupported bullet hit-rate section version " + sectionVersion);
        }
        if (payload.length != SECTION_BYTES) {
            throw new IllegalStateException("Unexpected bullet hit-rate payload length");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            double[] globalStats = readStats(in);
            persistedGlobalHits = globalStats[0];
            persistedGlobalShots = globalStats[1];
            for (int i = 0; i < FLIGHT_BIN_COUNT; i++) {
                double[] flightStats = readStats(in);
                persistedFlightHits[i] = flightStats[0];
                persistedFlightShots[i] = flightStats[1];
            }
            if (in.available() != 0) {
                throw new IllegalStateException("Bullet hit-rate payload contained trailing bytes");
            }
            persistedBaselineLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable bullet hit-rate payload", e);
        }
    }

    private static void writeStats(DataOutputStream out, double hits, double shots) throws IOException {
        double clampedShots = clamp(
                shots,
                0.0,
                BotConfig.Learning.BULLET_POWER_MAX_EFFECTIVE_SHOTS);
        double rate = clampedShots > 0.0 ? clamp(hits / shots, 0.0, 1.0) : 0.0;
        out.writeByte(encodeRate(rate));
        out.writeByte(encodeShots(clampedShots));
    }

    private static double[] readStats(DataInputStream in) throws IOException {
        double rate = decodeRate(in.readUnsignedByte());
        double shots = decodeShots(in.readUnsignedByte());
        double hits = clamp(rate * shots, 0.0, shots);
        return new double[]{hits, shots};
    }

    private static int encodeRate(double rate) {
        return (int) Math.round(clamp(rate, 0.0, 1.0) * 255.0);
    }

    private static double decodeRate(int encodedRate) {
        return clamp(encodedRate / 255.0, 0.0, 1.0);
    }

    private static int encodeShots(double shots) {
        double normalized = Math.sqrt(
                clamp(shots, 0.0, BotConfig.Learning.BULLET_POWER_MAX_EFFECTIVE_SHOTS)
                        / BotConfig.Learning.BULLET_POWER_MAX_EFFECTIVE_SHOTS);
        return (int) Math.round(normalized * 255.0);
    }

    private static double decodeShots(int encodedShots) {
        double normalized = clamp(encodedShots / 255.0, 0.0, 1.0);
        return normalized * normalized * BotConfig.Learning.BULLET_POWER_MAX_EFFECTIVE_SHOTS;
    }

    private static double posteriorMean(double hits,
                                        double shots,
                                        double priorMean,
                                        double priorStrength) {
        return (hits + priorMean * priorStrength) / (shots + priorStrength);
    }

    private static int flightBucket(int flightTicks) {
        double clampedTicks = Math.max(1.0, flightTicks);
        for (int i = 0; i < FLIGHT_BUCKET_UPPER_BOUNDS.length; i++) {
            if (clampedTicks <= FLIGHT_BUCKET_UPPER_BOUNDS[i]) {
                return i;
            }
        }
        return FLIGHT_BIN_COUNT - 1;
    }

    private static double priorMeanForFlightBucket(int flightBucket) {
        double flightTicks = FLIGHT_BUCKET_REPRESENTATIVE_TICKS[flightBucket];
        double prior = 0.72 / Math.sqrt(flightTicks + 4.0);
        return clamp(prior, 0.05, 0.3);
    }

    private static double combinedShots(boolean includeCurrentBattleData, double persistedShots, double currentBattleShots) {
        return includeCurrentBattleData ? persistedShots + currentBattleShots : persistedShots;
    }

    private static double combinedHits(boolean includeCurrentBattleData, double persistedHits, double currentBattleHits) {
        return includeCurrentBattleData ? persistedHits + currentBattleHits : persistedHits;
    }

    private static void clearArray(double[] values) {
        for (int i = 0; i < values.length; i++) {
            values[i] = 0.0;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
