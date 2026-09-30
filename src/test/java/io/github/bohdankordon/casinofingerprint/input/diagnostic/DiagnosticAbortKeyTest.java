package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.2 configurable abort key: ordinary physical keys with unambiguous virtual-key values,
 * never a gameplay control, and documented Steam/Rockstar conflicts. Pure data only.
 */
class DiagnosticAbortKeyTest {
    @Test
    void functionKeysUseTheContiguousVirtualKeyBlock() {
        assertEquals(0x70, DiagnosticAbortKey.F1.virtualKeyCode(), "VK_F1");
        assertEquals(0x7B, DiagnosticAbortKey.F12.virtualKeyCode(), "VK_F12");
        assertEquals(0x87, DiagnosticAbortKey.F24.virtualKeyCode(), "VK_F24");
        for (int index = 1; index <= 24; index++) {
            assertEquals(0x6F + index, DiagnosticAbortKey.valueOf("F" + index).virtualKeyCode(),
                    "VK_F" + index + " must be contiguous");
        }
    }

    @Test
    void pauseAndScrollLockUseTheirUnambiguousVirtualCodes() {
        assertEquals(0x13, DiagnosticAbortKey.PAUSE.virtualKeyCode(), "VK_PAUSE");
        assertEquals(0x91, DiagnosticAbortKey.SCROLL_LOCK.virtualKeyCode(), "VK_SCROLL");
    }

    @Test
    void everySymbolicNameParsesIgnoringCase() {
        for (DiagnosticAbortKey key : DiagnosticAbortKey.values()) {
            assertEquals(key, DiagnosticAbortKey.parse(key.symbolicName()), key + " parses");
            assertEquals(key, DiagnosticAbortKey.parse(key.symbolicName().toLowerCase(Locale.ROOT)),
                    key + " parses ignoring case");
        }
    }

    @Test
    void controlsCanNeverBeAbortKeys() {
        for (String reserved : List.of("UP", "DOWN", "LEFT", "RIGHT", "ENTER", "RETURN", "SELECT",
                "TAB", "PROCEED", "up", "Tab", "enter")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DiagnosticAbortKey.parse(reserved), reserved + " must be refused");
            assertTrue(error.getMessage().contains("REFUSED ABORT KEY"),
                    reserved + ": " + error.getMessage());
        }
    }

    @Test
    void noAbortKeyOverlapsAControlVirtualKey() {
        List<Integer> controlVirtualKeys = List.of(0x25, 0x26, 0x27, 0x28, 0x0D, 0x09);
        for (DiagnosticAbortKey key : DiagnosticAbortKey.values()) {
            assertFalse(controlVirtualKeys.contains(key.virtualKeyCode()),
                    key + " must never be a gameplay control");
        }
    }

    @Test
    void unknownAbortKeysAreRefusedWithTheSupportedList() {
        for (String unknown : List.of("HOME", "SPACE", "A", "F25", "F0", "")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DiagnosticAbortKey.parse(unknown), unknown + " must be refused");
            assertTrue(error.getMessage().contains("F1..F24"),
                    unknown + ": " + error.getMessage());
        }
    }

    @Test
    void documentedConflictsNameTheRealShortcuts() {
        assertTrue(DiagnosticAbortKey.F12.knownConflict().orElseThrow().contains("Steam screenshot"),
                "F12 is the Steam screenshot key");
        assertTrue(DiagnosticAbortKey.F11.knownConflict().orElseThrow().contains("Steam"),
                "F11 is the manual Steam recording modifier");
        assertTrue(DiagnosticAbortKey.F9.knownConflict().orElseThrow().contains("drop weapon"),
                "F9 is the GTA drop-weapon default");
        assertTrue(DiagnosticAbortKey.F10.knownConflict().orElseThrow().contains("drop ammo"),
                "F10 is the GTA drop-ammo default");
        for (DiagnosticAbortKey rockstar : List.of(DiagnosticAbortKey.F1, DiagnosticAbortKey.F2,
                DiagnosticAbortKey.F3)) {
            assertTrue(rockstar.knownConflict().orElseThrow().contains("Rockstar Editor"),
                    rockstar + " is a Rockstar Editor shortcut");
        }
    }

    @Test
    void unconflictedAbortKeysReportNoConflict() {
        for (DiagnosticAbortKey key : List.of(DiagnosticAbortKey.F4, DiagnosticAbortKey.F5,
                DiagnosticAbortKey.F8, DiagnosticAbortKey.F13, DiagnosticAbortKey.F24,
                DiagnosticAbortKey.PAUSE, DiagnosticAbortKey.SCROLL_LOCK)) {
            assertTrue(key.knownConflict().isEmpty(), key + " has no documented conflict");
        }
    }
}
