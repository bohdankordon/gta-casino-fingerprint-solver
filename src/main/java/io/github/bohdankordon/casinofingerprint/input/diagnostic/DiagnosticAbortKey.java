package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The small documented set of keys the Stage 8C.2 characterization CLI accepts as its
 * emergency abort key: F1..F24, PAUSE and SCROLL_LOCK -- ordinary physical keys whose Win32
 * virtual-key values are unambiguous ({@code VK_F1}..{@code VK_F24} = 0x70..0x87,
 * {@code VK_PAUSE} = 0x13, {@code VK_SCROLL} = 0x91).
 *
 * <p>The probe never picks an abort key automatically. The real Stage 8C run showed that F12,
 * the old default, is a Steam screenshot shortcut, so the user must choose an explicitly
 * configured key after checking the Steam and GTA bindings.
 *
 * <p>The gameplay controls are deliberately absent: UP, DOWN, LEFT, RIGHT, ENTER (the SELECT
 * key) and TAB (PROCEED) can never be abort keys, in any spelling.
 *
 * <p>{@link #knownConflict()} names the documented shortcut conflict of a key in the user's
 * current Steam/Rockstar setup. The list is not exhaustive of every custom binding; the user's
 * own configuration stays authoritative.
 */
public enum DiagnosticAbortKey {
    F1(0x70, "Rockstar Editor recording shortcut group"),
    F2(0x71, "Rockstar Editor recording shortcut group"),
    F3(0x72, "Rockstar Editor shortcut group (historical/default)"),
    F4(0x73, null),
    F5(0x74, null),
    F6(0x75, null),
    F7(0x76, null),
    F8(0x77, null),
    F9(0x78, "GTA default: drop weapon"),
    F10(0x79, "GTA default: drop ammo; also in the historical Rockstar Editor group"),
    F11(0x7A, "Ctrl+F11 starts manual Steam recording when that mode is enabled"),
    F12(0x7B, "Steam screenshot shortcut; Ctrl+F12 is the Steam Game Recording marker"),
    F13(0x7C, null),
    F14(0x7D, null),
    F15(0x7E, null),
    F16(0x7F, null),
    F17(0x80, null),
    F18(0x81, null),
    F19(0x82, null),
    F20(0x83, null),
    F21(0x84, null),
    F22(0x85, null),
    F23(0x86, null),
    F24(0x87, null),
    PAUSE(0x13, null),
    SCROLL_LOCK(0x91, null);

    /** The supported symbolic names, for usage and refusal messages. */
    public static final String SYMBOLS = "F1..F24, PAUSE, SCROLL_LOCK";

    /** Spellings that overlap a gameplay control under test and can never be abort keys. */
    private static final Set<String> RESERVED = Set.of("UP", "DOWN", "LEFT", "RIGHT", "ENTER",
            "RETURN", "SELECT", "TAB", "PROCEED");

    private final int virtualKeyCode;
    private final String knownConflict;

    DiagnosticAbortKey(int virtualKeyCode, String knownConflict) {
        this.virtualKeyCode = virtualKeyCode;
        this.knownConflict = knownConflict;
    }

    /** The Win32 virtual-key code polled for this key. */
    public int virtualKeyCode() {
        return virtualKeyCode;
    }

    /** The symbolic CLI name; the enum constant name. */
    public String symbolicName() {
        return name();
    }

    /** The documented shortcut conflict of this key, when it has one. */
    public Optional<String> knownConflict() {
        return Optional.ofNullable(knownConflict);
    }

    /**
     * Parses one symbolic abort-key name, ignoring case.
     *
     * @throws IllegalArgumentException for a missing name, for any spelling of a gameplay
     *         control (never an abort key) and for every unknown name
     */
    public static DiagnosticAbortKey parse(String name) {
        if (name == null) {
            throw new IllegalArgumentException("--abort-key is required");
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (RESERVED.contains(upper)) {
            throw new IllegalArgumentException("REFUSED ABORT KEY: " + upper + " overlaps a"
                    + " gameplay control under test and can never abort this probe");
        }
        for (DiagnosticAbortKey key : values()) {
            if (key.name().equals(upper)) {
                return key;
            }
        }
        throw new IllegalArgumentException("unknown abort key \"" + name + "\": supported abort"
                + " keys are " + SYMBOLS);
    }
}
