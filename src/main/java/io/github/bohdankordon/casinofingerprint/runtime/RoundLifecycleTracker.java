package io.github.bohdankordon.casinofingerprint.runtime;

import java.util.Objects;
import java.util.Optional;

/**
 * Production round lifecycle tracker: answers {@code is this stable recognition a NEW round
 * that downstream code may consume?}
 *
 * <p>Conceptual position in the pipeline:
 *
 * <pre>
 * RecognitionDecision -&gt; RecognitionConsensusTracker -&gt; RoundLifecycleTracker
 * </pre>
 *
 * <p>The tracker consumes the OUTPUT of {@link RecognitionConsensusTracker} via
 * {@link #accept(LiveRecognitionStatus)} and never reimplements recognition consensus itself.
 * Only {@code STABLE_RECOGNIZED} may create a lifecycle round; every other consensus state is
 * never actionable and — critically — never clears lifecycle memory. {@code UNCERTAIN} is a
 * refusal of the conservative policy, not a claim that the puzzle is absent, so treating it as
 * a reset would re-enable the previous-round carryover bug measured in Stage 6C.1A.
 *
 * <p>Stable EPISODES, not stable frames: the consensus tracker reports {@code STABLE_RECOGNIZED}
 * on every frame after the required streak is reached, but only a stable-episode ONSET (a
 * stable identity that differs from the previously observed stable identity, or any stable
 * identity after a non-stable observation) is a lifecycle event. A continued stable frame is
 * silent.
 *
 * <p>State machine:
 *
 * <pre>
 * WAITING_FOR_STABLE --stable onset(X)--&gt; ROUND_READY(X)
 * ROUND_READY(P)     --stable onset(P)--&gt; ROUND_READY(P)   (pending re-stabilized, still ready)
 * ROUND_READY(P)     --stable onset(Q!=P)--&gt; DESYNCHRONIZED (pending P kept as stale diagnostic)
 * ROUND_READY(P)     --anything else--&gt; ROUND_READY(P)     (consume validity follows the
 *                                                          latest observation, see below)
 * ROUND_CONSUMED(C)  --stable onset(C)--&gt; ROUND_CONSUMED(C) (suppressed, never a new round)
 * ROUND_CONSUMED(C)  --stable onset(Q!=C)--&gt; ROUND_READY(Q)
 * DESYNCHRONIZED     --anything--&gt; DESYNCHRONIZED          (only reset() clears it)
 * </pre>
 *
 * <p>Consumption contract ({@link #consumeReadyRound()}): valid only while the state is
 * {@code ROUND_READY} AND the most recently accepted consensus observation is still
 * {@code STABLE_RECOGNIZED} of exactly the ready identity. If the consensus has ceased to be
 * stably the ready identity before the consume call — an {@code UNCERTAIN} frame, a candidate,
 * a capture problem — the consume call is rejected instead of silently acknowledging a stale
 * round. There is no time grace period. If the ready identity later becomes stable again while
 * still pending, consuming it may succeed again. Consumption is lifecycle acknowledgement
 * only: it never resets the consensus tracker, never clears the consumed identity, and never
 * implies gameplay input or success.
 *
 * <p>Same-identity rule and its witnessed exception: after an identity is consumed, that SAME
 * identity stays suppressed fail-closed — unless an independent structural content transition is
 * confirmed at the same time as {@code STABLE_RECOGNIZED} of that same identity. The witness is
 * strictly additive: it may only permit a repeated consumed identity to become a new round, and
 * it can never create a round on its own. Any other combination (non-stable consensus, waiting,
 * pending or desynchronized lifecycle) ignores the witness. A different stable identity becomes
 * the next round without any witness evidence.
 *
 * <p>A witnessed same-identity transition needs no new consensus episode onset. Consensus
 * compares answer identity, not puzzle pixels, so a real {@code A -> A} transition can look like
 * {@code STABLE A, STABLE A, [visual content changes], STABLE A, STABLE A}. While
 * {@code ROUND_CONSUMED}, a current {@code STABLE} identity equal to the consumed identity plus
 * confirmed witness evidence is therefore sufficient to segment a new lifecycle round. This stays
 * safe only because {@code STABLE} consensus is required simultaneously.
 *
 * <p>Only the LATEST consumed identity is remembered. A legitimate later round may reuse an
 * identity consumed two rounds ago: after {@code A -&gt; consume, B -&gt; consume}, a stable
 * {@code A} differs from the current consumed identity {@code B} and may become ready. No
 * history set is kept.
 *
 * <p>{@link #reset()} is a session/external control primitive only. It is never called
 * automatically, in particular never because the stream contains {@code UNCERTAIN},
 * {@code CAPTURE_ERROR} or {@code UNSUPPORTED_FRAME}.
 *
 * <p>The tracker knows nothing about resolutions, hack ids, round numbers, timestamps or
 * recordings, and its correctness never depends on a clock, a sleep or a duration: there is
 * no timing constant anywhere in this class.
 *
 * <p>State is deterministic and single-threaded: one tracker belongs to one recognition stream.
 */
public final class RoundLifecycleTracker {
    private RoundLifecycleState state = RoundLifecycleState.WAITING_FOR_STABLE;
    private RecognitionIdentity readyIdentity;
    private RecognitionIdentity consumedIdentity;

    /** Latest accepted consensus observation, for the consume-validity rule. */
    private boolean latestStable;
    private RecognitionIdentity latestStableIdentity;

    /** Previous accepted observation, for stable-episode onset detection. */
    private boolean previousStable;
    private RecognitionIdentity previousStableIdentity;

    /** Current lifecycle state. */
    public RoundLifecycleState state() {
        return state;
    }

    /** Round waiting for downstream acknowledgement; empty unless {@code ROUND_READY}. */
    public Optional<RecognitionIdentity> readyIdentity() {
        return state == RoundLifecycleState.ROUND_READY
                ? Optional.of(readyIdentity)
                : Optional.empty();
    }

    /** Last acknowledged round identity; empty until the first consumption. */
    public Optional<RecognitionIdentity> consumedIdentity() {
        return Optional.ofNullable(consumedIdentity);
    }

    /**
     * Feeds one consensus output into the lifecycle state machine.
     *
     * <p>Equivalent to {@link #accept(LiveRecognitionStatus, PuzzleContentTransitionEvidence)}
     * with no independent witness: the fail-closed same-identity suppression always applies.
     *
     * @param consensusStatus output of {@link RecognitionConsensusTracker} for one frame
     * @return immutable snapshot of the lifecycle after this observation, including which
     *        lifecycle event this observation produced, if any
     */
    public RoundLifecycleStatus accept(LiveRecognitionStatus consensusStatus) {
        return accept(consensusStatus, PuzzleContentTransitionEvidence.absent());
    }

    /**
     * Feeds one consensus output plus independent structural content-transition evidence into the
     * lifecycle state machine.
     *
     * <p>The witness is strictly additive. The only permitted witnessed path is: the tracker is
     * currently {@code ROUND_CONSUMED}, the current consensus status is
     * {@code STABLE_RECOGNIZED}, the current stable identity equals the consumed identity, and
     * the independent witness confirms a structural transition — then the same identity becomes
     * {@code ROUND_READY} with {@code transitionWitnessUsed} set. Every other combination ignores
     * the witness: non-stable consensus, a waiting/pending/desynchronized tracker, or a different
     * stable identity (which follows the existing identity-change path with no witness needed).
     * In particular a witnessed same-identity transition needs no new consensus episode onset:
     * a continued {@code STABLE} frame of the consumed identity plus confirmed evidence is
     * sufficient.
     *
     * @param consensusStatus output of {@link RecognitionConsensusTracker} for one frame
     * @param transitionEvidence plain-data structural witness outcome for the same frame; never
     *        {@code null} (use {@link PuzzleContentTransitionEvidence#absent()} for no witness)
     * @return immutable snapshot of the lifecycle after this observation
     */
    public RoundLifecycleStatus accept(LiveRecognitionStatus consensusStatus,
            PuzzleContentTransitionEvidence transitionEvidence) {
        Objects.requireNonNull(consensusStatus, "consensusStatus");
        Objects.requireNonNull(transitionEvidence, "transitionEvidence");
        boolean stable = consensusStatus.state() == LiveRecognitionState.STABLE_RECOGNIZED;
        RecognitionIdentity current = stable ? identityOf(consensusStatus) : null;
        boolean onset = stable && !(previousStable && current.equals(previousStableIdentity));
        // The witness is consulted only together with STABLE consensus while ROUND_CONSUMED and
        // only for the repeated consumed identity. Every other path ignores it.
        boolean witnessConfirmed = stable && state == RoundLifecycleState.ROUND_CONSUMED
                && consumedIdentity != null && current.equals(consumedIdentity)
                && transitionEvidence.transitionConfirmed();

        boolean newRoundReady = false;
        boolean consumedIdentityRepeated = false;
        boolean desynchronizedNow = false;
        boolean transitionWitnessUsed = false;

        switch (state) {
            case WAITING_FOR_STABLE -> {
                if (onset) {
                    state = RoundLifecycleState.ROUND_READY;
                    readyIdentity = current;
                    newRoundReady = true;
                }
            }
            case ROUND_READY -> {
                if (onset) {
                    if (current.equals(readyIdentity)) {
                        // The still-pending round re-stabilized after an interruption:
                        // still ready, and consumable again (see consumeReadyRound).
                    } else {
                        // A different stable answer appeared while the previous round was
                        // never consumed: the consumer contract has fallen behind the game.
                        // Never silently replace the pending round.
                        state = RoundLifecycleState.DESYNCHRONIZED;
                        desynchronizedNow = true;
                    }
                }
            }
            case ROUND_CONSUMED -> {
                if (stable && current.equals(consumedIdentity)) {
                    if (witnessConfirmed) {
                        // Strictly additive exception: the repeated consumed identity becomes a
                        // new round because independent structural content change was confirmed
                        // at the same time as STABLE consensus of that same identity. Works on
                        // an onset and on a continued STABLE frame alike.
                        state = RoundLifecycleState.ROUND_READY;
                        readyIdentity = current;
                        newRoundReady = true;
                        transitionWitnessUsed = true;
                    } else if (onset) {
                        consumedIdentityRepeated = true;
                    }
                } else if (onset) {
                    state = RoundLifecycleState.ROUND_READY;
                    readyIdentity = current;
                    newRoundReady = true;
                }
            }
            case DESYNCHRONIZED -> {
                // Fail closed: no observation recovers the tracker, only reset() does.
            }
        }

        previousStable = stable;
        previousStableIdentity = current;
        latestStable = stable;
        latestStableIdentity = current;

        return new RoundLifecycleStatus(state,
                state == RoundLifecycleState.ROUND_READY
                        || state == RoundLifecycleState.DESYNCHRONIZED ? readyIdentity : null,
                consumedIdentity, current, newRoundReady, consumedIdentityRepeated,
                desynchronizedNow, transitionWitnessUsed);
    }

    /**
     * Acknowledges ownership of the ready round identity.
     *
     * <p>This is lifecycle acknowledgement only — not gameplay input, not gameplay success.
     * It transitions {@code ROUND_READY} to {@code ROUND_CONSUMED} and records exactly the
     * ready identity as the most recently consumed one. That consumed identity stays
     * remembered while the same identity continues, while observations are non-stable,
     * while a different identity becomes and remains pending, and while the tracker is
     * desynchronized; it is replaced only by a later successful consumption and cleared
     * only by {@code reset()}.
     *
     * @return exactly the canonical ready identity
     * @throws IllegalStateException when the state is not {@code ROUND_READY} (waiting,
     *         already consumed, or desynchronized), or when the most recently accepted
     *         consensus observation is no longer {@code STABLE_RECOGNIZED} of the ready
     *         identity, i.e. consuming a stale round is rejected
     */
    public RecognitionIdentity consumeReadyRound() {
        if (state != RoundLifecycleState.ROUND_READY) {
            throw new IllegalStateException(
                    "A round can only be consumed while ROUND_READY, not while " + state);
        }
        if (!latestStable || !readyIdentity.equals(latestStableIdentity)) {
            throw new IllegalStateException(
                    "The ready round " + readyIdentity.code()
                            + " is stale: the latest consensus observation is no longer stably"
                            + " that identity, so it must not be acknowledged");
        }
        RecognitionIdentity consumed = readyIdentity;
        readyIdentity = null;
        consumedIdentity = consumed;
        state = RoundLifecycleState.ROUND_CONSUMED;
        return consumed;
    }

    /**
     * Explicit full lifecycle reset: clears the ready identity, the consumed identity, the
     * desynchronization and the stable-episode memory, returning to
     * {@code WAITING_FOR_STABLE}.
     *
     * <p>Session/external control only. The tracker never calls this itself, and no
     * observation ({@code UNCERTAIN}, capture problems, consensus resets) triggers it.
     */
    public void reset() {
        state = RoundLifecycleState.WAITING_FOR_STABLE;
        readyIdentity = null;
        consumedIdentity = null;
        latestStable = false;
        latestStableIdentity = null;
        previousStable = false;
        previousStableIdentity = null;
    }

    private static RecognitionIdentity identityOf(LiveRecognitionStatus status) {
        if (status.fingerprint().isEmpty()) {
            throw new IllegalStateException(
                    "A STABLE_RECOGNIZED status must carry a fingerprint");
        }
        return RecognitionIdentity.of(status.fingerprint().orElseThrow(),
                status.selectedCandidates());
    }
}
