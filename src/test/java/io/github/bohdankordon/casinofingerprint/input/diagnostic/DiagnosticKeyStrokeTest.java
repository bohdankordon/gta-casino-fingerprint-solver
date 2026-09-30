package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pure {@code KEYBDINPUT} representation enforces the Win32 flag contract structurally:
 * scan-code strokes keep {@code wVk} zero, virtual-key strokes keep {@code wScan} zero, and an
 * unknown flag cannot be constructed. No native call is involved.
 */
class DiagnosticKeyStrokeTest {
    @Test
    void scanCodeStrokesMustKeepTheVirtualKeyZero() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticKeyStroke(0x26, 0x48,
                DiagnosticKeyStroke.KEYEVENTF_SCANCODE));
    }

    @Test
    void virtualKeyStrokesMustNotCarryAScanCode() {
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosticKeyStroke(0x26, 0x48, 0));
    }

    @Test
    void unknownFlagsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticKeyStroke(0x26, 0, 0x0004));
    }

    @Test
    void virtualKeyStrokesMatchTheWin32Representation() {
        DiagnosticKeyStroke down = DiagnosticKeyStroke.virtualKey(0x26, false);
        assertEquals(0x26, down.wVk());
        assertEquals(0, down.wScan(), "no scan code flag means wScan stays zero");
        assertEquals(0, down.flags(), "a plain key-down carries no flags");
        assertFalse(down.keyUp());
        assertFalse(down.extended());
        assertFalse(down.usesScanCode());

        DiagnosticKeyStroke up = DiagnosticKeyStroke.virtualKey(0x26, true);
        assertEquals(DiagnosticKeyStroke.KEYEVENTF_KEYUP, up.flags());
        assertTrue(up.keyUp());
    }

    @Test
    void scanCodeStrokesMatchTheWin32Representation() {
        ScanCodeSpec extended = new ScanCodeSpec(0x48, true);
        DiagnosticKeyStroke down = DiagnosticKeyStroke.scanCode(extended, false);
        assertEquals(0, down.wVk(), "wVk is ignored and stays zero with KEYEVENTF_SCANCODE");
        assertEquals(0x48, down.wScan());
        assertEquals(DiagnosticKeyStroke.KEYEVENTF_SCANCODE
                | DiagnosticKeyStroke.KEYEVENTF_EXTENDEDKEY, down.flags());
        assertTrue(down.usesScanCode());
        assertTrue(down.extended());
        assertFalse(down.keyUp());

        DiagnosticKeyStroke up = DiagnosticKeyStroke.scanCode(extended, true);
        assertEquals(DiagnosticKeyStroke.KEYEVENTF_SCANCODE
                | DiagnosticKeyStroke.KEYEVENTF_EXTENDEDKEY
                | DiagnosticKeyStroke.KEYEVENTF_KEYUP, up.flags());
        assertTrue(up.keyUp());
    }

    @Test
    void aNonExtendedScanCodeStrokeCarriesNoExtendedFlag() {
        DiagnosticKeyStroke stroke = DiagnosticKeyStroke.scanCode(new ScanCodeSpec(0x1C, false),
                false);
        assertEquals(DiagnosticKeyStroke.KEYEVENTF_SCANCODE, stroke.flags());
        assertFalse(stroke.extended(), "main Enter must not carry the E0 semantics");
    }
}
