package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Live-input options stay strictly opt-in: parsing pins every refusal. */
class LiveSolverOptionsTest {
    @Test
    void watchWithoutOptInIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> LiveSolverOptions.parse(new String[] {"--watch"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input"}));
    }

    @Test
    void targetWithoutOptInIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--target-exe", "GTA5.exe"}));
    }

    @Test
    void fullLiveInvocationParses() {
        LiveSolverOptions options = LiveSolverOptions.parse(new String[] {"--watch",
                "--enable-input", "--target-exe", "GTA5.exe"});
        assertEquals(LiveSolverOptions.Mode.WATCH, options.mode());
        assertTrue(options.inputEnabled());
        assertEquals("GTA5.exe", options.targetExecutable());
        assertFalse(options.help());
    }

    @Test
    void unknownFlagsContradictionsAndRangesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LiveSolverOptions.parse(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--list-monitors", "--watch", "--enable-input", "--target-exe",
                        "GTA5.exe"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--target-exe", "GTA5.exe",
                        "--stable-frames", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> LiveSolverOptions.parse(new String[] {"--solve-now"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--list-monitors", "--enable-input", "--target-exe", "GTA5.exe"}));
    }

    @Test
    void usageDocumentsTheSafetyContract() {
        String usage = LiveSolverOptions.usage();
        assertTrue(usage.contains("--enable-input"), "opt-in flag");
        assertTrue(usage.contains("--target-exe"), "target executable");
        assertTrue(usage.contains("F12"), "abort key");
    }
}
