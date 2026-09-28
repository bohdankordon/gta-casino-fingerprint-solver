package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle replay command line must be usable without the private recordings: help and
 * option errors never decode anything, and every artifact this tool can write stays below the
 * ignored {@code target/} tree.
 */
class RoundLifecycleReplayMainTest {

    @Test
    void helpPrintsUsageAndExitsWithoutTouchingARecording() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        Integer exit = RoundLifecycleReplayMain.run(new String[] {"--help"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("--padding-seconds"));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unknownOptionIsAUsageError() throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        Integer exit = RoundLifecycleReplayMain.run(new String[] {"--tune-threshold"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown option"));
    }

    @Test
    void missingOptionValueIsAUsageError() throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        Integer exit = RoundLifecycleReplayMain.run(new String[] {"--padding-seconds"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("requires a value"));
    }

    @Test
    void artifactPathsArePlainFilesDirectlyBelowTheOutputDirectory() {
        Path outputDir = Path.of("build-output").toAbsolutePath();

        assertEquals(outputDir.resolve("stage6c1b-lifecycle-events.csv"),
                RoundLifecycleReplay.artifactPath(outputDir,
                        RoundLifecycleReplay.EVENTS_CSV_REL));
        assertEquals(outputDir.resolve("stage6c1b-lifecycle-summary.csv"),
                RoundLifecycleReplay.artifactPath(outputDir,
                        RoundLifecycleReplay.SUMMARY_CSV_REL));
        assertEquals(outputDir.resolve("stage6c1b-lifecycle-report.txt"),
                RoundLifecycleReplay.artifactPath(outputDir, RoundLifecycleReplay.REPORT_REL));
        assertThrows(IllegalArgumentException.class, () -> RoundLifecycleReplay
                .artifactPath(outputDir, "docs/round-lifecycle-tracker.md"));
        assertThrows(IllegalArgumentException.class, () -> RoundLifecycleReplay
                .artifactPath(outputDir, "target/nested/artifact.csv"));
    }
}

