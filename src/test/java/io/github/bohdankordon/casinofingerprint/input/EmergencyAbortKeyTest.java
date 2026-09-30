package io.github.bohdankordon.casinofingerprint.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.4 production abort key: ordinary physical keys with unambiguous virtual-key
 * values, never a gameplay control, and documented Steam/Rockstar conflicts. Pure data
 * only, no native call.
 */
class EmergencyAbortKeyTest {
    @Test
    void functionKeysUseTheContiguousVirtualKeyBlock() {
        assertEquals(0x70, EmergencyAbortKey.F1.virtualKeyCode());
        assertEquals(0x7B, EmergencyAbortKey.F12.virtualKeyCode());
        assertEquals(0x87, EmergencyAbortKey.F24.virtualKeyCode());
        for (int index = 1; index <= 24; index++) {
            assertEquals(0x6F + index, EmergencyAbortKey.valueOf("F" + index).virtualKeyCode());
        }
    }

    @Test
    void pauseAndScrollLockUseTheirUnambiguousVirtualCodes() {
        assertEquals(0x13, EmergencyAbortKey.PAUSE.virtualKeyCode());
        assertEquals(0x91, EmergencyAbortKey.SCROLL_LOCK.virtualKeyCode());
    }

    @Test
    void everySymbolicNameParsesIgnoringCase() {
        for (EmergencyAbortKey key : EmergencyAbortKey.values()) {
            assertEquals(key, EmergencyAbortKey.parse(key.symbolicName()));
            assertEquals(key, EmergencyAbortKey.parse(key.symbolicName().toLowerCase(Locale.ROOT)));
        }
    }

    @Test
    void scrollLockParsesForTheValidatedSmokeInvocation() {
        assertEquals(EmergencyAbortKey.SCROLL_LOCK, EmergencyAbortKey.parse("SCROLL_LOCK"));
        assertEquals(0x91, EmergencyAbortKey.parse("SCROLL_LOCK").virtualKeyCode());
    }

    @Test
    void controlsCanNeverBeAbortKeys() {
        for (String reserved : List.of("UP", "DOWN", "LEFT", "RIGHT", "ENTER", "RETURN", "SELECT",
                "TAB", "PROCEED", "up", "Tab", "enter")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> EmergencyAbortKey.parse(reserved));
            assertTrue(error.getMessage().contains("REFUSED ABORT KEY"), error.getMessage());
        }
    }

    @Test
    void noAbortKeyOverlapsAControlVirtualKey() {
        List<Integer> controlVirtualKeys = List.of(0x25, 0x26, 0x27, 0x28, 0x0D, 0x09);
        for (EmergencyAbortKey key : EmergencyAbortKey.values()) {
            assertFalse(controlVirtualKeys.contains(key.virtualKeyCode()));
        }
    }

    @Test
    void unknownAbortKeysAreRefusedWithTheSupportedList() {
        for (String unknown : List.of("HOME", "SPACE", "A", "F25", "F0", "")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> EmergencyAbortKey.parse(unknown));
            assertTrue(error.getMessage().contains("F1..F24"), error.getMessage());
        }
    }

    @Test
    void documentedConflictsNameTheRealShortcuts() {
        assertTrue(EmergencyAbortKey.F12.knownConflict().orElseThrow().contains("Steam screenshot"));
        assertTrue(EmergencyAbortKey.F11.knownConflict().orElseThrow().contains("Steam"));
        assertTrue(EmergencyAbortKey.F9.knownConflict().orElseThrow().contains("drop weapon"));
        assertTrue(EmergencyAbortKey.F10.knownConflict().orElseThrow().contains("drop ammo"));
        for (EmergencyAbortKey rockstar : List.of(EmergencyAbortKey.F1, EmergencyAbortKey.F2,
                EmergencyAbortKey.F3)) {
            assertTrue(rockstar.knownConflict().orElseThrow().contains("Rockstar Editor"));
        }
    }

    @Test
    void unconflictedAbortKeysReportNoConflict() {
        for (EmergencyAbortKey key : List.of(EmergencyAbortKey.F4, EmergencyAbortKey.F5,
                EmergencyAbortKey.F8, EmergencyAbortKey.F13, EmergencyAbortKey.F24,
                EmergencyAbortKey.PAUSE, EmergencyAbortKey.SCROLL_LOCK)) {
            assertTrue(key.knownConflict().isEmpty());
        }
    }
}
