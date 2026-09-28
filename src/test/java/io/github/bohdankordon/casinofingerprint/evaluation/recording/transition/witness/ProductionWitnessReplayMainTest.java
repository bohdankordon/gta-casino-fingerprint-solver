package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The production-witness replay command line must be usable without the private recordings, and
 * the counterfactual substitution must be pixels-untouched identity metadata only.
 */
class ProductionWitnessReplayMainTest {

    @Test
    void helpPrintsUsageAndExitsWithoutTouchingARecording() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        Integer exit = ProductionWitnessReplayMain.run(new String[] {"--help"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("--padding-seconds"));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unknownOptionIsAUsageError() throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        Integer exit = ProductionWitnessReplayMain.run(new String[] {"--tune-threshold"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown option"));
    }

    @Test
    void missingOptionValueIsAUsageError() throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        Integer exit = ProductionWitnessReplayMain.run(new String[] {"--padding-seconds"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("requires a value"));
    }

    @Test
    void artifactPathsArePlainFilesDirectlyBelowTheOutputDirectory() {
        Path outputDir = Path.of("build-output").toAbsolutePath();

        assertEquals(outputDir.resolve("stage6c1d-production-witness-events.csv"),
                ProductionWitnessReplay.artifactPath(outputDir,
                        ProductionWitnessReplay.EVENTS_CSV_REL));
        assertEquals(outputDir.resolve("stage6c1d-production-witness-summary.csv"),
                ProductionWitnessReplay.artifactPath(outputDir,
                        ProductionWitnessReplay.SUMMARY_CSV_REL));
        assertEquals(outputDir.resolve("stage6c1d-production-witness-counterfactual-events.csv"),
                ProductionWitnessReplay.artifactPath(outputDir,
                        ProductionWitnessReplay.COUNTERFACTUAL_EVENTS_CSV_REL));
        assertEquals(outputDir.resolve("stage6c1d-production-witness-counterfactual-summary.csv"),
                ProductionWitnessReplay.artifactPath(outputDir,
                        ProductionWitnessReplay.COUNTERFACTUAL_SUMMARY_CSV_REL));
        assertEquals(outputDir.resolve("stage6c1d-production-witness-report.txt"),
                ProductionWitnessReplay.artifactPath(outputDir,
                        ProductionWitnessReplay.REPORT_REL));
        assertThrows(IllegalArgumentException.class, () -> ProductionWitnessReplay
                .artifactPath(outputDir, "docs/production-transition-witness.md"));
        assertThrows(IllegalArgumentException.class, () -> ProductionWitnessReplay
                .artifactPath(outputDir, "target/nested/artifact.csv"));
    }

    @Test
    void everyProductionWitnessArtifactStaysBelowTheIgnoredTargetDirectory() {
        for (String artifact : List.of(
                ProductionWitnessReplay.EVENTS_CSV_REL,
                ProductionWitnessReplay.SUMMARY_CSV_REL,
                ProductionWitnessReplay.COUNTERFACTUAL_EVENTS_CSV_REL,
                ProductionWitnessReplay.COUNTERFACTUAL_SUMMARY_CSV_REL,
                ProductionWitnessReplay.REPORT_REL)) {
            assertTrue(artifact.startsWith("target/"),
                    artifact + " must be build output, never a committed artifact");
        }
    }

    @Test
    void counterfactualSubstitutionOnlyRewritesTheRoundTwoIdentity() {
        RecognitionIdentity roundOne =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6));
        RecognitionIdentity roundTwo =
                RecognitionIdentity.of(FingerprintId.FP_3, List.of(2, 4, 6, 7));
        RecognitionDecision roundTwoDecision = io.github.bohdankordon.casinofingerprint.runtime
                .Stage5TestSupport.syntheticRecognized(FingerprintId.FP_3, List.of(2, 4, 6, 7));
        RecognitionDecision substituted = ProductionWitnessReplay.substitute(roundTwoDecision,
                roundOne, roundTwo);
        assertEquals(FingerprintId.FP_4,
                substituted.result().fingerprintId().orElseThrow());
        assertEquals(List.of(1, 4, 5, 6), substituted.result().selectedCandidateIndices());

        RecognitionDecision roundOneDecision = io.github.bohdankordon.casinofingerprint.runtime
                .Stage5TestSupport.syntheticRecognized(FingerprintId.FP_4, List.of(1, 4, 5, 6));
        assertEquals(roundOneDecision,
                ProductionWitnessReplay.substitute(roundOneDecision, roundOne, roundTwo));

        RecognitionDecision uncertain = io.github.bohdankordon.casinofingerprint.runtime
                .Stage5TestSupport.syntheticUncertain();
        assertEquals(uncertain,
                ProductionWitnessReplay.substitute(uncertain, roundOne, roundTwo));
    }
}
