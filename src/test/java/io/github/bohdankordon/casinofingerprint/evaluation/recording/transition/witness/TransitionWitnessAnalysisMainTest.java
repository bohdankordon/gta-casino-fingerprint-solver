package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Command line contract of the witness analysis: usage, artifacts and failure handling. */
class TransitionWitnessAnalysisMainTest {
    @Test
    void helpPrintsUsageAndSucceeds() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = TransitionWitnessAnalysisMain.run(new String[] {"--help"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("--no-contact-sheets"));
    }

    @Test
    void anUnknownOptionIsAUsageError() throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = TransitionWitnessAnalysisMain.run(new String[] {"--not-an-option"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown option"));
    }

    @Test
    void aMissingOptionValueIsAUsageError() throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = TransitionWitnessAnalysisMain.run(new String[] {"--source"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("requires a value"));
    }

    @Test
    void aProjectWithoutRecordingsOrAnnotationsFailsCleanly(@TempDir Path emptyProject)
            throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = TransitionWitnessAnalysisMain.run(
                new String[] {"--project-root", emptyProject.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(3, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("WITNESS_ERROR"));
    }
}
