package oog.mega.saguaro.info.learning.rule;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.math.MathUtils;

/**
 * The chance of winning the round, as the energy race predicts it, mapped to the rate
 * rounds are actually won from such states, and moved by where the robots stand:
 *
 *   logit P(win) = a + b * logit(race) + c1 * our wall room + c2 * their wall room + c3 * range
 *
 * The race takes both sides' hit rates as known and fixed. They are estimates, and they
 * drift, so rounds from states it calls nearly lost are won far more often than it says
 * and those it calls nearly won are lost more often -- measured against twelve opponents
 * across the rumble's range, October 31. And it sees only the energies: nothing of the
 * position a plan leaves us in, which the plan is chosen by. A logistic map fitted on the
 * race's own predictions, sampled through each round and labelled with how the round
 * ended, corrects the first; the position terms, starting at zero, learn the second from
 * the same outcomes. They are centred on typical values: all three are positive, so
 * uncentred they share the intercept's job, and against an opponent we mostly lose to,
 * the offset the map needs leaked into them as a slope -- against BeepBoop, range -0.6
 * and wall room -0.4 after fifteen battles -- that told the planner closing in and
 * hugging walls won rounds, and the reverse against opponents we mostly beat.
 *
 * The race's rest-of-round damage is mapped the same way, one scale per side: it takes
 * the hit rates and powers of now for the whole remaining round, while late in a round
 * both fall, so its damage ran over what followed in most battles measured -- 40.9 for
 * 33.0 against Gnarly, 38.5 for 29.3 against M1Abrams. Each round's mean actual damage
 * from each sample on, against the prediction there, with ten rounds of the prior's scale.
 *
 * Every sample of a round shares its one outcome, so each round weighs one observation in
 * the fit, whatever its length. A Gaussian prior holds the map at the identity, or at the
 * map earlier battles against this opponent settled on, until the rounds show otherwise.
 */
public final class RoundWinCalibration {
    public static final RoundWinCalibration INSTANCE = new RoundWinCalibration();

    static final int PARAMETERS = 5;
    private static final int MAX_ROUNDS = 200;
    private static final int SAMPLE_INTERVAL = 10;
    /** Prior precision on each parameter, in rounds. */
    private static final double PRIOR = 10;
    private static final double MIN_WIN = 1e-4;
    /** Wall room and range scales: a wall matters within a few ticks' travel; range across the field. */
    private static final double WALL_SCALE = 150, RANGE_SCALE = 800, HALF_BODY = 18;
    /** Typical wall room and range on those scales, which the position terms are centred on. */
    private static final double WALL_CENTRE = 0.7, RANGE_CENTRE = 0.6;

    private final double[] theta = {0, 1, 0, 0, 0};
    private final double[] prior = {0, 1, 0, 0, 0};
    private final List<double[][]> rounds = new ArrayList<>();
    private final List<double[]> current = new ArrayList<>();
    /** Per sample of this round: predicted rest-of-round damage, ours then theirs, and the damage credited so far. */
    private final List<double[]> currentDamage = new ArrayList<>();
    /**
     * This battle's rounds: summed predicted and actual remaining damage over their samples,
     * ours then theirs. Summed, not averaged per round: the planner asks the race about
     * states, so the scale is for the average state, and averaging each round first would
     * weigh a quick kill's few early samples -- all of whose damage is still to come -- as
     * heavily as a long round's many, which against Gnarly drove the scale to 1.3 for a race
     * that ran over by a sixth.
     */
    private final List<double[]> damageRounds = new ArrayList<>();
    private double ourScale = 1, theirScale = 1, ourScalePrior = 1, theirScalePrior = 1;
    private long lastSample = Long.MIN_VALUE;

    private RoundWinCalibration() { }

    /** Where the robots stand, as the map reads it: our wall room, theirs, and the range. */
    public static double[] position(double ourX, double ourY, double enemyX, double enemyY,
                                    double width, double height) {
        return new double[]{wallRoom(ourX, ourY, width, height), wallRoom(enemyX, enemyY, width, height),
                Math.min(1, MathUtils.hypot(enemyX - ourX, enemyY - ourY) / RANGE_SCALE)};
    }

    private static double wallRoom(double x, double y, double width, double height) {
        double room = Math.min(Math.min(x, width - x), Math.min(y, height - y)) - HALF_BODY;
        return Math.max(0, Math.min(1, room / WALL_SCALE));
    }

    private static double[] inputs(double win, double[] position) {
        double w = Math.max(MIN_WIN, Math.min(1 - MIN_WIN, win));
        return new double[]{1, Math.log(w / (1 - w)),
                position[0] - WALL_CENTRE, position[1] - WALL_CENTRE, position[2] - RANGE_CENTRE};
    }

    /** The chance of winning from a state the race gives this chance, at this position. */
    public double calibrate(double win, double[] position) {
        if (!(win > 0 && win < 1) || position == null) return win;
        double[] x = inputs(win, position);
        double z = 0;
        for (int i = 0; i < PARAMETERS; i++) z += theta[i] * x[i];
        return 1 / (1 + Math.exp(-z));
    }

    public void startRound() {
        current.clear();
        currentDamage.clear();
        lastSample = Long.MIN_VALUE;
    }

    /** The race's chance and where we stand, every few ticks of the round. */
    public void sample(long now, double win, double[] position, double ourDamage, double theirDamage,
                       double ourCredited, double theirCredited) {
        if (!(win > 0 && win < 1) || position == null) return;
        if (lastSample != Long.MIN_VALUE && now >= lastSample && now - lastSample < SAMPLE_INTERVAL) return;
        lastSample = now;
        current.add(inputs(win, position));
        if (Double.isFinite(ourDamage) && Double.isFinite(theirDamage)) {
            currentDamage.add(new double[]{ourDamage, theirDamage, ourCredited, theirCredited});
        }
    }

    /** The race's rest-of-round damage, ours and theirs, as it realises. */
    public double ourDamage(double predicted) { return ourScale * predicted; }
    public double theirDamage(double predicted) { return theirScale * predicted; }

    /** The round ended: its samples join the fit, labelled with how it ended. */
    public void endRound(boolean won, double ourCredited, double theirCredited) {
        endDamageRound(ourCredited, theirCredited);
        if (current.isEmpty()) return;
        double[][] round = new double[current.size() + 1][];
        for (int i = 0; i < current.size(); i++) round[i] = current.get(i);
        round[current.size()] = new double[]{won ? 1 : 0};
        rounds.add(round);
        while (rounds.size() > MAX_ROUNDS) rounds.remove(0);
        current.clear();
        fit();
    }

    private void endDamageRound(double ourCredited, double theirCredited) {
        if (currentDamage.isEmpty()) return;
        double[] round = new double[4];
        for (double[] s : currentDamage) {
            round[0] += s[0];
            round[1] += Math.max(0, ourCredited - s[2]);
            round[2] += s[1];
            round[3] += Math.max(0, theirCredited - s[3]);
        }
        currentDamage.clear();
        damageRounds.add(round);
        double ourPredicted = 0, ourActual = 0, theirPredicted = 0, theirActual = 0;
        for (double[] r : damageRounds) {
            ourPredicted += r[0]; ourActual += r[1]; theirPredicted += r[2]; theirActual += r[3];
        }
        int n = damageRounds.size();
        // The prior as PRIOR rounds whose actual damage was its scale times the mean prediction.
        ourScale = clampScale((ourActual + PRIOR * ourScalePrior * ourPredicted / n) / (ourPredicted * (1 + PRIOR / n)));
        theirScale = clampScale((theirActual + PRIOR * theirScalePrior * theirPredicted / n) / (theirPredicted * (1 + PRIOR / n)));
    }

    private static double clampScale(double scale) {
        return Double.isFinite(scale) ? Math.max(0.2, Math.min(3, scale)) : 1;
    }

    /** Maximum a posteriori by Newton's method, each round one observation's weight. */
    private void fit() {
        for (int iteration = 0; iteration < 8; iteration++) {
            double[] gradient = new double[PARAMETERS];
            double[][] hessian = new double[PARAMETERS][PARAMETERS];
            for (int i = 0; i < PARAMETERS; i++) {
                gradient[i] = -PRIOR * (theta[i] - prior[i]);
                hessian[i][i] = PRIOR;
            }
            for (double[][] round : rounds) {
                int n = round.length - 1;
                double label = round[n][0], weight = 1.0 / n;
                for (int s = 0; s < n; s++) {
                    double[] x = round[s];
                    double z = 0;
                    for (int i = 0; i < PARAMETERS; i++) z += theta[i] * x[i];
                    double p = 1 / (1 + Math.exp(-z));
                    for (int i = 0; i < PARAMETERS; i++) {
                        gradient[i] += weight * (label - p) * x[i];
                        for (int j = 0; j < PARAMETERS; j++) hessian[i][j] += weight * p * (1 - p) * x[i] * x[j];
                    }
                }
            }
            double[] step = solve(hessian, gradient);
            if (step == null) return;
            double largest = 0;
            for (int i = 0; i < PARAMETERS; i++) {
                theta[i] += step[i];
                largest = Math.max(largest, Math.abs(step[i]));
            }
            if (largest < 1e-6) return;
        }
    }

    /** Solves a symmetric positive-definite system by Gaussian elimination; null if singular. */
    private static double[] solve(double[][] matrix, double[] vector) {
        int n = vector.length;
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, a[i], 0, n);
            a[i][n] = vector[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            if (Math.abs(a[pivot][col]) < 1e-12) return null;
            double[] swap = a[col]; a[col] = a[pivot]; a[pivot] = swap;
            for (int row = col + 1; row < n; row++) {
                double factor = a[row][col] / a[col][col];
                for (int k = col; k <= n; k++) a[row][k] -= factor * a[col][k];
            }
        }
        double[] x = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double sum = a[row][n];
            for (int k = row + 1; k < n; k++) sum -= a[row][k] * x[k];
            x[row] = sum / a[row][row];
        }
        return x;
    }

    /** The fitted map, then the two damage scales, for carrying to the next battle against this opponent. */
    double[] exportParameters() {
        double[] out = java.util.Arrays.copyOf(theta, PARAMETERS + 2);
        out[PARAMETERS] = ourScale;
        out[PARAMETERS + 1] = theirScale;
        return out;
    }

    /** Centre the prior, and start, on the map earlier battles settled on. */
    void importParameters(double[] parameters) {
        if (parameters == null || parameters.length != PARAMETERS + 2) return;
        for (double p : parameters) if (!Double.isFinite(p)) return;
        System.arraycopy(parameters, 0, theta, 0, PARAMETERS);
        System.arraycopy(parameters, 0, prior, 0, PARAMETERS);
        ourScale = ourScalePrior = clampScale(parameters[PARAMETERS]);
        theirScale = theirScalePrior = clampScale(parameters[PARAMETERS + 1]);
    }

    static int exportedLength() { return PARAMETERS + 2; }

    public String diagnostics() {
        return String.format(java.util.Locale.ROOT,
                "roundWin map a=%.2f b=%.2f ourWall=%.2f theirWall=%.2f range=%.2f rounds=%d damageScale ours=%.2f theirs=%.2f",
                theta[0], theta[1], theta[2], theta[3], theta[4], rounds.size(), ourScale, theirScale);
    }
}
