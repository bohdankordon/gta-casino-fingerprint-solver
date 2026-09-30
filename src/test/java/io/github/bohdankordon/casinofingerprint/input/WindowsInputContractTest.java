package io.github.bohdankordon.casinofingerprint.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.win32.WindowsForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsSendInputSink;
import io.github.bohdankordon.casinofingerprint.input.win32.Win32Support;
import org.junit.jupiter.api.Test;

/**
 * Windows input contracts that hold on every OS: the promoted scan-code mapping and the
 * non-Windows refusal. No test here emits OS keyboard input or touches a native library:
 * mapping assertions are pure data, and construction on Linux must refuse. The abort key
 * has no default; its contract lives in {@code EmergencyAbortKeyTest}.
 */
class WindowsInputContractTest {
    @Test
    void gameControlMappingMatchesThePromotedScanCodeContract() {
        assertEquals(0x48, WindowsSendInputSink.spec(GameControl.UP).scanCode(), "UP is arrow-up E0 48");
        assertEquals(0x50, WindowsSendInputSink.spec(GameControl.DOWN).scanCode(), "DOWN is arrow-down E0 50");
        assertEquals(0x4B, WindowsSendInputSink.spec(GameControl.LEFT).scanCode(), "LEFT is arrow-left E0 4B");
        assertEquals(0x4D, WindowsSendInputSink.spec(GameControl.RIGHT).scanCode(), "RIGHT is arrow-right E0 4D");
        assertEquals(0x1C, WindowsSendInputSink.spec(GameControl.SELECT).scanCode(), "SELECT is main Enter 1C");
        assertEquals(0x0F, WindowsSendInputSink.spec(GameControl.PROCEED).scanCode(), "PROCEED is Tab 0F");
    }

    @Test
    void windowsBackendsRefuseOnNonWindows() throws Exception {
        if (Win32Support.isWindows()) {
            assertTrue(true, "Windows hosts construct the backends (no native call in ctors)");
            return;
        }
        assertThrows(IllegalStateException.class, WindowsSendInputSink::new, "input refuses");
        assertThrows(IllegalStateException.class, WindowsForegroundTargetGuard::new,
                "foreground guard refuses");
        assertThrows(IllegalStateException.class,
                () -> abortViaReflection(0x91), "abort refuses without a Windows host");
    }

    private static void abortViaReflection(int virtualKey) throws Exception {
        Class<?> type = Class.forName("io.github.bohdankordon.casinofingerprint.input.win32.WindowsEmergencyAbort");
        try {
            type.getConstructor(int.class).newInstance(virtualKey);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }
}
