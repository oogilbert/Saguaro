package oog.mega.saguaro.mode.antisurfer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.Wave;

final class AntiSurferRecentWeighting {
    private static final int GUN_RESOLVED_WINDOW = 8;
    private static final int MOVEMENT_RESOLVED_WINDOW = 5;
    private static final int IN_FLIGHT_WINDOW = 8;
    private static final double WAVE_DECAY = 0.65;
    private static final double MIN_ACTIVE_WEIGHT = 1e-3;
    private static final double INTERVAL_DISTANCE_SIGMA = 0.25;

    private final Deque<WaveScoreRecord> recentGunWaveScores = new ArrayDeque<WaveScoreRecord>();
    private final Deque<WaveScoreRecord> recentMovementWaveScores = new ArrayDeque<WaveScoreRecord>();

    void clear() {
        recentGunWaveScores.clear();
        recentMovementWaveScores.clear();
    }

    void onResolvedGunWave(Wave wave, double gfMin, double gfMax) {
        addWaveScore(recentGunWaveScores, scoreCentersAgainstInterval(wave, gfMin, gfMax), GUN_RESOLVED_WINDOW);
    }

    void onInvalidatedGunWave(Wave wave, Info info) {
        if (wave == null || info == null || info.getRobot() == null) {
            return;
        }
        EnemyInfo enemy = info.getEnemy();
        double[] reachableInterval = AntiSurferReachability.computeGunWaveReachableGfInterval(
                wave,
                enemy,
                info.getRobot().getTime(),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        if (reachableInterval == null) {
            return;
        }
        addWaveScore(recentGunWaveScores, scoreCentersAgainstInterval(
                wave,
                reachableInterval[0],
                reachableInterval[1]), GUN_RESOLVED_WINDOW);
    }

    void onResolvedMovementWave(Wave wave, double gfMin, double gfMax) {
        addWaveScore(recentMovementWaveScores, scoreCentersAgainstInterval(wave, gfMin, gfMax), MOVEMENT_RESOLVED_WINDOW);
    }

    double[] createGunWeights(Info info) {
        double[] resolvedScores = aggregateRecentScores(recentGunWaveScores, GUN_RESOLVED_WINDOW);
        double[] inFlightScores = aggregateInFlightGunScores(info);
        return combineScores(resolvedScores, inFlightScores);
    }

    double[] createMovementWeights() {
        return combineScores(
                aggregateRecentScores(recentMovementWaveScores, MOVEMENT_RESOLVED_WINDOW),
                null);
    }

    void prepareWaveRenderState(Info info, List<Wave> enemyWaves, List<Wave> myWaves) {
        if (enemyWaves != null) {
            for (Wave wave : enemyWaves) {
                wave.renderReachableGfInterval = null;
            }
        }
        if (myWaves == null) {
            return;
        }
        if (info == null || info.getRobot() == null) {
            for (Wave wave : myWaves) {
                wave.renderReachableGfInterval = null;
            }
            return;
        }
        EnemyInfo enemy = info.getEnemy();
        long currentTime = info.getRobot().getTime();
        for (Wave wave : myWaves) {
            wave.renderReachableGfInterval = AntiSurferReachability.computeGunWaveReachableGfInterval(
                    wave,
                    enemy,
                    currentTime,
                    info.getBattlefieldWidth(),
                    info.getBattlefieldHeight());
        }
    }

    private double[] aggregateInFlightGunScores(Info info) {
        if (info == null || info.getRobot() == null) {
            return null;
        }
        List<Wave> myWaves = info.getMyWaves();
        if (myWaves == null || myWaves.isEmpty()) {
            return null;
        }
        EnemyInfo enemy = info.getEnemy();
        long currentTime = info.getRobot().getTime();
        double[] numerator = new double[AntiSurferExpertId.VALUES.length];
        double[] denominator = new double[AntiSurferExpertId.VALUES.length];
        double weight = 1.0;
        int used = 0;
        for (int i = myWaves.size() - 1; i >= 0 && used < IN_FLIGHT_WINDOW; i--) {
            Wave wave = myWaves.get(i);
            double[] interval = AntiSurferReachability.computeGunWaveReachableGfInterval(
                    wave,
                    enemy,
                    currentTime,
                    info.getBattlefieldWidth(),
                    info.getBattlefieldHeight());
            if (interval == null) {
                continue;
            }
            double[] scores = scoreCentersAgainstInterval(wave, interval[0], interval[1]);
            accumulateScores(scores, weight, numerator, denominator);
            weight *= WAVE_DECAY;
            used++;
        }
        return finalizeScores(numerator, denominator);
    }

    private static void addWaveScore(Deque<WaveScoreRecord> queue, double[] scores, int windowSize) {
        if (scores == null) {
            return;
        }
        queue.addLast(new WaveScoreRecord(scores));
        while (queue.size() > windowSize * 2) {
            queue.removeFirst();
        }
    }

    private static double[] aggregateRecentScores(Deque<WaveScoreRecord> queue, int maxWaves) {
        if (queue.isEmpty()) {
            return null;
        }
        double[] numerator = new double[AntiSurferExpertId.VALUES.length];
        double[] denominator = new double[AntiSurferExpertId.VALUES.length];
        double weight = 1.0;
        int used = 0;
        for (Iterator<WaveScoreRecord> it = queue.descendingIterator(); it.hasNext() && used < maxWaves; used++) {
            accumulateScores(it.next().scores, weight, numerator, denominator);
            weight *= WAVE_DECAY;
        }
        return finalizeScores(numerator, denominator);
    }

    private static void accumulateScores(double[] scores,
                                         double weight,
                                         double[] numerator,
                                         double[] denominator) {
        for (int i = 0; i < scores.length; i++) {
            if (!Double.isFinite(scores[i])) {
                continue;
            }
            numerator[i] += weight * scores[i];
            denominator[i] += weight;
        }
    }

    private static double[] finalizeScores(double[] numerator, double[] denominator) {
        double[] scores = new double[numerator.length];
        for (int i = 0; i < numerator.length; i++) {
            scores[i] = denominator[i] > 0.0
                    ? numerator[i] / denominator[i]
                    : Double.NaN;
        }
        return scores;
    }

    private static double[] combineScores(double[] primaryScores, double[] secondaryScores) {
        double[] weights = new double[AntiSurferExpertId.VALUES.length];
        for (int i = 0; i < weights.length; i++) {
            double combined = 0.0;
            int contributors = 0;
            if (primaryScores != null && Double.isFinite(primaryScores[i])) {
                combined += primaryScores[i];
                contributors++;
            }
            if (secondaryScores != null && Double.isFinite(secondaryScores[i])) {
                combined += secondaryScores[i];
                contributors++;
            }
            if (contributors == 0) {
                weights[i] = 1.0;
            } else {
                weights[i] = Math.max(MIN_ACTIVE_WEIGHT, combined / contributors);
            }
        }
        return weights;
    }

    private static double[] scoreCentersAgainstInterval(Wave wave, double minGf, double maxGf) {
        if (wave == null || wave.fireTimeRenderGfMarkers == null) {
            return null;
        }
        double[] scores = new double[AntiSurferExpertId.VALUES.length];
        for (int i = 0; i < scores.length; i++) {
            double center = i < wave.fireTimeRenderGfMarkers.length
                    ? wave.fireTimeRenderGfMarkers[i]
                    : Double.NaN;
            if (!Double.isFinite(center)) {
                scores[i] = Double.NaN;
                continue;
            }
            scores[i] = scoreCenterAgainstInterval(center, minGf, maxGf);
        }
        return scores;
    }

    private static double scoreCenterAgainstInterval(double center, double minGf, double maxGf) {
        if (center >= minGf && center <= maxGf) {
            return 1.0;
        }
        double distance = center < minGf ? (minGf - center) : (center - maxGf);
        double normalizedDistance = distance / INTERVAL_DISTANCE_SIGMA;
        return Math.exp(-0.5 * normalizedDistance * normalizedDistance);
    }

    private static final class WaveScoreRecord {
        final double[] scores;

        WaveScoreRecord(double[] scores) {
            this.scores = scores;
        }
    }
}
