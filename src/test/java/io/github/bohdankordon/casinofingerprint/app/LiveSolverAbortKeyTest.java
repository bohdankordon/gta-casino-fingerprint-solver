package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.4 live abort-key wiring: explicit configuration, selected-key output and the
 * production scan-code delivery banner. Pure option parsing plus pure banner helpers plus
 * source-shape guards only: no test here constructs a native backend or emits OS input.
 */
class LiveSolverAbortKeyTest {
    @Test
    void deliveryBannerIsScanCodeBatch() {
        assertEquals("LIVE: input delivery: SCANCODE_BATCH", LiveSolverMain.inputDeliveryLine());
    }

    @Test
    void abortBannerUsesTheSelectedSymbolicName() {
        assertEquals("LIVE: emergency abort: SCROLL_LOCK (hold to stop input immediately)",
                LiveSolverMain.emergencyAbortLine(EmergencyAbortKey.SCROLL_LOCK));
        assertEquals("LIVE: emergency abort: F8 (hold to stop input immediately)",
                LiveSolverMain.emergencyAbortLine(EmergencyAbortKey.F8));
    }

    @Test
    void watchIntroUsesTheSelectedSymbolicName() {
        String intro = LiveSolverMain.watchIntroLine(EmergencyAbortKey.SCROLL_LOCK);
        assertTrue(intro.contains("SCROLL_LOCK"), intro);
        assertTrue(!intro.contains("F12 aborts") && !intro.contains("F12 "), "never hardcodes F12: " + intro);
    }

    @Test
    void conflictWarningSurfacesF12ButNotScrollLock() {
        assertTrue(LiveSolverMain.abortConflictWarning(EmergencyAbortKey.F12).isPresent());
        assertTrue(LiveSolverMain.abortConflictWarning(EmergencyAbortKey.F12).orElseThrow().contains("Steam screenshot"));
        assertTrue(LiveSolverMain.abortConflictWarning(EmergencyAbortKey.SCROLL_LOCK).isEmpty());
    }

    @Test
    void liveMainWiresTheSelectedAbortVirtualKey() throws IOException {
        Path main = Path.of(System.getProperty("user.dir")).resolve(
                "src/main/java/io/github/bohdankordon/casinofingerprint/app/LiveSolverMain.java");
        String text = Files.readString(main, StandardCharsets.UTF_8);
        assertTrue(text.contains("EmergencyAbort"), "abort stays wired");
        assertTrue(text.contains("options.abortKey().virtualKeyCode()"), "selected VK is passed");
        assertTrue(text.contains("inputDeliveryLine()") || text.contains("input delivery: SCANCODE_BATCH"),
                "delivery banner is printed");
        assertTrue(text.contains("emergencyAbortLine(options.abortKey())")
                || text.contains("emergency abort: \" + options.abortKey()"), "selected name is printed");
    }

    @Test
    void liveMainNeverHardcodesAFixedAbortBanner() throws IOException {
        Path main = Path.of(System.getProperty("user.dir")).resolve(
                "src/main/java/io/github/bohdankordon/casinofingerprint/app/LiveSolverMain.java");
        String text = Files.readString(main, StandardCharsets.UTF_8);
        assertTrue(!text.contains("emergency abort: F12"), "no fixed F12 banner: " + text.substring(0, Math.min(3000, text.length())));
        assertTrue(!text.contains("F12 aborts input immediately.\""), "watch intro is parameterized");
    }
}
