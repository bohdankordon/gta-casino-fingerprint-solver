package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.dryrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Command-line contract of the dry-run replay without touching private recordings. */
class DryRunReplayMainTest {
    @Test
    void helpPrintsUsageWithoutTouchingRecordings() throws Exception {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        Integer exit = DryRunReplayMain.run(new String[] {"--help"}, out, err);
        assertEquals(0, exit, "Exit code");
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("Usage:"), "Usage text");
    }

    @Test
    void unknownOptionsAreUsageErrors() throws Exception {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        Integer exit = DryRunReplayMain.run(new String[] {"--solve-now"}, out, err);
        assertEquals(2, exit, "Exit code");
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("unknown option"),
                "Diagnostic");
    }
}
