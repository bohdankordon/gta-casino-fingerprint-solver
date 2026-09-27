package io.github.bohdankordon.casinofingerprint.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bohdankordon.casinofingerprint.matching.evaluation.FixtureAnnotation;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.PuzzleRecognitionEngine;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage 4 perturbation regression: every realistic deterministic perturbation accepted by
 * the Stage 3 robustness suite must still return {@code RECOGNIZED} with the annotated
 * answer under the default prototype-conservative policy.
 *
 * <p>If a genuinely borderline perturbation ever stays {@code UNCERTAIN}, the threshold
 * must NOT simply be lowered until it passes: investigate first and document the evidence.
 * The per-perturbation evidence below {@code target/} exists for exactly that diagnosis.
 */
class RecognitionRobustnessTest {
    private static final List<Integer> EXPECTED_SELECTION = List.of(0, 3, 6, 7);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadNativeLibrary() {
        MatchingTestSupport.loadNativeLibrary();
    }

    @Test
    void realisticPerturbationsRemainRecognizedWithAStableAnswer() throws Exception {
        FixtureAnnotation annotation = MatchingTestSupport.representativeAnnotation();
        Map<String, UnaryOperator<Mat>> perturbations = new LinkedHashMap<>();
        perturbations.put("shift(+2,0)", raw -> MatchingTestSupport.translate(raw, 2, 0));
        perturbations.put("shift(0,-3)", raw -> MatchingTestSupport.translate(raw, 0, -3));
        perturbations.put("shift(-2,+3)", raw -> MatchingTestSupport.translate(raw, -2, 3));
        perturbations.put("shift(+3,+3)", raw -> MatchingTestSupport.translate(raw, 3, 3));
        perturbations.put("shift(-4,-4)", raw -> MatchingTestSupport.translate(raw, -4, -4));
        perturbations.put("shift(+4,0)", raw -> MatchingTestSupport.translate(raw, 4, 0));
        perturbations.put("blur(5x5,1.2)", MatchingTestSupport::blur);
        Path tempFile = tempDir.resolve("puzzle.jpg");
        perturbations.put("jpeg-q55",
                raw -> MatchingTestSupport.jpegRoundTrip(raw, tempFile, 55));
        perturbations.put("shift+blur+jpeg",
                raw -> MatchingTestSupport.jpegRoundTrip(
                        MatchingTestSupport.blur(MatchingTestSupport.translate(raw, 1, -1)), tempFile, 55));
        perturbations.put("brightness(0.6x)",
                raw -> MatchingTestSupport.scaleBrightness(raw, 0.6, 0));
        perturbations.put("brightness(1.35x,+10)",
                raw -> MatchingTestSupport.scaleBrightness(raw, 1.35, 10));
        perturbations.put("contrast(0.55x,+40)",
                raw -> MatchingTestSupport.scaleBrightness(raw, 0.55, 40));

        List<String> summary = new ArrayList<>();
        summary.add("label,status,fingerprint,selected,target_score,target_margin,"
                + "assignment_mean,weakest_pair,selection_margin,min_column_margin,evidence_strength");
        double worstTargetScore = Double.MAX_VALUE;
        double worstTargetMargin = Double.MAX_VALUE;
        double worstMean = Double.MAX_VALUE;
        double worstWeakest = Double.MAX_VALUE;
        double worstSelection = Double.MAX_VALUE;
        double worstColumn = Double.MAX_VALUE;
        for (Map.Entry<String, UnaryOperator<Mat>> perturbation : perturbations.entrySet()) {
            RecognitionDecision decision = recognizeWith(perturbation.getValue());
            RecognitionEvidence evidence = decision.evidence();
            summary.add(String.format(Locale.ROOT,
                    "%s,%s,%s,\"%s\",%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                    perturbation.getKey(),
                    decision.result().status(),
                    decision.result().fingerprintId().map(Object::toString).orElse("(none)"),
                    decision.result().selectedCandidateIndices().toString(),
                    evidence.bestTargetScore(), evidence.targetMargin(),
                    evidence.bestAssignmentMean(), evidence.weakestAssignedPair(),
                    evidence.selectionMargin(), evidence.minimumFragmentColumnMargin(),
                    decision.result().confidence()));
            worstTargetScore = Math.min(worstTargetScore, evidence.bestTargetScore());
            worstTargetMargin = Math.min(worstTargetMargin, evidence.targetMargin());
            worstMean = Math.min(worstMean, evidence.bestAssignmentMean());
            worstWeakest = Math.min(worstWeakest, evidence.weakestAssignedPair());
            worstSelection = Math.min(worstSelection, evidence.selectionMargin());
            worstColumn = Math.min(worstColumn, evidence.minimumFragmentColumnMargin());
            assertEquals(RecognitionResult.Status.RECOGNIZED,
                    decision.result().status(), perturbation.getKey() + ": status");
            assertEquals(FingerprintId.FP_1,
                    decision.result().fingerprintId().orElseThrow(),
                    perturbation.getKey() + ": fingerprint");
            assertEquals(EXPECTED_SELECTION, decision.result().selectedCandidateIndices(),
                    perturbation.getKey() + ": selected candidates");
            assertEquals(annotation.target(),
                    decision.result().fingerprintId().orElseThrow(),
                    perturbation.getKey() + ": agrees with the annotation");
        }
        summary.add(String.format(Locale.ROOT,
                "WORST,,,,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,",
                worstTargetScore, worstTargetMargin, worstMean, worstWeakest,
                worstSelection, worstColumn));
        Path output = MatchingTestSupport.PROJECT_ROOT.resolve(
                "target/stage4-perturbation-summary.csv".replace('/', java.io.File.separatorChar));
        Files.createDirectories(output.getParent());
        Files.writeString(output, String.join("\n", summary) + "\n", StandardCharsets.UTF_8);
    }

    private static RecognitionDecision recognizeWith(UnaryOperator<Mat> perturbation)
            throws IOException {
        try (ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary();
                NormalizedPuzzleFrame puzzle = MatchingTestSupport.normalizedPuzzleWith(perturbation)) {
            return new PuzzleRecognitionEngine().recognize(puzzle, library);
        }
    }
}

