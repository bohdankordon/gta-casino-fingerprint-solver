package io.github.bohdankordon.casinofingerprint.input.diagnostic;

/**
 * One of the four Stage 8C.2 input-delivery characterization modes: the delivery shape of ONE
 * harmless key tap. A mode never selects a control and never exposes Tab; the control stays an
 * independent, separately validated choice.
 *
 * <p>The four shapes exist to separate the open hypotheses about why the current production
 * batch produced no visible GTA reaction: a zero-duration down/up pair that the game never
 * samples, physical scan-code semantics, extended-key representation, or a normal-runtime
 * filter. All four remain ordinary user-mode Windows keyboard input: no window messages, no
 * hooks, no drivers, no process injection and no anti-cheat interaction.
 *
 * <ul>
 *   <li>{@link #VK_BATCH}: virtual-key key-down plus key-up in ONE native batch with no hold;
 *       this is the exact current production backend semantics and the untouched baseline.</li>
 *   <li>{@link #VK_HOLD}: virtual-key key-down, an explicit hold, virtual-key key-up as
 *       separate native submissions.</li>
 *   <li>{@link #SCANCODE_BATCH}: scan-code key-down plus key-up in ONE native batch.</li>
 *   <li>{@link #SCANCODE_HOLD}: scan-code key-down, an explicit hold, scan-code key-up as
 *       separate native submissions.</li>
 * </ul>
 */
public enum DiagnosticInputDeliveryMode {
    /** Virtual-key key-down plus key-up in one batch, no hold: the exact production baseline. */
    VK_BATCH(false, false,
            "virtual-key key-down + key-up in ONE batch, no hold (the exact production batch)"),
    /** Virtual-key key-down, hold, virtual-key key-up: separate native submissions. */
    VK_HOLD(false, true,
            "virtual-key key-down, hold, virtual-key key-up as separate submissions"),
    /** Scan-code key-down plus key-up in one batch: no hold and no sleep. */
    SCANCODE_BATCH(true, false, "scan-code key-down + key-up in ONE batch, no hold"),
    /** Scan-code key-down, hold, scan-code key-up: separate native submissions. */
    SCANCODE_HOLD(true, true,
            "scan-code key-down, hold, scan-code key-up as separate submissions");

    /** The accepted mode names, exactly once each, for usage and refusal messages. */
    public static final String NAMES = "VK_BATCH, VK_HOLD, SCANCODE_BATCH, SCANCODE_HOLD";

    private final boolean scanCodes;
    private final boolean hold;
    private final String description;

    DiagnosticInputDeliveryMode(boolean scanCodes, boolean hold, String description) {
        this.scanCodes = scanCodes;
        this.hold = hold;
        this.description = description;
    }

    /** True when the mode describes keys by Set-1 scan code instead of by virtual key. */
    public boolean usesScanCodes() {
        return scanCodes;
    }

    /** True when key-down and key-up are separate submissions with an explicit hold between. */
    public boolean holds() {
        return hold;
    }

    /** True when the whole tap is one native batch with no hold and no sleep. */
    public boolean isBatch() {
        return !hold;
    }

    /** Short human-readable shape of the mode, used by the CLI banners and usage text. */
    public String description() {
        return description;
    }

    /**
     * Parses one mode name.
     *
     * @throws IllegalArgumentException for a missing name, for any spelling of PROCEED (a
     *         gameplay control name that must never select a mode) and for every unknown name
     */
    public static DiagnosticInputDeliveryMode parse(String name) {
        if (name == null) {
            throw new IllegalArgumentException("--delivery-mode is required");
        }
        if (name.equalsIgnoreCase("PROCEED")) {
            throw new IllegalArgumentException("UNSUPPORTED DELIVERY MODE: PROCEED is a gameplay"
                    + " control, not a delivery mode, and Tab is never sent by Stage 8C");
        }
        return switch (name) {
            case "VK_BATCH" -> VK_BATCH;
            case "VK_HOLD" -> VK_HOLD;
            case "SCANCODE_BATCH" -> SCANCODE_BATCH;
            case "SCANCODE_HOLD" -> SCANCODE_HOLD;
            default -> throw new IllegalArgumentException("UNSUPPORTED DELIVERY MODE: unknown"
                    + " --delivery-mode \"" + name + "\": allowed modes are " + NAMES);
        };
    }
}
