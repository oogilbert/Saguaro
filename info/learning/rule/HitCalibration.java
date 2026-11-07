package oog.mega.saguaro.info.learning.rule;

/**
 * Maps a model's predicted hit chance to the hit rate actually realised, fitted on the
 * outcomes of the decisions taken: P(hit) = sigmoid(a + b * logit(q)).
 *
 * The models predict where a bullet goes, or where a mover goes, from the situation.
 * What is used is the chance of a hit at the position or angle we chose, and that is
 * not the same thing when the opponent learns from our choices: a gun that has learned
 * where our surfing goes fires at roughly twice the uniform rate where we choose to
 * stand, while a model of its aim from the situation predicts about the uniform rate
 * there. No setting of the aim model can express a dependence on our own choice. A
 * map fitted on realised outcomes can, and it is calibrated for exactly the positions
 * the decision-maker picks. It is monotone, so it never reverses a ranking.
 *
 * Fitted by maximum a posteriori logistic regression on the most recent outcomes,
 * refitted by a few Newton steps every {@link #REFIT} observations. It is parameterised
 * as a correction to the identity about a reference logit, x + c + d * (x - x0), with a
 * Gaussian prior pulling c and d to zero. Predictions cluster in a narrow band of
 * logits, so a and b of the plain form are nearly collinear, and a prior on them pulls
 * the fit sideways: synthetic data with a true (0.8, 1.0) came back (0.32, 0.78).
 */
public final class HitCalibration {
    private static final int CAPACITY = 2000;
    private static final int REFIT = 25;
    /** Prior precision on the correction (c, d), in units of observations. */
    private static final double PRIOR = 20;
    private static final double MIN_Q = 1e-4;

    private final double[] logit = new double[CAPACITY];
    private final double[] reference = new double[CAPACITY];
    private final boolean[] hit = new boolean[CAPACITY];
    private int size, next, sinceFit;
    /** Reference logit the correction is centred on: a 5% hit chance. */
    private static final double REFERENCE = Math.log(0.05 / 0.95);
    private double c = 0, d = 0;
    /**
     * The prior's centre: zero for an unknown opponent, and the correction earlier battles
     * against this one settled on otherwise, so each battle starts from that and moves only
     * as far as its own outcomes show.
     */
    private double priorC = 0, priorD = 0;

    public void clear() {
        size = 0; next = 0; sinceFit = 0;
        c = priorC; d = priorD;
    }

    /** Forget earlier battles: the prior returns to the identity. */
    public void clearPrior() {
        priorC = 0; priorD = 0;
        clear();
    }

    /** The fitted correction, for carrying to the next battle. */
    public double[] exportCorrection() { return new double[]{c, d}; }

    /** Centre the prior, and start, on a correction earlier battles learned. */
    public void importCorrection(double[] correction) {
        if (correction == null || correction.length != 2
                || !Double.isFinite(correction[0]) || !Double.isFinite(correction[1])) return;
        priorC = correction[0];
        priorD = correction[1];
        c = priorC;
        d = priorD;
    }

    public void observe(double predicted, boolean wasHit) {
        observe(predicted, wasHit, Double.NaN);
    }

    /**
     * An outcome, with the hit chance a uniform guess over the target's physical reach
     * would have had on this shot. The correction is centred on that chance rather than
     * on a fixed one: the reach is physics, known exactly, and only what the model adds
     * beyond it is learned. A model whose contrast proves worthless then falls back to
     * the physical baseline -- which still varies with range and bullet speed -- instead
     * of to a constant that would erase that dependence. NaN centres on the fixed 5%.
     */
    public void observe(double predicted, boolean wasHit, double uniform) {
        if (!Double.isFinite(predicted)) return;
        logit[next] = logit(predicted);
        reference[next] = Double.isFinite(uniform) ? logit(uniform) : REFERENCE;
        hit[next] = wasHit;
        next = (next + 1) % CAPACITY;
        size = Math.min(CAPACITY, size + 1);
        if (++sinceFit >= REFIT) { fit(); sinceFit = 0; }
    }

    /** The realised hit chance a raw prediction corresponds to. */
    public double calibrate(double predicted) {
        return calibrate(predicted, Double.NaN);
    }

    /** The realised hit chance of a raw prediction, about a shot's uniform-over-reach chance. */
    public double calibrate(double predicted, double uniform) {
        if (!Double.isFinite(predicted)) return predicted;
        double x = logit(predicted);
        double z = Double.isFinite(uniform) ? logit(uniform) : REFERENCE;
        return sigmoid(x + c + d * (x - z));
    }

    /** A correction (c, d) about the fixed reference, applied to a hit chance. */
    static double map(double predicted, double c, double d) {
        double x = logit(predicted);
        return sigmoid(x + c + d * (x - REFERENCE));
    }

    /** The map as sigmoid(a + b * logit(q)). */
    public double intercept() { return c - d * REFERENCE; }
    public double slope() { return 1 + d; }

    /**
     * Newton's method with a backtracking line search on the log-posterior. Plain Newton
     * steps diverge when the sigmoid saturates: against a target that never moves, every
     * chosen shot is predicted near certain while a third hit, the likelihood's curvature
     * there is nearly zero, and a full step -- hundreds of residuals over the prior's
     * curvature alone -- overshoots further each refit, sign alternating, until the map's
     * slope is far below zero and it ranks the likeliest shots least likely. Halving the
     * step until the posterior rises keeps every refit an improvement on the last.
     */
    private void fit() {
        for (int iteration = 0; iteration < 5; iteration++) {
            // Gradient and Hessian of the log-posterior in (c, d).
            double gc = -PRIOR * (c - priorC), gd = -PRIOR * (d - priorD);
            double hcc = PRIOR, hcd = 0, hdd = PRIOR;
            for (int i = 0; i < size; i++) {
                double x = logit[i], u = x - reference[i];
                double p = sigmoid(x + c + d * u);
                double r = (hit[i] ? 1 : 0) - p;
                double w = p * (1 - p);
                gc += r; gd += r * u;
                hcc += w; hcd += w * u; hdd += w * u * u;
            }
            double det = hcc * hdd - hcd * hcd;
            if (!(det > 1e-12)) return;
            double dc = (hdd * gc - hcd * gd) / det;
            double dd = (hcc * gd - hcd * gc) / det;
            double current = logPosterior(c, d), step = 1;
            while (step > 1e-6 && !(logPosterior(c + step * dc, d + step * dd) >= current)) step *= 0.5;
            if (step <= 1e-6) return;
            c += step * dc; d += step * dd;
            if (step * (Math.abs(dc) + Math.abs(dd)) < 1e-6) return;
        }
    }

    private double logPosterior(double c, double d) {
        double total = -0.5 * PRIOR * ((c - priorC) * (c - priorC) + (d - priorD) * (d - priorD));
        for (int i = 0; i < size; i++) {
            double z = logit[i] + c + d * (logit[i] - reference[i]);
            total += logSigmoid(hit[i] ? z : -z);
        }
        return total;
    }

    /** log(sigmoid(z)), without overflow at either end. */
    private static double logSigmoid(double z) {
        return z >= 0 ? -Math.log1p(Math.exp(-z)) : z - Math.log1p(Math.exp(z));
    }

    private static double logit(double q) {
        double c = Math.max(MIN_Q, Math.min(1 - MIN_Q, q));
        return Math.log(c / (1 - c));
    }

    private static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }
}
