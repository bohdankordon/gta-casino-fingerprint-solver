package io.github.bohdankordon.casinofingerprint.input.win32;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import java.util.Objects;

/**
 * Production scan-code identity of one gameplay control: a Windows Set-1 (MF-II / PC-AT)
 * make code plus its extended-key bit.
 *
 * <p>With {@code KEYEVENTF_SCANCODE} the {@code wScan} member identifies the key
 * physically. Extended keys are the ones whose full Set-1 sequence carries the {@code 0xE0}
 * prefix; only the byte after the prefix belongs in {@code wScan}, and the prefix itself
 * is represented by {@code KEYEVENTF_EXTENDEDKEY}. Arrow-up, for example, is {@code E0 48},
 * so it is the pair (0x48, extended) and never a two-byte 0xE048 value.
 *
 * <p>Validated Stage 8C.2/8C.3 mapping, now the production contract:
 * arrows {@code 0x48}/{@code 0x50}/{@code 0x4B}/{@code 0x4D} extended, main Enter
 * {@code 0x1C} non-extended, main Tab {@code 0x0F} non-extended. The numeric-keypad Enter
 * shares the 0x1C make code but carries the {@code E0} prefix, so production presses the
 * ordinary main Enter.
 *
 * @param scanCode the Set-1 make code without any {@code E0} prefix
 * @param extended true when the physical key carries the {@code E0} extended prefix
 */
public record WindowsGameKeySpec(int scanCode, boolean extended) {
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
    /** Main keyboard Tab make code (0F), non-extended. */
    public static final int TAB_PROCEED = 0x0F;

    public WindowsGameKeySpec {
        if (scanCode < 0 || scanCode > 0xFF) {
            throw new IllegalArgumentException("a Set-1 scan code is one byte, got " + scanCode);
        }
    }

    /**
     * The production Set-1 representation of one gameplay control.
     */
    public static WindowsGameKeySpec forControl(GameControl control) {
        Objects.requireNonNull(control, "control");
        return switch (control) {
            case UP -> new WindowsGameKeySpec(ARROW_UP, true);
            case DOWN -> new WindowsGameKeySpec(ARROW_DOWN, true);
            case LEFT -> new WindowsGameKeySpec(ARROW_LEFT, true);
            case RIGHT -> new WindowsGameKeySpec(ARROW_RIGHT, true);
            case SELECT -> new WindowsGameKeySpec(MAIN_ENTER, false);
            case PROCEED -> new WindowsGameKeySpec(TAB_PROCEED, false);
        };
    }
}
