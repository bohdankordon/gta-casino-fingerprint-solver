package io.github.bohdankordon.casinofingerprint.runtime;

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
 *   <li>{@link #accept(LiveRecognitionStatus, FrameRecognitionObservation)} measures the
 *       normalized puzzle of the SAME accepted frame against the frozen baseline (when
 *       armed) and feeds the consensus status plus the plain-data evidence into the
 *       lifecycle tracker, remembering that observation as the consumable one while it
 *       stays the latest stable frame of the ready round;</li>
 *   <li>{@link #consumeReadyRound(FrameRecognitionObservation)} accepts only that remembered
 *       observation and atomically pairs lifecycle consumption with baseline replacement
 *       on its exact content;</li>
 *   <li>{@link #reset()} clears the lifecycle state, the witness baseline and the
 *       consumable-observation reference;</li>
 *   <li>{@link #close()} releases the witness baseline and the reference deterministically.</li>
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
 * <p>Frame binding: consumption is bound to the accepted observation. The coordinator remembers
 * which {@link FrameRecognitionObservation} produced the latest currently-stable ready round;
 * {@code consumeReadyRound} rejects any other, older, closed or missing observation, so the
 * replacement baseline always comes from the exact accepted frame whose round is consumed and
 * never from unrelated content. A stale, different or closed observation can therefore never
 * re-arm the witness. When the latest accepted consensus stops being stably the ready identity,
 * the previous observation stops being consumable (the pending round itself follows the existing
 * lifecycle semantics); when the pending identity re-stabilizes, the new current observation
 * becomes the consumable one. Desynchronization leaves no consumable observation.
 *
 * <p>Ownership: observations passed to {@code accept} and {@code consumeReadyRound} are borrowed
 * and never closed or retained beyond a validation reference here. The coordinator owns the
 * witness baseline; the caller owns each per-frame observation and closes it. No {@code Mat}
 * escapes without an explicit ownership contract.
 *
 * <p>There is no clock, no sleep, no duration, no input and no automation anywhere in this class.
 * State is deterministic and single-threaded: one coordinator belongs to one recognition stream.
 */
public final class RoundLifecycleWitnessCoordinator implements AutoCloseable {
    private final RoundLifecycleTracker lifecycle = new RoundLifecycleTracker();
    private final PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
    private boolean closed;

    /**
     * The observation that produced the latest currently-stable frame of the ready round, or
     * {@code null} when nothing is currently consumable. A plain validation reference only:
     * the coordinator never closes it and never reads content through it except inside
     * {@code consumeReadyRound} after the identity check.
     */
    private FrameRecognitionObservation consumableObservation;

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
        RoundLifecycleStatus update =
                lifecycle.accept(consensusStatus, PuzzleContentTransitionEvidence.absent());
        // No frame content arrived with this observation, so nothing is consumable through an
        // observation until a later frame-bound accept provides one.
        consumableObservation = null;
        return update;
    }

    /**
     * Feeds one consensus output plus the owned observation of the SAME frame that produced
     * the consensus decision.
     *
     * <p>The observation is borrowed: its puzzle is read for the witness comparison and the
     * observation itself is remembered for consumption validation, but it is never closed
     * here. A {@code null} observation means no content is available for this frame and
     * behaves like no witness. While the witness is unarmed the lifecycle behaves exactly
     * as without it.
     *
     * <p>The same-frame invariant is established here: the observation becomes the consumable
     * one only while the returned snapshot shows {@code ROUND_READY} whose ready identity is
     * still the current stable identity. Any later non-stable observation, any observation
     * of another identity, an explicit no-frame accept, a reset or a desynchronization
     * clears it.
     *
     * @param consensusStatus output of {@link RecognitionConsensusTracker} for the decision
     *        of {@code observation}
     * @param observation owned observation of the same frame, or {@code null} when no frame
     *        content is available; borrowed, not closed
     * @return lifecycle snapshot after this observation, with {@code transitionWitnessUsed} set
     *         only when an otherwise-suppressed repeated consumed identity became ready because
     *         independent content change was confirmed
     */
    public RoundLifecycleStatus accept(LiveRecognitionStatus consensusStatus,
            FrameRecognitionObservation observation) {
        requireOpen();
        Objects.requireNonNull(consensusStatus, "consensusStatus");
        if (observation != null && observation.closed()) {
            throw new IllegalStateException(
                    "The observation is closed; its normalized puzzle is released");
        }
        PuzzleContentTransitionEvidence evidence;
        if (observation == null || !witness.isArmed()) {
            evidence = PuzzleContentTransitionEvidence.absent();
        } else {
            evidence = witness.measure(observation.puzzle());
        }
        RoundLifecycleStatus update = lifecycle.accept(consensusStatus, evidence);
        if (update.state() == RoundLifecycleState.ROUND_READY
                && update.stable().isPresent()
                && update.ready().isPresent()
                && update.stable().get().equals(update.ready().get())
                && observation != null) {
            consumableObservation = observation;
        } else {
            consumableObservation = null;
        }
        return update;
    }

    /**
     * Measures the witness evidence for the puzzle of the given observation without feeding
     * the lifecycle and without touching the consumable-observation reference. Useful for
     * diagnostics; the lifecycle decision still happens in
     * {@link #accept(LiveRecognitionStatus, FrameRecognitionObservation)}.
     *
     * @param observation observation whose puzzle is compared; borrowed, not closed
     * @return plain-data evidence; unconfirmed when the witness is not armed
     */
    public PuzzleContentTransitionEvidence evidenceFor(FrameRecognitionObservation observation) {
        requireOpen();
        Objects.requireNonNull(observation, "observation");
        if (!witness.isArmed()) {
            return PuzzleContentTransitionEvidence.absent();
        }
        return witness.measure(observation.puzzle());
    }

    /**
     * Acknowledges ownership of the ready round and re-arms the witness baseline on the exact
     * content of THAT consumed frame, atomically.
     *
     * <p>Frame-bound consumption: {@code observation} must be the same observation that the
     * latest {@link #accept(LiveRecognitionStatus, FrameRecognitionObservation)} remembered
     * as currently consumable — the observation of the latest still-stable frame of the ready
     * round. Any other observation (older, different, or accepted while the consensus was not
     * stably the ready identity), a missing reference after a non-stable observation, reset,
     * desynchronization or close, or a closed observation is rejected with
     * {@code IllegalStateException} or {@code NullPointerException}, the lifecycle stays
     * unconsumed, and the existing baseline stays intact. A closed observation fails on
     * {@code puzzle()} access before anything is prepared or consumed.
     *
     * <p>Preferred failure order otherwise: the replacement baseline is cloned from the
     * validated observation first; the lifecycle round is consumed second; the prepared
     * baseline is installed third and the previous baseline is closed last. The
     * consumable-observation reference is cleared on success, on reset and on close.
     *
     * @param observation the accepted observation currently eligible for consumption;
     *        borrowed, not closed
     * @return exactly the canonical ready identity
     * @throws IllegalStateException when the observation is not the currently consumable
     *         accepted observation, when its content is no longer usable, or when no round
     *         is consumable
     */
    public RecognitionIdentity consumeReadyRound(FrameRecognitionObservation observation) {
        requireOpen();
        Objects.requireNonNull(observation, "observation");
        if (observation != consumableObservation) {
            throw new IllegalStateException("The observation is not the accepted observation "
                    + "currently eligible for consumption: it is older, different, or the "
                    + "latest accepted consensus is no longer stably the ready identity");
        }
        try (PuzzleContentTransitionWitness.PreparedBaseline staged =
                witness.prepare(observation.puzzle())) {
            RecognitionIdentity consumed = lifecycle.consumeReadyRound();
            witness.install(staged);
            consumableObservation = null;
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
        consumableObservation = null;
    }

    /** Releases the witness baseline. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            witness.close();
            consumableObservation = null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("RoundLifecycleWitnessCoordinator is closed");
        }
    }
}
