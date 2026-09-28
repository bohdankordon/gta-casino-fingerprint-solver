package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import java.util.Objects;
import java.util.Optional;

/**
 * Production integration of the round lifecycle with the structural content-transition witness.
 *
 * <p>Conceptual position:
 *
 * <pre>
 * RecognitionDecision
 *     -&gt; RecognitionConsensusTracker
 *     -&gt; STABLE identity + structural content witness
 *     -&gt; RoundLifecycleTracker
 * </pre>
 *
 * <p>The witness is strictly additive. The coordinator owns one {@link RoundLifecycleTracker} and
 * one {@link PuzzleContentTransitionWitness} and pairs them so that a caller cannot forget the
 * baseline side of consumption:
 *
 * <ul>
 *   <li>{@link #accept(LiveRecognitionStatus, NormalizedPuzzleFrame)} measures the current
 *       normalized puzzle against the frozen baseline (when armed) and feeds the consensus status
 *       plus the plain-data evidence into the lifecycle tracker;</li>
 *   <li>{@link #consumeReadyRound(NormalizedPuzzleFrame)} atomically pairs lifecycle consumption
 *       with baseline replacement on the exact content of the consumed round;</li>
 *   <li>{@link #reset()} clears both the lifecycle state and the witness baseline;</li>
 *   <li>{@link #close()} releases the witness baseline deterministically.</li>
 * </ul>
 *
 * <p>Baseline re-arm contract: after EVERY successful round consumption, including a witnessed
 * repeated-identity round, the baseline becomes the exact content of THAT consumed round, so a
 * later unchanged frame of the same content does not fire again. The only way to consume through
 * this coordinator replaces the baseline; there is no consume path that leaves a stale baseline
 * behind.
 *
 * <p>Failure order for baseline replacement: the new baseline is prepared (cloned) first; the
 * round must still be consumable; the lifecycle round is consumed; the prepared baseline is
 * installed; the previous baseline is closed. If preparation fails the lifecycle stays
 * unconsumed. If lifecycle consumption fails the prepared baseline is closed and the existing
 * baseline stays intact. The lifecycle is therefore never left consumed with no corresponding
 * baseline because a clone failed.
 *
 * <p>Entry and exit safety: the witness is armed only after a round is successfully consumed.
 * Even while armed, the lifecycle consults the evidence only together with
 * {@code STABLE_RECOGNIZED}, so exit content changes (which become {@code UNCERTAIN}
 * immediately) do nothing and the baseline survives until a later successful consumption or an
 * explicit reset. {@code UNCERTAIN}, {@code CAPTURE_ERROR}, {@code UNSUPPORTED_FRAME} and
 * {@code CANDIDATE_RECOGNITION} never disarm or reset the baseline.
 *
 * <p>Ownership: normalized puzzles passed to {@code accept} and {@code consumeReadyRound} are
 * borrowed and never closed here. The coordinator owns the witness baseline; the caller owns the
 * per-frame observation (see {@link FrameRecognitionObservation}). No {@code Mat} escapes without
 * an explicit ownership contract.
 *
 * <p>There is no clock, no sleep, no duration, no input and no automation anywhere in this class.
 * State is deterministic and single-threaded: one coordinator belongs to one recognition stream.
 */
public final class RoundLifecycleWitnessCoordinator implements AutoCloseable {
    private final RoundLifecycleTracker lifecycle = new RoundLifecycleTracker();
    private final PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
    private boolean closed;

    /** Current lifecycle state. */
    public RoundLifecycleState state() {
        return lifecycle.state();
    }

    /** Round waiting for downstream acknowledgement; empty unless {@code ROUND_READY}. */
    public Optional<RecognitionIdentity> readyIdentity() {
        return lifecycle.readyIdentity();
    }

    /** Last acknowledged round identity; empty until the first consumption. */
    public Optional<RecognitionIdentity> consumedIdentity() {
        return lifecycle.consumedIdentity();
    }

    /** True when a consumed-round baseline is frozen and ready to compare against. */
    public boolean isWitnessArmed() {
        return witness.isArmed();
    }

    /** True once {@link #close()} released the witness baseline. */
    public boolean closed() {
        return closed;
    }

    /**
     * Feeds one consensus output without content: behaves exactly like the witness-free lifecycle
     * (used for frames that produced no normalized puzzle, such as capture problems).
     *
     * @param consensusStatus output of {@link RecognitionConsensusTracker} for one frame
     * @return lifecycle snapshot after this observation
     */
    public RoundLifecycleStatus accept(LiveRecognitionStatus consensusStatus) {
        requireOpen();
        Objects.requireNonNull(consensusStatus, "consensusStatus");
        return lifecycle.accept(consensusStatus, PuzzleContentTransitionEvidence.absent());
    }

    /**
     * Feeds one consensus output plus the normalized puzzle of the SAME frame.
     *
     * <p>The puzzle is borrowed: it is read for the witness comparison and never closed here.
     * A {@code null} puzzle means no content is available for this frame and behaves like no
     * witness. While the witness is unarmed the lifecycle behaves exactly as without it.
     *
     * @param consensusStatus output of {@link RecognitionConsensusTracker} for one frame
     * @param puzzle normalized puzzle of the same frame, or {@code null} when unavailable;
     *        borrowed, not closed
     * @return lifecycle snapshot after this observation, with {@code transitionWitnessUsed} set
     *         only when an otherwise-suppressed repeated consumed identity became ready because
     *         independent content change was confirmed
     */
    public RoundLifecycleStatus accept(LiveRecognitionStatus consensusStatus,
            NormalizedPuzzleFrame puzzle) {
        requireOpen();
        Objects.requireNonNull(consensusStatus, "consensusStatus");
        if (puzzle == null || !witness.isArmed()) {
            return lifecycle.accept(consensusStatus, PuzzleContentTransitionEvidence.absent());
        }
        return lifecycle.accept(consensusStatus, witness.measure(puzzle));
    }

    /**
     * Measures the witness evidence for the given puzzle without feeding the lifecycle. Useful
     * for diagnostics; the lifecycle decision still happens in
     * {@link #accept(LiveRecognitionStatus, NormalizedPuzzleFrame)}.
     *
     * @param puzzle normalized puzzle to compare; borrowed, not closed
     * @return plain-data evidence; unconfirmed when the witness is not armed
     */
    public PuzzleContentTransitionEvidence evidenceFor(NormalizedPuzzleFrame puzzle) {
        requireOpen();
        Objects.requireNonNull(puzzle, "puzzle");
        if (!witness.isArmed()) {
            return PuzzleContentTransitionEvidence.absent();
        }
        return witness.measure(puzzle);
    }

    /**
     * Acknowledges ownership of the ready round and re-arms the witness baseline on the exact
     * content of THAT consumed round, atomically.
     *
     * <p>Preferred failure order: the replacement baseline is cloned first; the lifecycle round
     * is consumed second; the prepared baseline is installed third and the previous baseline is
     * closed last. If preparation fails the lifecycle stays unconsumed. If lifecycle consumption
     * fails (for example a stale round) the prepared baseline is closed and the existing baseline
     * stays intact.
     *
     * @param consumedPuzzle normalized puzzle of the consumed round frame; borrowed, not closed
     * @return exactly the canonical ready identity
     * @throws IllegalStateException when no round is consumable or the coordinator is closed
     */
    public RecognitionIdentity consumeReadyRound(NormalizedPuzzleFrame consumedPuzzle) {
        requireOpen();
        Objects.requireNonNull(consumedPuzzle, "consumedPuzzle");
        try (PuzzleContentTransitionWitness.PreparedBaseline staged =
                witness.prepare(consumedPuzzle)) {
            RecognitionIdentity consumed = lifecycle.consumeReadyRound();
            witness.install(staged);
            return consumed;
        }
    }

    /**
     * Explicit full reset: clears both the lifecycle state and the witness baseline. No stale
     * native baseline survives. Session/external control only; never called automatically, in
     * particular never because the stream contains {@code UNCERTAIN}, capture problems or
     * candidate recognitions.
     */
    public void reset() {
        requireOpen();
        lifecycle.reset();
        witness.clear();
    }

    /** Releases the witness baseline. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            witness.close();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("RoundLifecycleWitnessCoordinator is closed");
        }
    }
}
