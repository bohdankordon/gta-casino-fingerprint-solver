package io.github.bohdankordon.casinofingerprint.app;

/**
 * Outcome of one Stage 8C.1 single-tap input diagnostic run.
 *
 * <p>Exactly one status is produced per invocation: {@link #SENT} means the one explicitly
 * requested tap was handed to the input sink after every gate passed; every other status is a
 * refusal that sent zero input. The labels are the exact user-facing codes printed by the CLI,
 * and no status ever claims that GTA reacted to a tap.
 */
public enum InputDiagnosticStatus {
    /** Exactly one complete tap was submitted to the pinned foreground target. */
    SENT("SENT"),
    /** The explicit {@code --enable-input} opt-in was missing: nothing was sent. */
    INPUT_DISABLED("INPUT DISABLED"),
    /** The F12 emergency abort was active, or the countdown was interrupted. */
    ABORTED("ABORTED"),
    /** No foreground window belonged to the required executable after the countdown. */
    TARGET_NOT_FOREGROUND("TARGET NOT FOREGROUND"),
    /** The pinned target no longer owned the foreground window before the tap. */
    FOCUS_LOST("FOCUS LOST"),
    /** The requested control is not allowed in Stage 8C (PROCEED/Tab or unknown). */
    UNSUPPORTED_CONTROL("UNSUPPORTED CONTROL"),
    /** Not running on Windows: native gameplay input is never constructed or sent. */
    NON_WINDOWS("NON-WINDOWS"),
    /** The tap backend failed; the tap is never retried and never repeated. */
    INPUT_ERROR("INPUT ERROR");

    private final String label;

    InputDiagnosticStatus(String label) {
        this.label = label;
    }

    /** The exact user-facing refusal or result label. */
    public String label() {
        return label;
    }
}
