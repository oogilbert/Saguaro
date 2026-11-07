package oog.mega.saguaro.info.learning.rule;

import java.util.List;

/**
 * A learned model of how an opponent turns its observations into a rating, updated by
 * gradient on every outcome. Targeting and movement use this one class, because both
 * are the same problem pointed in opposite directions: targeting models the opponent's
 * movement (its observations are our hits on it and its crossings of our waves),
 * movement models the opponent's gun (its observations are our crossings of its waves).
 *
 * The rating at a guess factor is a sum over logged observations of a kernel at each
 * one's guess factor, weighted by how near its situation is and by a credit for its
 * kind:
 *
 *   D(g) = sum_n credit(kind_n) / (1 + s * dist(n)) / (1 + ((g - g_n) / h)^2)
 *
 * The credits carry no sign constraint: a surfer avoids where it was hit, a bot that
 * ignores our fire returns to where it has been, a gun that logs every wave aims where
 * we have been, a fixed-rule gun does not aim where it missed us. Each is a direction of
 * the credit vector, so one model covers them all and nothing selects between models.
 *
 * The rating, standardised over the points compared, becomes a probability
 *
 *   p(g) = (1 - floor) * softmax(sigma * beta * z(g)) + floor / n
 *
 * with sigma +1 for a gun and -1 for a mover, beta the learned confidence, and the floor
 * the learned share of behaviour the rating does not explain. Every outcome is the
 * log-probability of a weighted set of these points.
 *
 * Distance is over the context features plus two recency dimensions, measured in how
 * many revealed bullets, and how many unhit waves, have arrived since an observation.
 */
public final class RuleModel {
    private static final double RANK_SCALE = 10.0;
    private static final int NEIGHBOURS = 200;
    public static final int BINS = 47;
    private static final int MAX_POINTS = 64;
    private static final double LEARNING_RATE = 0.04;
    private static final double SCALE_DECAY = 0.995;

    private final int features;
    private final int dimensions;
    private final int hitRecency;
    private final int sharpnessSlot, bandwidthSlot, hitCreditSlot, passCreditSlot,
            collisionCreditSlot, betaSlot, floorSlot, parameterCount;

    private final double[] priorWeights;
    private final double priorKernel, priorHit, priorPass, priorCollision;

    private final double[] theta;
    private final double[] scaleOf;
    private final double[] gradient;
    private final double[] grid = new double[BINS];
    private final double[] near = new double[NEIGHBOURS];
    private final int[] which = new int[NEIGHBOURS];
    private final double[] closeness = new double[NEIGHBOURS];
    private final double[] weightOf = new double[NEIGHBOURS];
    private final double[] points = new double[MAX_POINTS];
    private final double[] rating = new double[MAX_POINTS];
    private final double[] standard = new double[MAX_POINTS];
    private final double[] probability = new double[MAX_POINTS];
    private final double[] given = new double[MAX_POINTS];
    private final double[] member = new double[MAX_POINTS];
    private final long[] latest = new long[2];
    /** Revealed bullets assumed to have arrived after the newest one logged. */
    private int hitShift;
    /**
     * Dimension weights as multipliers, refreshed at the start of each read. The
     * distance is evaluated for every logged observation on every read, and taking
     * the exponential of each weight there cost 24 exponentials per observation: half
     * a millisecond per distribution at a full log.
     */
    private final double[] scale;
    private int updates;
    /**
     * The step size relative to a fresh opponent's: 1/sqrt(1 + battles) after that many
     * earlier battles' learning, so the parameters settle as evidence accumulates instead
     * of wandering at a fresh opponent's pace -- which drove the floor, rewarded directly by
     * sharper fits, towards overconfidence over three battles against DrussGT. It never
     * reaches zero, so a changed opponent is still followed.
     */
    private double stepScale = 1.0;
    /** Prediction score this battle: summed log of the outcome's probability over a uniform guess's. */
    private double liftSum;
    private int liftCount;

    /**
     * @param priorWeights starting weight of each context feature
     * @param kernel       starting kernel width in guess-factor units
     */
    public RuleModel(double[] priorWeights, double kernel, double hit, double pass, double collision) {
        this.features = priorWeights.length;
        this.dimensions = features + 2;
        this.hitRecency = features;
        this.sharpnessSlot = dimensions;
        this.bandwidthSlot = dimensions + 1;
        this.hitCreditSlot = dimensions + 2;
        this.passCreditSlot = dimensions + 3;
        this.collisionCreditSlot = dimensions + 4;
        this.betaSlot = dimensions + 5;
        this.floorSlot = dimensions + 6;
        this.parameterCount = dimensions + 7;
        this.priorWeights = priorWeights.clone();
        this.priorKernel = kernel;
        this.priorHit = hit;
        this.priorPass = pass;
        this.priorCollision = collision;
        this.theta = new double[parameterCount];
        this.scaleOf = new double[parameterCount];
        this.gradient = new double[parameterCount];
        this.scale = new double[dimensions];
        this.scaledSituation = new double[features];
        for (int i = 0; i < BINS; i++) grid[i] = -1.0 + 2.0 * i / (BINS - 1);
        reset();
    }

    public void reset() {
        for (int i = 0; i < features; i++) theta[i] = Math.log(Math.max(0.05, priorWeights[i]));
        theta[hitRecency] = Math.log(0.5);
        theta[hitRecency + 1] = Math.log(0.5);
        theta[sharpnessSlot] = 0;
        theta[bandwidthSlot] = Math.log(priorKernel);
        theta[hitCreditSlot] = priorHit;
        theta[passCreditSlot] = priorPass;
        theta[collisionCreditSlot] = priorCollision;
        theta[betaSlot] = Math.log(4.0);
        theta[floorSlot] = Math.log(0.05 / 0.95);
        java.util.Arrays.fill(scaleOf, 0.0);
        clamp();
        updates = 0;
        stepScale = 1.0;
        liftSum = 0;
        liftCount = 0;
    }

    /** How many earlier battles' learning the parameters carry. */
    public void setExperience(int battles) {
        stepScale = 1.0 / Math.sqrt(1.0 + Math.max(0, battles));
    }

    /**
     * Mean log-ratio, this battle, of the probability the model gave each observed outcome
     * before learning from it to the probability a uniform guess over the same options
     * gave it: zero for a model that knows nothing, positive for one that predicts.
     */
    public double meanLift() { return liftCount == 0 ? 0 : liftSum / liftCount; }

    public int liftCount() { return liftCount; }

    public int featureCount() { return features; }
    /** Live parameter vector, for gradient checks. */
    double[] parameters() { return theta; }
    /** Gradient of the last fitted outcome, for gradient checks. */
    double[] lastGradient() { return gradient; }

    public double sharpness() { return Math.exp(theta[sharpnessSlot]); }
    public double bandwidth() { return Math.exp(theta[bandwidthSlot]); }
    public double choiceSharpness() { return Math.exp(theta[betaSlot]); }
    public double floor() { return 1.0 / (1.0 + Math.exp(-theta[floorSlot])); }
    public int updateCount() { return updates; }

    /** The learned parameters, for carrying to the next battle against this opponent. */
    public double[] exportParameters() { return theta.clone(); }

    /**
     * Continue from parameters learned in earlier battles: training goes on from them as
     * though the battles were one, with fresh step-size statistics.
     */
    public void importParameters(double[] parameters) {
        if (parameters == null || parameters.length != theta.length) return;
        for (double p : parameters) if (!Double.isFinite(p)) return;
        System.arraycopy(parameters, 0, theta, 0, theta.length);
        java.util.Arrays.fill(scaleOf, 0.0);
        clamp();
    }
    public static double gridPoint(int i) { return -1.0 + 2.0 * i / (BINS - 1); }

    private int creditSlot(RuleLog.Kind kind) {
        switch (kind) {
            case HIT: return hitCreditSlot;
            case PASSED: return passCreditSlot;
            default: return collisionCreditSlot;
        }
    }

    private double delta(int i, double[] situation, RuleLog.Entry entry) {
        if (i < hitRecency) return (situation[i] - entry.features()[i]) * scale[i];
        int sequence = RuleLog.sequence(entry.kind());
        if (i != hitRecency + sequence) return 0;
        double newer = latest[sequence] - entry.ordinal() + (sequence == 0 ? hitShift : 0);
        return newer / RANK_SCALE * scale[i];
    }

    private double squareDistance(double[] situation, RuleLog.Entry entry) {
        double total = 0;
        double[] f = entry.features();
        for (int i = 0; i < hitRecency; i++) {
            double d = (situation[i] - f[i]) * scale[i];
            total += d * d;
        }
        double d = delta(hitRecency + RuleLog.sequence(entry.kind()), situation, entry);
        return total + d * d;
    }

    /** Nearest observations known strictly before {@code asOf}, with what each carries. */
    private int gather(double[] situation, List<RuleLog.Entry> log, long asOf) {
        for (int i = 0; i < dimensions; i++) scale[i] = Math.exp(theta[i]);
        if (log instanceof RuleLog.View view) return gatherFlat(situation, view.owner(), log, asOf);
        latest[0] = -1;
        latest[1] = -1;
        for (int i = 0; i < log.size(); i++) {
            RuleLog.Entry entry = log.get(i);
            if (entry.time() >= asOf) continue;
            int sequence = RuleLog.sequence(entry.kind());
            latest[sequence] = Math.max(latest[sequence], entry.ordinal());
        }
        // Every visible distance, then the nearest NEIGHBOURS by selection: linear in
        // the log, where keeping a sorted top list by insertion shifted up to that many
        // entries per insertion. Nothing downstream needs the neighbours in order.
        if (allDistances.length < log.size()) {
            allDistances = new double[log.size()];
            allIndices = new int[log.size()];
        }
        int visible = 0;
        for (int i = 0; i < log.size(); i++) {
            RuleLog.Entry entry = log.get(i);
            if (entry.time() >= asOf) continue;
            allDistances[visible] = squareDistance(situation, entry);
            allIndices[visible] = i;
            visible++;
        }
        if (visible > NEIGHBOURS) select(visible, NEIGHBOURS);
        int found = Math.min(visible, NEIGHBOURS);
        for (int n = 0; n < found; n++) {
            near[n] = allDistances[n];
            which[n] = allIndices[n];
        }
        double s = sharpness();
        for (int n = 0; n < found; n++) {
            closeness[n] = 1.0 / (1.0 + s * near[n]);
            weightOf[n] = theta[creditSlot(log.get(which[n]).kind())] * closeness[n];
        }
        return found;
    }

    /** The same scan over the log's flat layout: identical results, no per-entry indirection. */
    private int gatherFlat(double[] situation, RuleLog owner, List<RuleLog.Entry> log, long asOf) {
        int size = log.size();
        int stride = owner.featureCount();
        scaleLog(owner, size, stride);
        double[] features = scaledFeatures;
        for (int k = 0; k < hitRecency; k++) scaledSituation[k] = situation[k] * scale[k];
        long[] times = owner.flatTimes, ordinals = owner.flatOrdinals;
        byte[] sequences = owner.flatSequences;
        latest[0] = -1;
        latest[1] = -1;
        for (int i = 0; i < size; i++) {
            if (times[i] >= asOf) continue;
            int sequence = sequences[i];
            if (ordinals[i] > latest[sequence]) latest[sequence] = ordinals[i];
        }
        if (allDistances.length < size) {
            allDistances = new double[size];
            allIndices = new int[size];
        }
        double hitScale = scale[hitRecency] / RANK_SCALE, passScale = scale[hitRecency + 1] / RANK_SCALE;
        int visible = 0;
        for (int i = 0; i < size; i++) {
            if (times[i] >= asOf) continue;
            int base = i * stride;
            double total = 0;
            for (int k = 0; k < hitRecency; k++) {
                double d = scaledSituation[k] - features[base + k];
                total += d * d;
            }
            int sequence = sequences[i];
            double d = sequence == 0
                    ? (latest[0] - ordinals[i] + hitShift) * hitScale
                    : (latest[1] - ordinals[i]) * passScale;
            allDistances[visible] = total + d * d;
            allIndices[visible] = i;
            visible++;
        }
        if (visible > NEIGHBOURS) select(visible, NEIGHBOURS);
        int found = Math.min(visible, NEIGHBOURS);
        for (int n = 0; n < found; n++) {
            near[n] = allDistances[n];
            which[n] = allIndices[n];
        }
        double s = sharpness();
        for (int n = 0; n < found; n++) {
            closeness[n] = 1.0 / (1.0 + s * near[n]);
            weightOf[n] = theta[creditSlot(log.get(which[n]).kind())] * closeness[n];
        }
        return found;
    }

    private double[] allDistances = new double[0];
    private int[] allIndices = new int[0];
    /**
     * The log's features multiplied by the dimension weights, kept until the log or the
     * weights next change: reads far outnumber either, and the multiply was a third of
     * each distance.
     */
    private double[] scaledFeatures = new double[0];
    private final double[] scaledSituation;
    private RuleLog scaledOwner;
    private long scaledLogVersion = -1, scaledThetaVersion = -1;
    /** Changes with every change of the parameters. */
    private long thetaVersion;

    private void scaleLog(RuleLog owner, int size, int stride) {
        if (owner == scaledOwner && owner.version() == scaledLogVersion && thetaVersion == scaledThetaVersion) return;
        if (scaledFeatures.length < owner.flatFeatures.length) scaledFeatures = new double[owner.flatFeatures.length];
        double[] features = owner.flatFeatures;
        for (int i = 0; i < size; i++) {
            int base = i * stride;
            for (int k = 0; k < hitRecency; k++) scaledFeatures[base + k] = features[base + k] * scale[k];
        }
        scaledOwner = owner;
        scaledLogVersion = owner.version();
        scaledThetaVersion = thetaVersion;
    }

    /** Rearrange the first {@code count} distances so the {@code k} smallest come first. */
    private void select(int count, int k) {
        int lower = 0, upper = count - 1;
        while (lower < upper) {
            double pivot = allDistances[(lower + upper) >>> 1];
            int i = lower, j = upper;
            while (i <= j) {
                while (allDistances[i] < pivot) i++;
                while (allDistances[j] > pivot) j--;
                if (i <= j) {
                    double d = allDistances[i]; allDistances[i] = allDistances[j]; allDistances[j] = d;
                    int x = allIndices[i]; allIndices[i] = allIndices[j]; allIndices[j] = x;
                    i++; j--;
                }
            }
            if (k - 1 <= j) upper = j;
            else if (k - 1 >= i) lower = i;
            else return;
        }
    }

    /** The opponent's rating at the given points, as of a battle-clock time. */
    public void rating(double[] situation, List<RuleLog.Entry> log, double[] at, int count,
                       double[] out, long asOf) {
        rating(situation, log, at, count, out, asOf, 0);
    }

    /**
     * The rating once {@code shift} more revealed bullets have arrived: every logged one
     * moves that many places down the recency order, as it will once a predicted hit
     * lands.
     */
    public void rating(double[] situation, List<RuleLog.Entry> log, double[] at, int count,
                       double[] out, long asOf, int shift) {
        java.util.Arrays.fill(out, 0, count, 0.0);
        if (log.isEmpty()) return;
        hitShift = shift;
        int found;
        try {
            found = gather(situation, log, asOf);
        } finally {
            hitShift = 0;
        }
        double h = bandwidth();
        for (int n = 0; n < found; n++) {
            double w = weightOf[n];
            if (w == 0) continue;
            double centre = log.get(which[n]).guessFactor();
            for (int g = 0; g < count; g++) {
                double u = (at[g] - centre) / h;
                out[g] += w / (1.0 + u * u);
            }
        }
    }

    /**
     * The probability of each point, in exactly the form the likelihood is fitted in:
     * standardised over these points, softmax at sigma * beta, mixed with the floor.
     */
    public void probabilities(double[] situation, List<RuleLog.Entry> log, double[] at, int count,
                              double sigma, double[] out, long asOf) {
        rating(situation, log, at, count, out, asOf);
        toProbabilities(out, count, sigma);
    }

    /**
     * A rating over points made a probability in place, in exactly the form the
     * likelihood is fitted in: standardised over these points, softmax at sigma * beta,
     * mixed with the floor.
     */
    public void toProbabilities(double[] out, int count, double sigma) {
        double mean = 0;
        for (int g = 0; g < count; g++) mean += out[g];
        mean /= count;
        double variance = 0;
        for (int g = 0; g < count; g++) variance += (out[g] - mean) * (out[g] - mean);
        double spread = Math.sqrt(variance / count);
        if (!(spread > 1e-9)) {
            java.util.Arrays.fill(out, 0, count, 1.0 / count);
            return;
        }
        double beta = choiceSharpness();
        double highest = Double.NEGATIVE_INFINITY;
        for (int g = 0; g < count; g++) {
            out[g] = sigma * beta * (out[g] - mean) / spread;
            highest = Math.max(highest, out[g]);
        }
        double total = 0;
        for (int g = 0; g < count; g++) {
            out[g] = Math.exp(out[g] - highest);
            total += out[g];
        }
        double floor = floor();
        for (int g = 0; g < count; g++) out[g] = (1 - floor) * out[g] / total + floor / count;
    }

    /**
     * A gun's outcome: did the bullet arrive within our body interval. Read as of the
     * moment it was fired, before the outcome is logged.
     */
    public void update(double[] situation, List<RuleLog.Entry> log, double guessFactor,
                       double bodyHalf, boolean passed, long asOf) {
        if (!Double.isFinite(guessFactor)) return;
        System.arraycopy(grid, 0, points, 0, BINS);
        double spacing = 2.0 / (BINS - 1);
        double half = Math.max(bodyHalf, 0.5 * spacing);
        for (int g = 0; g < BINS; g++) {
            double overlap = Math.min(points[g] + 0.5 * spacing, guessFactor + half)
                    - Math.max(points[g] - 0.5 * spacing, guessFactor - half);
            double inside = Math.max(0, Math.min(1, overlap / spacing));
            member[g] = passed ? 1 - inside : inside;
        }
        fit(situation, log, BINS, 1.0, asOf);
    }

    /**
     * A mover's outcome: it crossed within a body width of some options, out of those
     * it could physically reach, each weighted by how much of its cell was covered.
     */
    public void updateChoice(double[] situation, List<RuleLog.Entry> log,
                             double[] options, double[] covered, int count, long asOf) {
        int n = Math.min(count, MAX_POINTS);
        System.arraycopy(options, 0, points, 0, n);
        System.arraycopy(covered, 0, member, 0, n);
        fit(situation, log, n, -1.0, asOf);
    }

    /**
     * Step on the log-probability of the weighted outcome set over {@link #points}. With
     * u = sigma * beta * z, the softmax gradient is E[du | set] - E[du], scaled by the
     * share of the outcome the rating accounts for once mixed with the floor.
     */
    private void fit(double[] situation, List<RuleLog.Entry> log, int count,
                     double sigma, long asOf) {
        if (log.isEmpty() || count < 2) return;
        int found = gather(situation, log, asOf);
        if (found == 0) return;
        double s = sharpness(), h = bandwidth();
        java.util.Arrays.fill(rating, 0, count, 0.0);
        for (int n = 0; n < found; n++) {
            double w = weightOf[n];
            double at = log.get(which[n]).guessFactor();
            for (int g = 0; g < count; g++) {
                double u = (points[g] - at) / h;
                rating[g] += w / (1.0 + u * u);
            }
        }
        double mean = 0;
        for (int g = 0; g < count; g++) mean += rating[g];
        mean /= count;
        double variance = 0;
        for (int g = 0; g < count; g++) variance += (rating[g] - mean) * (rating[g] - mean);
        double spread = Math.sqrt(variance / count);
        if (!(spread > 1e-9)) {
            // A rating with no contrast predicts the uniform guess: it scores nothing.
            liftCount++;
            return;
        }
        double beta = choiceSharpness();
        double highest = Double.NEGATIVE_INFINITY;
        for (int g = 0; g < count; g++) {
            standard[g] = (rating[g] - mean) / spread;
            highest = Math.max(highest, sigma * beta * standard[g]);
        }
        double total = 0;
        for (int g = 0; g < count; g++) {
            probability[g] = Math.exp(sigma * beta * standard[g] - highest);
            total += probability[g];
        }
        double inside = 0, covered = 0;
        for (int g = 0; g < count; g++) {
            probability[g] /= total;
            given[g] = member[g] * probability[g];
            inside += given[g];
            covered += member[g];
        }
        double floor = floor();
        double uniform = covered / count;
        double outcome = (1 - floor) * inside + floor * uniform;
        if (!(outcome > 1e-12)) return;
        if (uniform > 1e-12) {
            liftSum += Math.log(outcome / uniform);
            liftCount++;
        }
        double share = (1 - floor) * inside / outcome;
        double expectedZ = 0, givenZ = 0;
        for (int g = 0; g < count; g++) {
            given[g] = inside > 1e-12 ? given[g] / inside : 0;
            expectedZ += probability[g] * standard[g];
            givenZ += given[g] * standard[g];
        }
        if (!(inside > 1e-12)) givenZ = expectedZ;
        double gap = givenZ - expectedZ;
        double coefficient = sigma * beta / spread;

        java.util.Arrays.fill(gradient, 0.0);
        for (int n = 0; n < found; n++) {
            RuleLog.Entry entry = log.get(which[n]);
            double underGiven = 0, underPrediction = 0, againstZ = 0;
            double bandGiven = 0, bandPrediction = 0, bandZ = 0;
            for (int g = 0; g < count; g++) {
                double u = (points[g] - entry.guessFactor()) / h;
                double k = 1.0 / (1.0 + u * u);
                double kb = 2.0 * u * u * k * k;
                underGiven += given[g] * k;
                underPrediction += probability[g] * k;
                againstZ += standard[g] * k;
                bandGiven += given[g] * kb;
                bandPrediction += probability[g] * kb;
                bandZ += standard[g] * kb;
            }
            double effect = coefficient * (underGiven - underPrediction - gap * againstZ / count);
            double bandEffect = coefficient * (bandGiven - bandPrediction - gap * bandZ / count);
            double w = weightOf[n];
            double closenessRate = -closeness[n] * s;
            for (int i = 0; i < dimensions; i++) {
                double d = delta(i, situation, entry);
                if (d == 0) continue;
                gradient[i] += w * closenessRate * 2.0 * d * d * effect;
            }
            gradient[sharpnessSlot] += w * closenessRate * near[n] * effect;
            gradient[creditSlot(entry.kind())] += closeness[n] * effect;
            gradient[bandwidthSlot] += w * bandEffect;
        }
        gradient[betaSlot] = sigma * beta * gap;
        for (int p = 0; p < parameterCount; p++) gradient[p] *= share;
        gradient[floorSlot] = (uniform - inside) / outcome * floor * (1 - floor);

        for (int p = 0; p < parameterCount; p++) {
            double g = gradient[p];
            if (!Double.isFinite(g)) continue;
            scaleOf[p] = SCALE_DECAY * scaleOf[p] + (1 - SCALE_DECAY) * g * g;
            double move = stepScale * LEARNING_RATE * g / (Math.sqrt(scaleOf[p]) + 1e-9);
            theta[p] += Math.max(-0.1, Math.min(0.1, move));
        }
        clamp();
        updates++;
    }

    /** Pin the weights' geometric mean at one and the credits at unit length. */
    private void normalise() {
        double meanLog = 0;
        for (int i = 0; i < dimensions; i++) meanLog += theta[i];
        meanLog /= dimensions;
        if (Double.isFinite(meanLog)) {
            for (int i = 0; i < dimensions; i++) theta[i] -= meanLog;
            theta[sharpnessSlot] += 2 * meanLog;
        }
        double length = Math.sqrt(theta[hitCreditSlot] * theta[hitCreditSlot]
                + theta[passCreditSlot] * theta[passCreditSlot]
                + theta[collisionCreditSlot] * theta[collisionCreditSlot]);
        if (length > 1e-9 && Double.isFinite(length)) {
            theta[hitCreditSlot] /= length;
            theta[passCreditSlot] /= length;
            theta[collisionCreditSlot] /= length;
        }
    }

    private void clamp() {
        thetaVersion++;
        normalise();
        for (int i = 0; i < dimensions; i++) {
            theta[i] = Math.max(Math.log(0.05), Math.min(Math.log(20.0), theta[i]));
        }
        theta[sharpnessSlot] = Math.max(Math.log(0.05), Math.min(Math.log(20.0), theta[sharpnessSlot]));
        theta[bandwidthSlot] = Math.max(Math.log(0.02), Math.min(Math.log(0.40), theta[bandwidthSlot]));
        theta[betaSlot] = Math.max(Math.log(0.05), Math.min(Math.log(40.0), theta[betaSlot]));
        theta[floorSlot] = Math.max(Math.log(0.001 / 0.999), Math.min(0.0, theta[floorSlot]));
    }

    /** The learned weight of each dimension, as the multiplier on its distance. */
    public String weightDiagnostics() {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) text.append(' ');
            text.append(String.format(java.util.Locale.ROOT, "%.2f", Math.exp(theta[i])));
        }
        return text.append(']').toString();
    }

    public String diagnostics() {
        return String.format(java.util.Locale.ROOT,
                "sharp=%.2f band=%.3f hit=%+.2f pass=%+.2f coll=%+.2f beta=%.2f floor=%.3f n=%d",
                sharpness(), bandwidth(), theta[hitCreditSlot], theta[passCreditSlot],
                theta[collisionCreditSlot], choiceSharpness(), floor(), updates);
    }
}
