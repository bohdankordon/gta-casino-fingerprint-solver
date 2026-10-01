package io.github.bohdankordon.casinofingerprint.app;

/**
 * High-level operator states shown in the Stage 9B desktop UI.
 *
 * <p>The UI communicates every state with both text and a visual treatment, never
 * with color alone. ARMED is only ever displayed while the live backend worker is
 * actually running; DISARMED is never displayed after input ownership has begun.
 */
public enum OperatorSessionState {
    /** Initial state of every launch. Zero input has been sent and none can be sent. */
    DISARMED("DISARMED", "neutral",
            "No input. The application starts here on every launch."),
    /** The live backend worker is running with verified gameplay input enabled. */
    ARMED_WATCHING("ARMED / WATCHING", "armed",
            "Live input is armed and the solver is watching the game."),
    /** Orderly shutdown was requested and the worker has not finished yet. */
    STOPPING("STOPPING", "progress",
            "Orderly shutdown requested. Waiting for the worker to stop."),
    /** The armed session stopped normally after an orderly stop request. */
    STOPPED("STOPPED", "neutral",
            "The session stopped normally."),
    /** The armed session ended with a latched fault. No further input will be sent. */
    FAULTED("FAULTED", "error",
            "The session ended with a fault. No further input will be sent."),
    /** The configured physical emergency abort key stopped gameplay input. */
    ABORTED("ABORTED", "error",
            "The emergency abort key stopped gameplay input.");

    private final String badge;
    private final String tone;
    private final String description;

    OperatorSessionState(String badge, String tone, String description) {
        this.badge = badge;
        this.tone = tone;
        this.description = description;
    }

    /** Short badge text shown in the main window header. */
    public String badge() {
        return badge;
    }

    /** Visual tone hint (neutral, armed, progress, error); always paired with text. */
    public String tone() {
        return tone;
    }

    /** One-line description shown next to the badge. */
    public String description() {
        return description;
    }

    /** True for STOPPED, FAULTED and ABORTED: the session ended and re-arm is locked. */
    public boolean isTerminal() {
        return this == STOPPED || this == FAULTED || this == ABORTED;
    }

    /** True while the live backend worker owns input (ARMED_WATCHING, STOPPING). */
    public boolean isArmed() {
        return this == ARMED_WATCHING || this == STOPPING;
    }
}
