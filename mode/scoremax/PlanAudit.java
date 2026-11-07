package oog.mega.saguaro.mode.scoremax;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.movement.PathWaveIntersection;

/**
 * DIAGNOSTIC ONLY: how well the chosen plans' predictions match what happens. For every
 * real enemy wave on the chosen path, the hit chance the plan assigned it, kept per band
 * of ticks to impact, and for every shot we fire, the hit chance the plan assigned it;
 * each scored against the outcome when the wave resolves. Changes no decision.
 */
public final class PlanAudit {
    private static final double[] EDGES = {0.02, 0.05, 0.10, 0.20, 0.40};
    private static final String[] BANDS = {"20+", "10-19", "0-9"};

    /** Per enemy wave, the latest prediction in each band; NaN where none was made. */
    private static final Map<Wave, double[]> enemyPredictions = new IdentityHashMap<>();
    private static final Map<Wave, Double> shotPredictions = new IdentityHashMap<>();
    private static long plannedShotTime = Long.MIN_VALUE;
    private static double plannedShotChance = Double.NaN;

    private static final int[][] enemyCount = new int[3][EDGES.length + 1];
    private static final int[][] enemyHits = new int[3][EDGES.length + 1];
    private static final double[][] enemyPredicted = new double[3][EDGES.length + 1];
    private static final int[] shotCount = new int[EDGES.length + 1];
    private static final int[] shotHits = new int[EDGES.length + 1];
    private static final double[] shotPredicted = new double[EDGES.length + 1];

    private PlanAudit() { }

    // DIAGNOSTIC ONLY: the energy race's predictions at the energies each tick's plan starts
    // from, every ten ticks, scored against the round's outcome: whether we were the one
    // left, and the bullet damage each side dealt from then on.
    private static final java.util.List<double[]> raceSamples = new java.util.ArrayList<>();
    private static long lastRaceSample = Long.MIN_VALUE;
    private static boolean roundWon;
    private static final int[] raceCount = new int[10], raceWins = new int[10];
    private static final double[] racePredicted = new double[10], raceOurPredicted = new double[10],
            raceOurActual = new double[10], raceTheirPredicted = new double[10], raceTheirActual = new double[10];

    private static double tablePowerSum, firedPowerSum;
    private static int powerSamples;

    /** DIAGNOSTIC ONLY: the table's chosen power at the current energies, against the power the plan fires. */
    static void recordPower(double tablePower, double firedPower) {
        if (!(tablePower > 0) || !(firedPower > 0)) return;
        tablePowerSum += tablePower;
        firedPowerSum += firedPower;
        powerSamples++;
    }

    static void recordRace(long now, EnergyRaceTable.Outcome outcome, double ourCredited, double enemyCredited) {
        if (outcome == null) return;
        if (lastRaceSample != Long.MIN_VALUE && now >= lastRaceSample && now - lastRaceSample < 10) return;
        lastRaceSample = now;
        raceSamples.add(new double[]{outcome.winProbability, outcome.ourDamage, outcome.enemyDamage,
                ourCredited, enemyCredited});
    }

    public static void onWin() { roundWon = true; }

    /** Whether this round was won, once the round has ended. */
    public static boolean roundWon() { return roundWon; }

    /** The round ended: score its samples against what happened from each of them. */
    public static void finishRound(double ourCredited, double enemyCredited) {
        for (double[] sample : raceSamples) {
            int b = Math.max(0, Math.min(9, (int) (sample[0] * 10)));
            raceCount[b]++;
            racePredicted[b] += sample[0];
            if (roundWon) raceWins[b]++;
            raceOurPredicted[b] += sample[1];
            raceOurActual[b] += ourCredited - sample[3];
            raceTheirPredicted[b] += sample[2];
            raceTheirActual[b] += enemyCredited - sample[4];
        }
        raceSamples.clear();
    }

    public static String raceCalibration() {
        StringBuilder text = new StringBuilder(String.format(java.util.Locale.ROOT,
                "race power table=%.2f fired=%.2f n=%d; calibration (n: predicted win/realised | our damage predicted/actual | theirs)",
                powerSamples == 0 ? 0 : tablePowerSum / powerSamples, powerSamples == 0 ? 0 : firedPowerSum / powerSamples,
                powerSamples));
        for (int b = 0; b < 10; b++) {
            if (raceCount[b] == 0) continue;
            double n = raceCount[b];
            text.append(String.format(java.util.Locale.ROOT, " [%d: %.2f/%.2f | %.0f/%.0f | %.0f/%.0f]",
                    raceCount[b], racePredicted[b] / n, raceWins[b] / n,
                    raceOurPredicted[b] / n, raceOurActual[b] / n, raceTheirPredicted[b] / n, raceTheirActual[b] / n));
        }
        return text.toString();
    }

    public static void startRound() {
        raceSamples.clear();
        lastRaceSample = Long.MIN_VALUE;
        roundWon = false;
        enemyPredictions.clear();
        shotPredictions.clear();
        plannedShotTime = Long.MIN_VALUE;
    }

    /** The plan chosen this tick: its predictions for the enemy waves on its path and its shot. */
    static void recordPlan(long now, ShotPlanner.ShotSelection shot, List<PathWaveIntersection> intersections,
                           double firePower) {
        if (intersections != null) {
            for (PathWaveIntersection intersection : intersections) {
                if (intersection == null || intersection.wave == null || !intersection.wave.isEnemy
                        || intersection.wave.isVirtual) continue;
                long ticks = intersection.firstContactTime - now;
                int band = ticks >= 20 ? 0 : ticks >= 10 ? 1 : 2;
                double[] slots = enemyPredictions.computeIfAbsent(intersection.wave,
                        w -> new double[]{Double.NaN, Double.NaN, Double.NaN});
                slots[band] = EventScheduleBuilder.waveHitProbability(shot, intersection);
            }
        }
        if (firePower > 0 && shot != null && Double.isFinite(shot.hitProbability)) {
            plannedShotTime = now;
            plannedShotChance = shot.hitProbability;
        }
    }

    /** One of our waves was fired: the plan's hit chance for it, if the plan fired it. */
    public static void onMyWaveFired(Wave wave) {
        if (wave.fireTime - plannedShotTime <= 1 && wave.fireTime >= plannedShotTime) {
            shotPredictions.put(wave, plannedShotChance);
        }
    }

    public static void onMyWaveResolved(Wave wave, boolean hit) {
        Double predicted = shotPredictions.remove(wave);
        if (predicted == null || wave.bulletCollided) return;
        int bucket = bucket(predicted);
        shotCount[bucket]++;
        shotPredicted[bucket] += predicted;
        if (hit) shotHits[bucket]++;
    }

    /** An enemy wave resolved: a hit on us, or a pass. Collisions are not scored. */
    public static void onEnemyWaveResolved(Wave wave, boolean hit) {
        double[] slots = enemyPredictions.remove(wave);
        if (slots == null) return;
        for (int band = 0; band < 3; band++) {
            if (!Double.isFinite(slots[band])) continue;
            int bucket = bucket(slots[band]);
            enemyCount[band][bucket]++;
            enemyPredicted[band][bucket] += slots[band];
            if (hit) enemyHits[band][bucket]++;
        }
    }

    public static void onEnemyWaveCollided(Wave wave) {
        enemyPredictions.remove(wave);
    }

    private static int bucket(double p) {
        int b = 0;
        while (b < EDGES.length && p >= EDGES[b]) b++;
        return b;
    }

    /** DIAGNOSTIC ONLY: the energy race's last view of a fresh round. */
    public static String raceReport() { return EnergyRaceTable.lastPass; }

    public static String report() {
        StringBuilder text = new StringBuilder("audit ours");
        append(text, shotCount, shotPredicted, shotHits);
        for (int band = 0; band < 3; band++) {
            text.append(" theirs").append(BANDS[band]);
            append(text, enemyCount[band], enemyPredicted[band], enemyHits[band]);
        }
        return text.toString();
    }

    private static void append(StringBuilder text, int[] count, double[] predicted, int[] hits) {
        int n = 0, h = 0;
        double p = 0;
        text.append('[');
        for (int b = 0; b < count.length; b++) {
            if (b > 0) text.append(' ');
            text.append(String.format(java.util.Locale.ROOT, "%d:%.3f/%.3f", count[b],
                    count[b] == 0 ? 0 : predicted[b] / count[b], count[b] == 0 ? 0 : (double) hits[b] / count[b]));
            n += count[b];
            h += hits[b];
            p += predicted[b];
        }
        text.append(String.format(java.util.Locale.ROOT, " all %d:%.3f/%.3f]", n, n == 0 ? 0 : p / n,
                n == 0 ? 0 : (double) h / n));
    }
}
