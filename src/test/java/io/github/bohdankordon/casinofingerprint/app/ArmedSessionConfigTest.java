package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import org.junit.jupiter.api.Test;

/**
 * The armed GUI configuration converts to exactly the validated backend
 * options: same monitor, target, abort key and production watch defaults.
 */
class ArmedSessionConfigTest {
    @Test
    void convertsToValidatedBackendOptions() {
        ArmedSessionConfig config =
                new ArmedSessionConfig(1, "GTA5_Enhanced.exe", EmergencyAbortKey.F4);
        LiveSolverOptions options = config.toLiveSolverOptions();
        assertEquals(Integer.valueOf(1), options.monitorIndex());
        assertEquals(LiveSolverOptions.Mode.WATCH, options.mode());
        assertTrue(options.inputEnabled());
        assertEquals("GTA5_Enhanced.exe", options.targetExecutable());
        assertEquals(EmergencyAbortKey.F4, options.abortKey());
        assertEquals(LiveSolverOptions.DEFAULT_INTERVAL_MILLIS, options.intervalMillis());
        assertEquals(LiveSolverOptions.DEFAULT_STABLE_FRAMES, options.stableFrames());
    }

    @Test
    void targetExecutableIsStripped() {
        ArmedSessionConfig config =
                new ArmedSessionConfig(0, "  GTA5_Enhanced.exe  ", EmergencyAbortKey.F4);
        assertEquals("GTA5_Enhanced.exe", config.targetExecutable());
    }

    @Test
    void negativeMonitorIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ArmedSessionConfig(-1, "GTA5_Enhanced.exe",
                        EmergencyAbortKey.F4));
    }

    @Test
    void blankTargetIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ArmedSessionConfig(0, "   ", EmergencyAbortKey.F4));
    }

    @Test
    void missingAbortKeyIsRefused() {
        assertThrows(NullPointerException.class,
                () -> new ArmedSessionConfig(0, "GTA5_Enhanced.exe", null));
    }
}
