package io.github.bohdankordon.casinofingerprint.evaluation.recording.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Command-line contract of the control-state evaluation without touching recordings. */
class ControlStateEvaluationMainTest {
    @Test
    void helpPrintsUsageWithoutTouchingRecordings() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = ControlStateEvaluationMain.run(new String[] {"--help"}, out, err,
                Path.of(System.getProperty("user.dir")));
        assertEquals(0, exit, "Exit code");
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("Usage:"), "Usage text");
    }

    @Test
    void unknownOptionsAreUsageErrors() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = ControlStateEvaluationMain.run(new String[] {"--solve-now"}, out, err,
                Path.of(System.getProperty("user.dir")));
        assertEquals(2, exit, "Exit code");
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("unknown option"),
                "Diagnostic");
    }

    @Test
    void committedAnnotationsParseWithoutRecordings() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        var annotations = ControlStateEvaluationMain
                .readAnnotations(root.resolve(ControlStateEvaluationMain.ANNOTATION_REL));
        assertTrue(annotations.size() >= 40, "annotation rows: " + annotations.size());
        long valid = annotations.stream().filter(a -> a.expectValid()).count();
        long refused = annotations.size() - valid;
        assertTrue(valid >= 25 && refused >= 8,
                "mixed actionable and refused rows: valid=" + valid + " refused=" + refused);
    }
}
