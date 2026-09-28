package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.matching.FragmentMatcher;
import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.matching.StructuralSimilarityScorer;
import io.github.bohdankordon.casinofingerprint.matching.TargetMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Production structural content-transition witness: an identity-independent check that the
 * normalized puzzle content changed relative to one frozen consumed-round baseline.
 *
 * <p>Measured provenance (Stage 6C.1C, 4442 full-rate real-game frames, 3800 same-round frames,
 * four real R1 -&gt; R2 transitions): at similarity cut {@value #STRUCTURAL_SIMILARITY_CUT} the
 * worst observed same-round frame changed 2 of 9 regions while the weakest real transition
 * changed 9 of 9. Requiring at least {@value #REQUIRED_CHANGED_REGIONS} of
 * {@value #TOTAL_REGIONS} regions to be below the cut therefore separates the two populations on
 * that dataset with a measured count margin (4 regions of safety gap, 3 regions of transition
 * slack), firing on the first new recognized frame with zero same-round false triggers.
 *
 * <p>These constants are data-backed provisional production constants, NOT calibrated
 * probabilities and NOT universal bounds. Four transitions are evidence, not a distribution: an
 * unseen overlay, a graphics setting or a same-target layout outside the measured diversity may
 * behave differently, and an exact visual repeat (similarity 1.0 everywhere) is intentionally
 * undetectable and stays fail-closed. There is deliberately no runtime threshold-tuning UI.
 *
 * <p>Identity independence: this class reads only normalized {@code Mat} profiles. It has no
 * field, parameter or branch that mentions a fingerprint, a selected candidate set, a recognition
 * decision, a consensus state or a lifecycle state. Its only job is to compare the current
 * normalized structural content with the frozen baseline and return plain
 * {@link PuzzleContentTransitionEvidence}. The lifecycle layer decides whether that evidence
 * matters. A static test enforces the isolation.
 *
 * <p>Baseline ownership: the witness owns the frozen normalized target clone plus the eight
 * frozen normalized candidate clones. No panel or raw screenshot is kept. The baseline is
 * immutable after arming, owns all native {@code Mat}s, is deterministically closed, is replaced
 * safely when a later round is consumed, is cleared on explicit reset, survives
 * {@code UNCERTAIN} / capture-error / unsupported frames, and is never continuously adapted. The
 * witness itself is {@link AutoCloseable}; no native resource depends on GC or finalization.
 *
 * <p>Arming: the baseline exists only after a round is successfully consumed. Even while armed,
 * the lifecycle consults the evidence only together with {@code STABLE_RECOGNIZED}, so entry and
 * exit content changes (which this rule measures strongly) can never create a round on their own.
 *
 * <p>State is deterministic and single-threaded: one witness belongs to one recognition stream.
 * There is no clock, no sleep, no duration and no input anywhere in this class.
 */
public final class PuzzleContentTransitionWitness implements AutoCloseable {
    /**
     * Structural similarity cut: a region counts as changed iff its similarity is strictly below
     * this value. Similarity exactly equal to the cut is NOT changed.
     *
     * <p>Chosen from the Stage 6C.1C real-data separation (worst same-round 2/9 vs weakest real
     * transition 9/9 at this cut). Conservative and provisional, not a calibrated probability.
     */
    public static final double STRUCTURAL_SIMILARITY_CUT = 0.50;

    /**
     * Changed-region count required to confirm a transition.
     *
     * <p>Chosen from the Stage 6C.1C real-data separation (K=6 sits in the middle of the observed
     * 2-vs-9 gap). Conservative and provisional, not a universal bound.
     */
    public static final int REQUIRED_CHANGED_REGIONS = 6;

    /** Total compared regions: the target plus eight same-position candidates. */
    public static final int TOTAL_REGIONS = 9;

    /** Candidate regions per puzzle. */
    public static final int CANDIDATE_COUNT = 8;

    private final StructuralSimilarityScorer scorer = new StructuralSimilarityScorer();
    private Mat baselineTarget;
    private List<Mat> baselineCandidates = List.of();
    private boolean closed;

    /** True when a consumed-round baseline is frozen and ready to compare against. */
    public boolean isArmed() {
        return !closed && baselineTarget != null;
    }

    /** True once {@link #close()} released the baseline. */
    public boolean closed() {
        return closed;
    }

    /**
     * Freezes the content of one consumed round, replacing any previous baseline.
     *
     * <p>The source frame is borrowed: its Mats are cloned and the source stays owned by its
     * caller. On success the witness is armed on the new content and the previous baseline, if
     * any, is closed. If cloning fails the previous baseline remains intact.
     *
     * @param source normalized puzzle of the consumed round; borrowed, not closed
     */
    public void arm(NormalizedPuzzleFrame source) {
        Objects.requireNonNull(source, "source");
        requireOpen();
        try (PreparedBaseline staged = prepare(source)) {
            install(staged);
        }
    }

    /**
     * Prepares a replacement baseline without touching the currently armed one.
     *
     * <p>This is the first half of the failure-safe consume-then-replace sequence used by
     * {@link RoundLifecycleWitnessCoordinator}: clone the new content first, consume the
     * lifecycle round second, and only then {@link #install(PreparedBaseline) install} the
     * prepared baseline. If preparation fails, the caller must not consume the round and the
     * existing baseline is untouched.
     *
     * @param source normalized puzzle to clone; borrowed, not closed
     * @return owned staged baseline that must be installed or closed by the caller
     */
    public PreparedBaseline prepare(NormalizedPuzzleFrame source) {
        Objects.requireNonNull(source, "source");
        requireOpen();
        Mat target = source.target().clone();
        List<Mat> candidates = new ArrayList<>(CANDIDATE_COUNT);
        try {
            for (Mat candidate : source.candidates()) {
                candidates.add(candidate.clone());
            }
        } catch (RuntimeException e) {
            target.close();
            for (Mat candidate : candidates) {
                candidate.close();
            }
            throw e;
        }
        if (candidates.size() != CANDIDATE_COUNT) {
            target.close();
            for (Mat candidate : candidates) {
                candidate.close();
            }
            throw new IllegalArgumentException("Exactly " + CANDIDATE_COUNT
                    + " candidate profiles are required, got " + candidates.size());
        }
        return new PreparedBaseline(target, List.copyOf(candidates));
    }

    /**
     * Installs a baseline previously returned by {@link #prepare(NormalizedPuzzleFrame)},
     * replacing the currently armed baseline.
     *
     * <p>On success the witness owns the staged Mats and the previous baseline is closed; the
     * staged holder is consumed and must not be used again. If the witness is closed the staged
     * baseline is closed here and an exception is thrown, leaving the previous state untouched
     * as far as possible (a closed witness holds no baseline).
     *
     * @param staged prepared baseline; ownership is transferred here
     */
    public void install(PreparedBaseline staged) {
        Objects.requireNonNull(staged, "staged");
        if (staged.transferred || staged.closed) {
            throw new IllegalStateException("Prepared baseline was already installed or closed");
        }
        requireOpen();
        Mat previousTarget = baselineTarget;
        List<Mat> previousCandidates = baselineCandidates;
        baselineTarget = staged.target;
        baselineCandidates = staged.candidates;
        staged.transferred = true;
        if (previousTarget != null) {
            previousTarget.close();
            for (Mat candidate : previousCandidates) {
                candidate.close();
            }
        }
    }

    /**
     * Measures one current normalized puzzle against the frozen baseline.
     *
     * @param current normalized puzzle of the current frame; borrowed, not closed
     * @return plain-data evidence; unconfirmed when the witness is not armed
     */
    public PuzzleContentTransitionEvidence measure(NormalizedPuzzleFrame current) {
        Objects.requireNonNull(current, "current");
        requireOpen();
        if (baselineTarget == null) {
            return PuzzleContentTransitionEvidence.absent();
        }
        double targetSimilarity = scorer
                .score(baselineTarget, current.target(), TargetMatcher.TRANSLATION_RADIUS)
                .value();
        List<Mat> currentCandidates = current.candidates();
        if (currentCandidates.size() != CANDIDATE_COUNT) {
            throw new IllegalArgumentException("Exactly " + CANDIDATE_COUNT
                    + " candidate profiles are required, got " + currentCandidates.size());
        }
        List<Double> similarities = new ArrayList<>(CANDIDATE_COUNT);
        for (int index = 0; index < CANDIDATE_COUNT; index++) {
            similarities.add(scorer.score(baselineCandidates.get(index),
                    currentCandidates.get(index), FragmentMatcher.TRANSLATION_RADIUS).value());
        }
        int changed = 0;
        if (targetSimilarity < STRUCTURAL_SIMILARITY_CUT) {
            changed++;
        }
        for (double similarity : similarities) {
            if (similarity < STRUCTURAL_SIMILARITY_CUT) {
                changed++;
            }
        }
        return new PuzzleContentTransitionEvidence(changed >= REQUIRED_CHANGED_REGIONS, changed,
                targetSimilarity, List.copyOf(similarities));
    }

    /**
     * Explicit full witness reset: closes and clears the frozen baseline. The witness stays open
     * but unarmed. Never called automatically, in particular never because the stream contains
     * {@code UNCERTAIN}, capture problems or candidate recognitions.
     */
    public void clear() {
        requireOpen();
        if (baselineTarget != null) {
            baselineTarget.close();
            for (Mat candidate : baselineCandidates) {
                candidate.close();
            }
            baselineTarget = null;
            baselineCandidates = List.of();
        }
    }

    /** Releases the frozen baseline, if any. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (baselineTarget != null) {
                baselineTarget.close();
                for (Mat candidate : baselineCandidates) {
                    candidate.close();
                }
                baselineTarget = null;
                baselineCandidates = List.of();
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("PuzzleContentTransitionWitness is closed");
        }
    }

    /**
     * One prepared replacement baseline: cloned target plus eight cloned candidates waiting to be
     * {@link PuzzleContentTransitionWitness#install(PreparedBaseline) installed}.
     *
     * <p>Owns its Mats until installation transfers them or {@link #close()} releases them.
     * Close is idempotent; installing consumes the holder so a later close is a no-op.
     */
    public static final class PreparedBaseline implements AutoCloseable {
        private final Mat target;
        private final List<Mat> candidates;
        private boolean transferred;
        private boolean closed;

        private PreparedBaseline(Mat target, List<Mat> candidates) {
            this.target = target;
            this.candidates = candidates;
        }

        /** True once {@link #close()} released the staged Mats. */
        public boolean closed() {
            return closed;
        }

        /** Releases the staged Mats unless they were installed. Idempotent. */
        @Override
        public void close() {
            if (!closed && !transferred) {
                closed = true;
                target.close();
                for (Mat candidate : candidates) {
                    candidate.close();
                }
            } else {
                closed = true;
            }
        }
    }
}
