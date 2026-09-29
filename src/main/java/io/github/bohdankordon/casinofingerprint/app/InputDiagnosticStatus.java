package io.github.bohdankordon.casinofingerprint.app;

/**
 * Outcome of one Stage 8C.1 single-tap input diagnostic run.
 *
 * <p>Exactly one status is produced per invocation. Two questions are deliberately kept apart,
 * because "the tap did not complete" is NOT the same as "no input was sent":
 *
 * <ul>
 *   <li>{@link #inputAttempted()} asks whether the single allowed tap invocation was reached.
 *       It is false for every refusal that happens BEFORE the tap, and those statuses therefore
 *       guarantee zero native input. It is true for {@link #SENT} and for
 *       {@link #INPUT_ERROR}.</li>
 *   <li>{@link #zeroInputGuaranteed()} asks whether the status proves that no native input
 *       event was emitted. It is true only for the pre-tap refusals, never for {@link #SENT}
 *       and never for {@link #INPUT_ERROR}.</li>
 * </ul>
 *
 * <p>{@link #SENT} means the one explicitly requested tap was submitted to the input sink and
 * the backend reported a complete tap. {@link #INPUT_ERROR} means the tap WAS attempted and
 * complete delivery was NOT confirmed: the production backend submits key-down plus key-up as
 * one native batch call, so a partially delivered batch may already have delivered a real key
 * event to the foreground application. The labels are the exact user-facing codes printed by
 * the CLI, and no status ever claims that GTA reacted to a tap.
 */
public enum InputDiagnosticStatus {
    /** Exactly one complete tap was submitted to the pinned foreground target. */
    SENT("SENT", true),
    /** The explicit {@code --enable-input} opt-in was missing: nothing was sent. */
    INPUT_DISABLED("INPUT DISABLED", false),
    /** The F12 emergency abort was active, or the countdown was interrupted: nothing was sent. */
    ABORTED("ABORTED", false),
    /** No foreground window belonged to the required executable: nothing was sent. */
    TARGET_NOT_FOREGROUND("TARGET NOT FOREGROUND", false),
    /** The pinned target no longer owned the foreground window: nothing was sent. */
    FOCUS_LOST("FOCUS LOST", false),
    /** The requested control is not allowed in Stage 8C (PROCEED/Tab or unknown): nothing sent. */
    UNSUPPORTED_CONTROL("UNSUPPORTED CONTROL", false),
    /** Not on Windows: no native input backend exists and nothing was sent. */
    NON_WINDOWS("NON-WINDOWS", false),
    /**
     * The single tap WAS attempted and complete delivery was not confirmed: partial native
     * input may already have been delivered, nothing is retried and no second tap is attempted.
     */
    INPUT_ERROR("INPUT ERROR", true);

    private final String label;
    private final boolean inputAttempted;

    InputDiagnosticStatus(String label, boolean inputAttempted) {
        this.label = label;
        this.inputAttempted = inputAttempted;
    }

    /** The exact user-facing refusal or result label. */
    public String label() {
        return label;
    }

    /**
     * True when this status is only reachable AFTER the one allowed tap invocation.
     *
     * @see #zeroInputGuaranteed()
     */
    public boolean inputAttempted() {
        return inputAttempted;
    }

    /**
     * True only when this status proves that no native input event was emitted, which is the
     * case for every pre-tap refusal and never for {@link #SENT} or {@link #INPUT_ERROR}.
     */
    public boolean zeroInputGuaranteed() {
        return !inputAttempted;
    }
}
