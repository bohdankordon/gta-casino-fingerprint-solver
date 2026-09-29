package io.github.bohdankordon.casinofingerprint.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.win32.WindowsEmergencyAbort;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsSendInputSink;
import io.github.bohdankordon.casinofingerprint.input.win32.Win32Support;
import org.junit.jupiter.api.Test;

/**
 * Windows input contracts that hold on every OS: the game-control key mapping and the
 * non-Windows refusal. No test here emits OS keyboard input or touches a native library:
 * mapping assertions are pure data, and construction on Linux must refuse.
 */
class WindowsInputContractTest {
    @Test
    void gameControlMappingMatchesTheProjectContract() {
        assertEquals(0x26, WindowsSendInputSink.virtualKey(GameControl.UP), "UP is arrow-up");
        assertEquals(0x28, WindowsSendInputSink.virtualKey(GameControl.DOWN), "DOWN is arrow-down");
        assertEquals(0x25, WindowsSendInputSink.virtualKey(GameControl.LEFT), "LEFT is arrow-left");
        assertEquals(0x27, WindowsSendInputSink.virtualKey(GameControl.RIGHT),
                "RIGHT is arrow-right");
        assertEquals(0x0D, WindowsSendInputSink.virtualKey(GameControl.SELECT),
                "SELECT is Enter");
        assertEquals(0x09, WindowsSendInputSink.virtualKey(GameControl.PROCEED),
                "PROCEED is Tab");
    }

    @Test
    void defaultAbortKeyIsF12() {
        assertEquals(0x7B, WindowsEmergencyAbort.DEFAULT_ABORT_KEY, "F12");
    }

    @Test
    void windowsBackendsRefuseOnNonWindows() {
        if (Win32Support.isWindows()) {
            assertTrue(true, "Windows hosts construct the backends (no native call in ctors)");
            return;
        }
        assertThrows(IllegalStateException.class, WindowsSendInputSink::new, "input refuses");
        assertThrows(IllegalStateException.class, WindowsForegroundTargetGuard::new,
                "foreground guard refuses");
        assertThrows(IllegalStateException.class, WindowsEmergencyAbort::new, "abort refuses");
    }
}
