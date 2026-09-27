package io.github.bohdankordon.casinofingerprint.recognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.gameplay.ExtractedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFrameExtractor;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.matching.evaluation.FixtureAnnotation;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.nio.file.Path;
import java.util.List;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.Test;

/**
 * Stage 4 behavior on the representative 2560x1440 fixture: the full engine must return
 * {@code RECOGNIZED} with the human-verified answer (FP_1, candidates [0, 3, 6, 7]).
 */
class RepresentativeFixtureRecognitionTest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath();

    @Test
    void representativeFixtureIsRecognizedWithTheAnnotatedAnswer() throws Exception {
        org.bytedeco.javacpp.Loader.load(opencv_core.class);
        RecognitionDecision decision = recognizeFixture();
        RecognitionResult result = decision.result();

        assertEquals(RecognitionResult.Status.RECOGNIZED, result.status(), "Status");
        assertEquals(FingerprintId.FP_1, result.fingerprintId().orElseThrow(), "Fingerprint");
        assertEquals(List.of(0, 3, 6, 7), result.selectedCandidateIndices(),
                "Selected candidates sorted ascending");
        assertEquals(FingerprintId.FP_1, decision.evidence().bestTarget(), "Evidence target");
        assertTrue(decision.uncertaintyReasons().isEmpty(), "No uncertainty reasons");
    }

    @Test
    void bestAssignmentMappingMatchesTheHumanVerifiedGroundTruth() throws Exception {
        org.bytedeco.javacpp.Loader.load(opencv_core.class);
        FixtureAnnotation annotation =
                FixtureAnnotation.read(PROJECT_ROOT.resolve(FixtureAnnotation.REPRESENTATIVE_REL));
        RecognitionDecision decision = recognizeFixture();

        assertEquals(FingerprintId.FP_1, annotation.target(), "Annotation target");
        for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
            assertEquals(annotation.candidateFor(fragmentId),
                    decision.bestAssignment().candidateForFragment(fragmentId),
                    "FRAGMENT_" + fragmentId + " mapping agrees with the annotation");
        }
        assertEquals(4, decision.bestAssignment().selectedCandidateSet().size(),
                "Four distinct candidates");
        assertEquals(List.of(0, 3, 6, 7), decision.bestAssignment().selectedCandidatesSorted(),
                "Selected indices deterministically sorted");
    }

    @Test
    void confidenceAndEvidenceAreFiniteAndInternallyConsistent() throws Exception {
        org.bytedeco.javacpp.Loader.load(opencv_core.class);
        RecognitionDecision decision = recognizeFixture();
        RecognitionEvidence evidence = decision.evidence();

        double confidence = decision.result().confidence();
        assertTrue(Double.isFinite(confidence) && confidence >= 0.0 && confidence <= 1.0,
                "Confidence finite in [0, 1]: " + confidence);
        assertEquals(
                Math.min(evidence.bestTargetScore(),
                        Math.min(evidence.bestAssignmentMean(), evidence.weakestAssignedPair())),
                confidence, 1e-12, "Documented evidence-strength formula");
        assertTrue(Double.isFinite(evidence.bestAssignmentMean()), "Mean recorded");
        assertTrue(Double.isFinite(evidence.weakestAssignedPair()), "Weakest pair recorded");
        assertTrue(Double.isFinite(evidence.selectionMargin()), "Selection margin recorded");
        assertTrue(Double.isFinite(evidence.minimumFragmentColumnMargin()), "Column margin recorded");
        assertTrue(Double.isFinite(evidence.assignmentMappingMargin()), "Mapping margin recorded");
        assertTrue(evidence.bestAssignmentMean() > 0.9,
                "Fixture assignment mean near 0.95: " + evidence.bestAssignmentMean());
        assertTrue(evidence.weakestAssignedPair() > 0.9,
                "Fixture weakest pair near 0.93: " + evidence.weakestAssignedPair());
    }

    @Test
    void repeatedRecognitionIsDeterministic() throws Exception {
        org.bytedeco.javacpp.Loader.load(opencv_core.class);
        RecognitionDecision first = recognizeFixture();
        RecognitionDecision second = recognizeFixture();

        assertEquals(first.result().status(), second.result().status(), "Status");
        assertEquals(first.result().fingerprintId(), second.result().fingerprintId(), "Fingerprint");
        assertEquals(first.result().selectedCandidateIndices(),
                second.result().selectedCandidateIndices(), "Selection");
        assertEquals(first.result().confidence(), second.result().confidence(), 1e-12, "Confidence");
        assertEquals(first.bestAssignment().candidatesInFragmentOrder(),
                second.bestAssignment().candidatesInFragmentOrder(), "Mapping");
        assertEquals(first.uncertaintyReasons(), second.uncertaintyReasons(), "Reasons");
    }

    private static RecognitionDecision recognizeFixture() throws Exception {
        GameplayLayout layout =
                GameplayLayout.representative(PROJECT_ROOT.resolve(GameplayFixture.LAYOUT_REL));
        StructuralNormalizer normalizer = new StructuralNormalizer();
        try (Mat frame = opencv_imgcodecs.imread(
                PROJECT_ROOT.resolve(GameplayFixture.SOURCE_REL).toString(),
                opencv_imgcodecs.IMREAD_UNCHANGED)) {
            if (frame == null || frame.empty()) {
                throw new IllegalStateException("Could not decode gameplay fixture");
            }
            try (ReferenceFingerprintLibrary library = ReferenceFingerprintLibrary.load(PROJECT_ROOT);
                    ExtractedPuzzleFrame raw = new GameplayFrameExtractor(layout).extract(frame);
                    NormalizedPuzzleFrame puzzle = NormalizedPuzzleFrame.normalize(raw, normalizer)) {
                return new PuzzleRecognitionEngine().recognize(puzzle, library);
            }
        }
    }
}

