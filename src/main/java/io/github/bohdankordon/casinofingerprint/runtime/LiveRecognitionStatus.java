package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.recognition.UncertaintyReason;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One snapshot of the live recognition state, with the recognition behind it when there is one.
 *
 * <p>A status never describes input. It says what the runtime saw and how strong the conservative
 * Stage 4 decision was; turning that into gameplay actions is deliberately not part of this
 * stage.
 *
 * <p>Uncertain and unsupported outcomes are NOT "puzzle absent": an uncertain frame can be a real
 * puzzle the policy refuses to confirm, and an unsupported frame is a capture problem. Only the
 * wording of the status, and nothing else, distinguishes them.
 */
public final class LiveRecognitionStatus {
    private final LiveRecognitionState state;
    private final RecognitionDecision decision;
    private final int streak;
    private final int requiredStreak;
    private final String message;

    private LiveRecognitionStatus(LiveRecognitionState state, RecognitionDecision decision,
            int streak, int requiredStreak, String message) {
        this.state = Objects.requireNonNull(state, "state");
        this.decision = decision;
        this.streak = streak;
        this.requiredStreak = requiredStreak;
        this.message = message;
    }

    /** Initial state: nothing captured yet. */
    public static LiveRecognitionStatus waiting() {
        return new LiveRecognitionStatus(LiveRecognitionState.WAITING, null, 0, 0, null);
    }

    /** A frame was recognized but the policy did not confirm it. */
    public static LiveRecognitionStatus uncertain(RecognitionDecision decision) {
        return new LiveRecognitionStatus(
                LiveRecognitionState.UNCERTAIN, Objects.requireNonNull(decision, "decision"),
                0, 0, null);
    }

    /** One recognized frame, without any consensus check (--once mode). */
    public static LiveRecognitionStatus recognized(RecognitionDecision decision) {
        return new LiveRecognitionStatus(
                LiveRecognitionState.RECOGNIZED, requireRecognized(decision), 0, 0, null);
    }

    /** Consecutive identical recognized answers, still below the required stability count. */
    public static LiveRecognitionStatus candidate(
            RecognitionDecision decision, int streak, int requiredStreak) {
        requireStreak(streak, requiredStreak);
        return new LiveRecognitionStatus(LiveRecognitionState.CANDIDATE_RECOGNITION,
                requireRecognized(decision), streak, requiredStreak, null);
    }

    /** The required number of consecutive identical recognized answers was reached. */
    public static LiveRecognitionStatus stable(
            RecognitionDecision decision, int streak, int requiredStreak) {
        requireStreak(streak, requiredStreak);
        if (streak < requiredStreak) {
            throw new IllegalArgumentException("A stable answer needs at least " + requiredStreak
                    + " consecutive frames, got " + streak);
        }
        return new LiveRecognitionStatus(LiveRecognitionState.STABLE_RECOGNIZED,
                requireRecognized(decision), streak, requiredStreak, null);
    }

    /** The captured frame was not the supported physical layout size. */
    public static LiveRecognitionStatus unsupportedFrame(String message) {
        return new LiveRecognitionStatus(
                LiveRecognitionState.UNSUPPORTED_FRAME, null, 0, 0, requireMessage(message));
    }

    /** The capture backend failed for this frame. */
    public static LiveRecognitionStatus captureError(String message) {
        return new LiveRecognitionStatus(
                LiveRecognitionState.CAPTURE_ERROR, null, 0, 0, requireMessage(message));
    }

    /** Maps one decision from a single frame to RECOGNIZED or UNCERTAIN. */
    public static LiveRecognitionStatus ofSingleFrame(RecognitionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        return decision.result().status() == RecognitionResult.Status.RECOGNIZED
                ? recognized(decision)
                : uncertain(decision);
    }

    /** Current runtime state. */
    public LiveRecognitionState state() {
        return state;
    }

    /** Recognition behind this status, when a frame was recognized at all. */
    public Optional<RecognitionDecision> decision() {
        return Optional.ofNullable(decision);
    }

    /** Recognized fingerprint, when there is one. */
    public Optional<FingerprintId> fingerprint() {
        return decision == null ? Optional.empty() : decision.result().fingerprintId();
    }

    /** Selected candidate indices, sorted ascending; empty unless a frame was recognized. */
    public List<Integer> selectedCandidates() {
        return decision == null ? List.of() : decision.result().selectedCandidateIndices();
    }

    /** Deterministic structural evidence strength, when a frame was recognized at all. */
    public OptionalDouble evidence() {
        return decision == null
                ? OptionalDouble.empty()
                : OptionalDouble.of(decision.result().confidence());
    }

    /** Failing policy gates of an uncertain frame; empty otherwise. */
    public List<UncertaintyReason> uncertaintyReasons() {
        return decision == null ? List.of() : decision.uncertaintyReasons();
    }

    /** Consecutive identical recognized answers so far; zero outside the consensus states. */
    public int streak() {
        return streak;
    }

    /** Required consecutive identical recognized answers; zero outside the consensus states. */
    public int requiredStreak() {
        return requiredStreak;
    }

    /** Diagnostic text for UNSUPPORTED_FRAME and CAPTURE_ERROR. */
    public Optional<String> message() {
        return Optional.ofNullable(message);
    }

    /** One-line description used by the command-line runtime and by tests. */
    public String describe() {
        switch (state) {
            case WAITING:
                return "WAITING no frame has been captured yet";
            case UNSUPPORTED_FRAME:
                return "UNSUPPORTED_FRAME " + message;
            case CAPTURE_ERROR:
                return "CAPTURE_ERROR " + message;
            case UNCERTAIN:
                return String.format(Locale.ROOT, "UNCERTAIN evidence=%.4f reasons=%s",
                        confidence(), uncertaintyReasons());
            case RECOGNIZED:
                return String.format(Locale.ROOT, "RECOGNIZED %s %s evidence=%.4f",
                        fingerprintText(), selectedCandidates(), confidence());
            case CANDIDATE_RECOGNITION:
                return String.format(Locale.ROOT, "candidate %s %s %d/%d",
                        fingerprintText(), selectedCandidates(), streak, requiredStreak);
            case STABLE_RECOGNIZED:
                return String.format(Locale.ROOT, "STABLE %s %s evidence=%.4f",
                        fingerprintText(), selectedCandidates(), confidence());
            default:
                throw new IllegalStateException("Unhandled state " + state);
        }
    }

    /**
     * Change key for watch-mode printing: two statuses with the same signature are the same
     * reported event and must not be printed twice. Evidence and uncertainty reasons are
     * deliberately excluded, so a continuously recognized puzzle prints once.
     */
    public String signature() {
        StringBuilder text = new StringBuilder(state.name());
        fingerprint().ifPresent(id -> text.append('|').append(id));
        if (!selectedCandidates().isEmpty()) {
            text.append('|').append(selectedCandidates());
        }
        if (state == LiveRecognitionState.CANDIDATE_RECOGNITION) {
            text.append('|').append(streak);
        }
        if (message != null) {
            text.append('|').append(message);
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    private String fingerprintText() {
        return fingerprint().map(FingerprintId::name).orElse("(none)");
    }

    private double confidence() {
        return decision == null ? 0.0 : decision.result().confidence();
    }

    private static RecognitionDecision requireRecognized(RecognitionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (decision.result().status() != RecognitionResult.Status.RECOGNIZED) {
            throw new IllegalArgumentException(
                    "A recognized status needs a RECOGNIZED decision, got "
                            + decision.result().status());
        }
        return decision;
    }

    private static void requireStreak(int streak, int requiredStreak) {
        if (streak < 1) {
            throw new IllegalArgumentException("Streak must be positive, got " + streak);
        }
        if (requiredStreak < 1) {
            throw new IllegalArgumentException(
                    "Required streak must be positive, got " + requiredStreak);
        }
    }

    private static String requireMessage(String message) {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("Diagnostic message must not be blank");
        }
        return message;
    }
}
