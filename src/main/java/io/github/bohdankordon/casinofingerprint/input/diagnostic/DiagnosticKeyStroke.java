package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import java.util.Objects;

/**
 * One pure description of a single native keyboard event: exactly the {@code KEYBDINPUT}
 * members {@code wVk}, {@code wScan} and {@code dwFlags}. Nothing here calls Windows, so the
 * exact bytes the diagnostic would submit are pinned by CI.
 *
 * <p>The Win32 contract: without {@code KEYEVENTF_SCANCODE} the event is identified by
 * {@code wVk} and {@code wScan} stays zero; with {@code KEYEVENTF_SCANCODE} the event is
 * identified by {@code wScan}, {@code wVk} is ignored and must stay zero, and extended keys
 * additionally set {@code KEYEVENTF_EXTENDEDKEY}. The record enforces both invariants, so an
 * invalid mixture cannot be constructed.
 *
 * @param wVk virtual-key code; zero for scan-code strokes
 * @param wScan Set-1 scan code; zero for virtual-key strokes
 * @param flags the {@code KEYEVENTF_} flags of the event
 */
public record DiagnosticKeyStroke(int wVk, int wScan, int flags) {
    /** {@code KEYEVENTF_EXTENDEDKEY}: the key carries the E0 scan-code prefix. */
    public static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    /** {@code KEYEVENTF_KEYUP}: the key is being released. */
    public static final int KEYEVENTF_KEYUP = 0x0002;
    /** {@code KEYEVENTF_SCANCODE}: identify the key by scan code instead of virtual key. */
    public static final int KEYEVENTF_SCANCODE = 0x0008;

    public DiagnosticKeyStroke {
        if (wVk < 0 || wVk > 0xFFFF) {
            throw new IllegalArgumentException("wVk is a WORD, got " + wVk);
        }
        if (wScan < 0 || wScan > 0xFF) {
            throw new IllegalArgumentException("wScan is one byte, got " + wScan);
        }
        int unknown = flags & ~(KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP | KEYEVENTF_SCANCODE);
        if (unknown != 0) {
            throw new IllegalArgumentException(
                    "unsupported keyboard flags: 0x" + Integer.toHexString(unknown));
        }
        if (isScanCode(flags) && wVk != 0) {
            throw new IllegalArgumentException("KEYEVENTF_SCANCODE ignores wVk, which must stay"
                    + " zero for a scan-code stroke, got " + wVk);
        }
        if (!isScanCode(flags) && wScan != 0) {
            throw new IllegalArgumentException("a virtual-key stroke must not carry a scan code,"
                    + " got wScan " + wScan);
        }
    }

    /** True for a key release. */
    public boolean keyUp() {
        return (flags & KEYEVENTF_KEYUP) != 0;
    }

    /** True when the event is identified by scan code. */
    public boolean usesScanCode() {
        return isScanCode(flags);
    }

    /** True when the key carries the E0 extended prefix. */
    public boolean extended() {
        return (flags & KEYEVENTF_EXTENDEDKEY) != 0;
    }

    /** One virtual-key down or up stroke: {@code wScan} zero and no scan-code flag. */
    public static DiagnosticKeyStroke virtualKey(int virtualKey, boolean keyUp) {
        return new DiagnosticKeyStroke(virtualKey, 0, keyUp ? KEYEVENTF_KEYUP : 0);
    }

    /** One scan-code down or up stroke: {@code wVk} zero and the extended bit from the spec. */
    public static DiagnosticKeyStroke scanCode(ScanCodeSpec spec, boolean keyUp) {
        Objects.requireNonNull(spec, "spec");
        int flags = KEYEVENTF_SCANCODE | (spec.extended() ? KEYEVENTF_EXTENDEDKEY : 0)
                | (keyUp ? KEYEVENTF_KEYUP : 0);
        return new DiagnosticKeyStroke(0, spec.scanCode(), flags);
    }

    private static boolean isScanCode(int flags) {
        return (flags & KEYEVENTF_SCANCODE) != 0;
    }
}
