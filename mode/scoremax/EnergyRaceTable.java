package oog.mega.saguaro.mode.scoremax;

import java.util.function.DoubleUnaryOperator;

import oog.mega.saguaro.info.state.RobocodeScoreUtil;
import robocode.Rules;

/**
 * The rest of a round, from any pair of energies, as the models predict it: the chance we
 * are the one left, and the bullet damage each side still deals.
 *
 * Running the branch simulator until a robot dies is far too costly, so the round's
 * remainder is solved as a Markov chain whose state is just the two energies. The next
 * event is one side firing, in proportion to each side's fire rate at its power; the shot
 * hits at that power's hit chance, taking the damage from its target and returning three
 * times its power to the shooter, or misses having cost the power. The opponent fires at
 * the power its power model gives for those energies. We fire at whichever power
 * maximises expected score from there on -- the same objective the planner maximises
 * within its horizon, so a plan is valued as though we keep choosing well afterwards.
 *
 * Every shot lowers the total energy -- a hit's damage is always more than twice its
 * power -- so the states are solved in order of total energy. A shot moves the energies
 * by far less than a cell, so the weight a state's successors put back on it is solved
 * exactly, and a few sweeps settle the small weight they put on its neighbours. Hit chances, fire rates and range are taken as they
 * are now for the rest of the round; position, ramming and bullet shadows are not
 * modelled. It replaces a fitted logistic in the energy lead, which saw where the energies
 * stood at the horizon but not the rates they were changing at: a power that hits too
 * rarely to pay for itself cost a single shot's energy there, and the whole round's in
 * fact.
 */
final class EnergyRaceTable {
    private static final double CELL = 4.0;
    private static final double MAX_ENERGY = 120.0;
    private static final int N = (int) Math.ceil(MAX_ENERGY / CELL) + 1;
    private static final int SWEEPS = 4;
    /** The powers we choose among at each state. */
    private static final double[] POWERS = {0.2, 0.4, 0.7, 1.0, 1.4, 1.9, 2.5, 3.0};

    /** The rest of the round from one pair of energies. */
    static final class Outcome {
        final double winProbability, ourDamage, enemyDamage;
        Outcome(double winProbability, double ourDamage, double enemyDamage) {
            this.winProbability = winProbability;
            this.ourDamage = ourDamage;
            this.enemyDamage = enemyDamage;
        }
    }

    /** What the table is built from. */
    interface Inputs {
        /** The opponent's power at these energies, theirs first; NaN if it cannot fire. */
        double enemyPower(double enemyEnergy, double ourEnergy);
        /** Our hit chance at a power. */
        double ourHit(double power);
        /** The opponent's hit chance at a power. */
        double enemyHit(double power);
        /** Our share of the battle's score so far, which the objective weighs points by. */
        double share();
    }

    private final double[][] win = new double[N][N];
    private final double[][] ours = new double[N][N];
    private final double[][] theirs = new double[N][N];
    private final double[][] enemyPower = new double[N][N];
    /** DIAGNOSTIC ONLY: the power the table chooses for us at each state. */
    private final double[][] bestPower = new double[N][N];
    private boolean built;
    /** DIAGNOSTIC ONLY: the last full pass's view of a fresh round. */
    static String lastPass = "race none";

    boolean isBuilt() { return built; }

    /** Solve the whole table at once, from scratch: for tests. */
    void build(Inputs inputs) {
        reset();
        for (int pass = 0; pass < SWEEPS; pass++) {
            do { advance(inputs, N * N); } while (cursorTotal != 1);
        }
    }

    // The table is solved incrementally: a fixed number of states a tick, in order of total
    // energy, each pass beginning from the inputs as they then are and from the values the
    // last pass left. A whole solve at once overran the turn and skipped it, every rebuild;
    // the rates it is built from change slowly, so passes that follow them a few ticks
    // apart stay converged.
    private int cursorTotal = 1, cursorI;
    private double[] passOurHit;
    private double passShare;
    private DoubleUnaryOperator passEnemyHit;

    /** Start from the energy share and no further damage, before any pass. */
    void reset() {
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                win[i][j] = i + j == 0 ? 0.5 : i / (double) (i + j);
                ours[i][j] = 0;
                theirs[i][j] = 0;
            }
        }
        cursorTotal = 1;
        cursorI = 0;
        passOurHit = null;
        built = true;
    }

    /** Solve the next states of the current pass, starting a pass from the present inputs when one ends. */
    void advance(Inputs inputs, int states) {
        if (!built) reset();
        for (int done = 0; done < states; done++) {
            if (passOurHit == null) startPass(inputs);
            int i = Math.max(0, cursorTotal - (N - 1)) + cursorI;
            int j = cursorTotal - i;
            double power = inputs.enemyPower(j * CELL, i * CELL);
            enemyPower[i][j] = Double.isFinite(power) ? Math.min(power, j * CELL) : 0.0;
            solve(i, j, passOurHit, passEnemyHit, passShare);
            cursorI++;
            if (Math.max(0, cursorTotal - (N - 1)) + cursorI > Math.min(N - 1, cursorTotal)) {
                cursorI = 0;
                cursorTotal++;
                if (cursorTotal > 2 * (N - 1)) {
                    cursorTotal = 1;
                    // DIAGNOSTIC ONLY: what a full pass said about a fresh round.
                    double[] start = lookup(100, 100);
                    lastPass = String.format(java.util.Locale.ROOT,
                            "race fresh round win=%.3f ourDamage=%.1f theirDamage=%.1f share=%.2f enemyPower=%.2f ourHit=%s",
                            start[0], start[1], start[2], passShare, enemyPower[N * 100 / 120][N * 100 / 120],
                            java.util.Arrays.toString(java.util.Arrays.stream(passOurHit)
                                    .map(h -> Math.round(h * 1000) / 1000.0).toArray()));
                    passOurHit = null;
                    return;
                }
            }
        }
    }

    private void startPass(Inputs inputs) {
        passOurHit = new double[POWERS.length];
        for (int k = 0; k < POWERS.length; k++) passOurHit[k] = clamp01(inputs.ourHit(POWERS[k]));
        passShare = Math.max(0.0, Math.min(1.0, inputs.share()));
        java.util.Map<Long, Double> enemyHits = new java.util.HashMap<>();
        passEnemyHit = p -> enemyHits.computeIfAbsent(Math.round(p * 20),
                key -> clamp01(inputs.enemyHit(key / 20.0)));
    }

    /**
     * Expected (win, our damage, their damage) over a successor, interpolated, with the
     * share of its weight that falls back on the state (i, j) itself kept apart: a shot
     * moves the energies by far less than a cell, so most of a state's successors land
     * back in it, and that self-reference is solved exactly rather than iterated.
     */
    private double accumulate(double e1, double e2, double probability, int i, int j, double[] sum) {
        if (e1 <= 0 || e2 <= 0) {
            sum[0] += probability * (e1 <= 0 && e2 <= 0 ? 0.5 : e1 <= 0 ? 0 : 1);
            return 0;
        }
        double x = Math.min(N - 1, e1 / CELL), y = Math.min(N - 1, e2 / CELL);
        int i0 = (int) Math.floor(x), j0 = (int) Math.floor(y);
        int i1 = Math.min(N - 1, i0 + 1), j1 = Math.min(N - 1, j0 + 1);
        double fx = x - i0, fy = y - j0;
        int[] ci = {i0, i1, i0, i1}, cj = {j0, j0, j1, j1};
        double[] cw = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};
        double self = 0;
        for (int k = 0; k < 4; k++) {
            double w = probability * cw[k];
            if (w == 0) continue;
            if (ci[k] == i && cj[k] == j) { self += w; continue; }
            sum[0] += w * win[ci[k]][cj[k]];
            sum[1] += w * ours[ci[k]][cj[k]];
            sum[2] += w * theirs[ci[k]][cj[k]];
        }
        return self;
    }

    private void solve(int i, int j, double[] ourHit, DoubleUnaryOperator enemyHit, double share) {
        if (i == 0 || j == 0) {
            win[i][j] = i == 0 && j == 0 ? 0.5 : i == 0 ? 0.0 : 1.0;
            ours[i][j] = 0;
            theirs[i][j] = 0;
            return;
        }
        double e1 = i * CELL, e2 = j * CELL;
        double p2 = enemyPower[i][j];
        boolean enemyFires = p2 >= 0.1;
        double rate2 = enemyFires ? 1.0 / EventScheduleBuilder.continuationCadenceTicks(p2) : 0.0;
        // The opponent's shot, the same whatever we choose: its successors, its damage to
        // us, and the weight that returns to this state.
        double[] enemySum = new double[3];
        double enemySelf = 0;
        if (enemyFires) {
            double h2 = enemyHit.applyAsDouble(p2), damage = Rules.getBulletDamage(p2);
            enemySelf += accumulate(e1 - damage, e2 - p2 + 3 * p2, h2, i, j, enemySum);
            enemySelf += accumulate(e1, e2 - p2, 1 - h2, i, j, enemySum);
            enemySum[2] += h2 * Math.min(damage, e1);
        }
        double bestValue = Double.NEGATIVE_INFINITY, bestWin = 0, bestOurs = 0, bestTheirs = 0;
        boolean any = false;
        double[] ourSum = new double[3];
        for (int k = 0; k < POWERS.length; k++) {
            double p1 = POWERS[k];
            if (p1 > e1) break;
            any = true;
            double rate1 = 1.0 / EventScheduleBuilder.continuationCadenceTicks(p1);
            double share1 = rate1 / (rate1 + rate2);
            double h1 = ourHit[k], damage = Rules.getBulletDamage(p1);
            java.util.Arrays.fill(ourSum, 0.0);
            double ourSelf = accumulate(e1 - p1 + 3 * p1, e2 - damage, h1, i, j, ourSum);
            ourSelf += accumulate(e1 - p1, e2, 1 - h1, i, j, ourSum);
            ourSum[1] += h1 * Math.min(damage, e2);
            double self = share1 * ourSelf + (1 - share1) * enemySelf;
            if (!(self < 1 - 1e-9)) continue;
            double w = (share1 * ourSum[0] + (1 - share1) * enemySum[0]) / (1 - self);
            double o = (share1 * ourSum[1] + (1 - share1) * enemySum[1]) / (1 - self);
            double t = (share1 * ourSum[2] + (1 - share1) * enemySum[2]) / (1 - self);
            double value = (1 - share) * (o + RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1 * w)
                    - share * (t + RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1 * (1 - w));
            if (value > bestValue) {
                bestValue = value; bestWin = w; bestOurs = o; bestTheirs = t;
                bestPower[i][j] = p1;
            }
        }
        if (!any || bestValue == Double.NEGATIVE_INFINITY) {
            // We cannot fire: the opponent's shots decide it, or neither side can and the
            // energies stand.
            if (enemyFires && enemySelf < 1 - 1e-9) {
                bestWin = enemySum[0] / (1 - enemySelf);
                bestOurs = enemySum[1] / (1 - enemySelf);
                bestTheirs = enemySum[2] / (1 - enemySelf);
            } else {
                bestWin = e1 > e2 ? 1 : e1 < e2 ? 0 : 0.5;
                bestOurs = 0;
                bestTheirs = 0;
            }
        }
        win[i][j] = bestWin;
        ours[i][j] = bestOurs;
        theirs[i][j] = bestTheirs;
    }

    /** DIAGNOSTIC ONLY: the power the table would fire at the nearest state. */
    double chosenPower(double ourEnergy, double enemyEnergy) {
        int i = (int) Math.max(0, Math.min(N - 1, Math.round(ourEnergy / CELL)));
        int j = (int) Math.max(0, Math.min(N - 1, Math.round(enemyEnergy / CELL)));
        return bestPower[i][j];
    }

    /** The rest of the round from these energies, interpolated. */
    Outcome outcome(double ourEnergy, double enemyEnergy) {
        double[] v = lookup(ourEnergy, enemyEnergy);
        return new Outcome(v[0], v[1], v[2]);
    }

    /** Bilinear interpolation of (win, our damage, their damage) at energies; zero energy is decided. */
    private double[] lookup(double e1, double e2) {
        if (e1 <= 0 && e2 <= 0) return new double[]{0.5, 0, 0};
        if (e1 <= 0) return new double[]{0, 0, 0};
        if (e2 <= 0) return new double[]{1, 0, 0};
        double x = Math.min(N - 1, e1 / CELL), y = Math.min(N - 1, e2 / CELL);
        int i0 = (int) Math.floor(x), j0 = (int) Math.floor(y);
        int i1 = Math.min(N - 1, i0 + 1), j1 = Math.min(N - 1, j0 + 1);
        double fx = x - i0, fy = y - j0;
        double[] r = new double[3];
        double[][][] tables = {win, ours, theirs};
        for (int k = 0; k < 3; k++) {
            double[][] t = tables[k];
            r[k] = (1 - fx) * (1 - fy) * t[i0][j0] + fx * (1 - fy) * t[i1][j0]
                    + (1 - fx) * fy * t[i0][j1] + fx * fy * t[i1][j1];
        }
        return r;
    }

    private static double clamp01(double p) {
        return Double.isFinite(p) ? Math.max(0.0, Math.min(1.0, p)) : 0.0;
    }
}
