package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import org.junit.jupiter.api.Test;

/** Live-input options stay strictly opt-in with an explicit abort key: parsing pins every refusal. */
class LiveSolverOptionsTest {
    @Test
    void watchWithoutOptInIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> LiveSolverOptions.parse(new String[] {"--watch"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--target-exe", "GTA5_Enhanced.exe"}));
    }

    @Test
    void targetWithoutOptInIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--target-exe", "GTA5.exe"}));
    }

    @Test
    void liveWatchWithoutAbortKeyIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--target-exe", "GTA5_Enhanced.exe"}));
        assertTrue(error.getMessage().contains("--abort-key"), error.getMessage());
    }

    @Test
    void fullLiveInvocationParsesWithScrollLock() {
        LiveSolverOptions options = LiveSolverOptions.parse(new String[] {"--watch",
                "--enable-input", "--target-exe", "GTA5_Enhanced.exe", "--abort-key", "SCROLL_LOCK"});
        assertEquals(LiveSolverOptions.Mode.WATCH, options.mode());
        assertTrue(options.inputEnabled());
        assertEquals("GTA5_Enhanced.exe", options.targetExecutable());
        assertEquals(EmergencyAbortKey.SCROLL_LOCK, options.abortKey());
        assertEquals(0x91, options.abortKey().virtualKeyCode());
        assertFalse(options.help());
    }

    @Test
    void abortKeyWithoutOptInIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--target-exe", "GTA5.exe", "--abort-key", "SCROLL_LOCK"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--list-monitors", "--abort-key", "SCROLL_LOCK"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--target-exe", "GTA5.exe", "--abort-key", "SCROLL_LOCK",
                        "--abort-key", "F8"}));
    }

    @Test
    void unknownAbortNamesAreRefused() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--target-exe", "GTA5.exe", "--abort-key", "HOME"}));
        assertTrue(unknown.getMessage().contains("F1..F24"), unknown.getMessage());
    }

    @Test
    void gameplayControlsCannotBeAbortKeys() {
        for (String reserved : new String[] {"UP", "TAB", "SELECT", "ENTER", "PROCEED"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                    new String[] {"--watch", "--enable-input", "--target-exe", "GTA5.exe", "--abort-key", reserved}),
                    reserved + " must be refused");
            assertTrue(error.getMessage().contains("REFUSED ABORT KEY"), error.getMessage());
        }
    }

    @Test
    void f12ParsesButCarriesItsDocumentedConflict() {
        LiveSolverOptions options = LiveSolverOptions.parse(new String[] {"--watch",
                "--enable-input", "--target-exe", "GTA5.exe", "--abort-key", "F12"});
        assertEquals(EmergencyAbortKey.F12, options.abortKey());
        assertTrue(options.abortKey().knownConflict().orElseThrow().contains("Steam screenshot"));
        assertTrue(LiveSolverMain.abortConflictWarning(options.abortKey()).isPresent());
        assertTrue(LiveSolverMain.abortConflictWarning(EmergencyAbortKey.SCROLL_LOCK).isEmpty());
    }

    @Test
    void listMonitorsRemainsValidWithoutAnAbortKey() {
        LiveSolverOptions options = LiveSolverOptions.parse(new String[] {"--list-monitors"});
        assertEquals(LiveSolverOptions.Mode.LIST_MONITORS, options.mode());
        assertFalse(options.inputEnabled());
        assertEquals(null, options.abortKey());
    }

    @Test
    void unknownFlagsContradictionsAndRangesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LiveSolverOptions.parse(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--list-monitors", "--watch", "--enable-input", "--target-exe",
                        "GTA5.exe", "--abort-key", "SCROLL_LOCK"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--target-exe", "GTA5.exe", "--abort-key", "SCROLL_LOCK",
                        "--stable-frames", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> LiveSolverOptions.parse(new String[] {"--solve-now"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--list-monitors", "--enable-input", "--target-exe", "GTA5.exe", "--abort-key", "SCROLL_LOCK"}));
    }

    @Test
    void usageDocumentsTheExplicitAbortContract() {
        String usage = LiveSolverOptions.usage();
        assertTrue(usage.contains("--enable-input"), "opt-in flag");
        assertTrue(usage.contains("--target-exe"), "target executable");
        assertTrue(usage.contains("--abort-key"), "abort key option: " + usage);
        assertTrue(usage.contains("SCANCODE_BATCH"), "delivery: " + usage);
        assertTrue(!usage.contains("F12 is the emergency abort"), "no fixed F12 claim: " + usage);
    }
}
