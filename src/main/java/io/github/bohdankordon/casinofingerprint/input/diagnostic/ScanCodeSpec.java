package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import java.util.Objects;

/**
 * One physical key as a Windows Set-1 (MF-II / PC/AT) make code plus its extended-key bit.
 *
 * <p>With {@code KEYEVENTF_SCANCODE} the {@code wScan} member identifies the key physically.
 * Extended keys are the ones whose full Set-1 sequence carries the {@code 0xE0} prefix; only
 * the byte after the prefix belongs in {@code wScan}, and the prefix itself is represented by
 * {@code KEYEVENTF_EXTENDEDKEY}. Arrow-up, for example, is {@code E0 48}, so it is the pair
 * (0x48, extended) and never a two-byte 0xE048 value.
 *
 * <p>Mapping rationale for the five Stage 8C controls:
 *
 * <ul>
 *   <li>UP, DOWN, LEFT and RIGHT are the arrow cluster: make codes 0x48, 0x50, 0x4B and 0x4D,
 *       every one of them an extended ({@code E0}) key;</li>
 *   <li>SELECT is the MAIN keyboard Enter: make code 0x1C, not extended. The numeric-keypad
 *       Enter shares the 0x1C make code but carries the {@code E0} prefix, so the probe must
 *       press the ordinary main Enter -- the same key the production virtual-key mapping means
 *       by {@code VK_RETURN}.</li>
 * </ul>
 *
 * <p>PROCEED has no general mapping: Tab is never sent by the Stage 8C.1 single-tap
 * diagnostic or by the Stage 8C.2 characterization probe. The dedicated Stage 8C.3 proceed
 * probe owns the only Tab mapping through {@link #tabForProceedProbe()}, so the general
 * {@link #forControl(GameControl)} refusal stays the ban that every other path enforces.
 *
 * @param scanCode the Set-1 make code without any {@code E0} prefix
 * @param extended true when the physical key carries the {@code E0} extended prefix
 */
public record ScanCodeSpec(int scanCode, boolean extended) {
    /** Arrow-up make code (E0 48). */
    public static final int ARROW_UP = 0x48;
    /** Arrow-down make code (E0 50). */
    public static final int ARROW_DOWN = 0x50;
    /** Arrow-left make code (E0 4B). */
    public static final int ARROW_LEFT = 0x4B;
    /** Arrow-right make code (E0 4D). */
    public static final int ARROW_RIGHT = 0x4D;
    /** Main keyboard Enter make code (1C); the keypad Enter is its E0 variant. */
    public static final int MAIN_ENTER = 0x1C;
    /**
     * Main keyboard Tab make code (0F), non-extended. This constant belongs to the
     * dedicated Stage 8C.3 proceed probe only; it is never reachable through
     * {@link #forControl(GameControl)}.
     */
    public static final int TAB_PROCEED = 0x0F;

    public ScanCodeSpec {
        if (scanCode < 0 || scanCode > 0xFF) {
            throw new IllegalArgumentException("a Set-1 scan code is one byte, got " + scanCode);
        }
    }

    /**
     * The Set-1 representation of one allowed Stage 8C control.
     *
     * @throws IllegalArgumentException for PROCEED (Tab is never sent)
     */
    public static ScanCodeSpec forControl(GameControl control) {
        Objects.requireNonNull(control, "control");
        return switch (control) {
            case UP -> new ScanCodeSpec(ARROW_UP, true);
            case DOWN -> new ScanCodeSpec(ARROW_DOWN, true);
            case LEFT -> new ScanCodeSpec(ARROW_LEFT, true);
            case RIGHT -> new ScanCodeSpec(ARROW_RIGHT, true);
            case SELECT -> new ScanCodeSpec(MAIN_ENTER, false);
            case PROCEED -> throw new IllegalArgumentException("PROCEED is forbidden in Stage 8C:"
                    + " Tab has no characterization mapping and is never sent");
        };
    }

    /**
     * The Set-1 Tab representation for the dedicated Stage 8C.3 proceed probe only:
     * make code {@code 0x0F}, non-extended (Tab carries no {@code E0} prefix).
     *
     * <p>This is deliberately a separate method so the general {@link #forControl(GameControl)}
     * mapping keeps refusing PROCEED. Only {@code ProceedDiagnosticPlan} may call it; no
     * general control path, delivery plan or CLI may route PROCEED through here silently.
     *
     * @return the Tab scan-code spec (0x0F, non-extended)
     */
    public static ScanCodeSpec tabForProceedProbe() {
        return new ScanCodeSpec(TAB_PROCEED, false);
    }
}
