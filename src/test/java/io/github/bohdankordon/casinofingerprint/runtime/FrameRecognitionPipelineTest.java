package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.gameplay.ExtractedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFrameExtractor;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.PuzzleRecognitionEngine;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.util.List;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The full-frame pipeline on the stored 2560x1440 gameplay fixture: the fixture is recognized with
 * the Stage 4 answer and evidence, the source frame is never modified, wrong frame sizes are
 * rejected before ROI extraction and one reference library serves repeated frames.
 */
class FrameRecognitionPipelineTest {
    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    @Test
    void fixtureFrameIsRecognizedThroughTheFullPipeline() throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionResult result = Stage5TestSupport.pipeline(library).recognize(frame);
            RecognitionResult decision = result.decision().result();

            assertEquals(RecognitionResult.Status.RECOGNIZED, decision.status(), "Status");
            assertEquals(FingerprintId.FP_1, decision.fingerprintId().orElseThrow(), "Fingerprint");
            assertEquals(List.of(0, 3, 6, 7), decision.selectedCandidateIndices(), "Candidates");
            assertTrue(result.decision().uncertaintyReasons().isEmpty(), "No uncertainty reasons");
            assertTrue(result.extractionNanos() > 0, "Extraction timing measured");
            assertTrue(result.recognitionNanos() > 0, "Recognition timing measured");
        }
    }

    @Test
    void pipelineDecisionMatchesTheStage4PipelineOnTheSameFrame() throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            GameplayLayout layout = Stage5TestSupport.representativeLayout();
            RecognitionDecision stage4;
            try (ExtractedPuzzleFrame raw = new GameplayFrameExtractor(layout).extract(frame);
                    NormalizedPuzzleFrame puzzle =
                            NormalizedPuzzleFrame.normalize(raw, new StructuralNormalizer())) {
                stage4 = new PuzzleRecognitionEngine().recognize(puzzle, library);
            }

            RecognitionDecision actual =
                    Stage5TestSupport.pipeline(library).recognize(frame).decision();

            assertEquals(stage4.result().status(), actual.result().status(), "Status");
            assertEquals(stage4.result().fingerprintId(), actual.result().fingerprintId(),
                    "Fingerprint");
            assertEquals(stage4.result().selectedCandidateIndices(),
                    actual.result().selectedCandidateIndices(), "Selection");
            assertEquals(stage4.result().confidence(), actual.result().confidence(), 1e-12,
                    "Evidence strength");
            assertEquals(stage4.uncertaintyReasons(), actual.uncertaintyReasons(), "Reasons");
            assertTrue(actual.result().confidence() > 0.6,
                    "Fixture evidence strength near 0.61: " + actual.result().confidence());
        }
    }

    @Test
    void sourceFrameIsNotModifiedByRecognition() throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame();
                Mat original = frame.clone();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            Stage5TestSupport.pipeline(library).recognize(frame);

            try (Mat difference = new Mat()) {
                opencv_core.absdiff(original, frame, difference);
                for (int channel = 0; channel < 3; channel++) {
                    try (Mat singleChannel = new Mat()) {
                        opencv_core.extractChannel(difference, singleChannel, channel);
                        assertEquals(0, opencv_core.countNonZero(singleChannel),
                                "Channel " + channel + " of the source frame is unchanged");
                    }
                }
            }
            assertEquals(GameplayFixture.EXPECTED_WIDTH, frame.cols(), "The source frame is still open");
            assertEquals(GameplayFixture.EXPECTED_HEIGHT, frame.rows(), "The source frame is still open");
        }
    }

    @Test
    void logicalSizeFrameIsRejectedBeforeRoiExtraction() throws Exception {
        try (Mat logical = Stage5TestSupport.logicalSizeFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);

            UnsupportedFrameSizeException failure = assertThrows(UnsupportedFrameSizeException.class,
                    () -> pipeline.recognize(logical));

            assertEquals(GameplayFixture.EXPECTED_WIDTH, failure.expectedWidth(), "Expected width");
            assertEquals(GameplayFixture.EXPECTED_HEIGHT, failure.expectedHeight(), "Expected height");
            assertEquals(Stage5TestSupport.LOGICAL_WIDTH, failure.actualWidth(), "Actual width");
            assertEquals(Stage5TestSupport.LOGICAL_HEIGHT, failure.actualHeight(), "Actual height");
            assertTrue(failure.getMessage().contains("2048x1152"), "Diagnostic: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("2560x1440"), "Diagnostic: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("logical-resolution"),
                    "Diagnostic explains the DPI cause: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("refuses to resize"),
                    "Diagnostic states the refusal: " + failure.getMessage());
            assertEquals(Stage5TestSupport.LOGICAL_WIDTH, logical.cols(),
                    "The rejected frame is untouched");
        }
    }

    @Test
    void emptyFrameIsRejectedAsAProgrammerError() throws Exception {
        try (Mat empty = new Mat();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            assertThrows(IllegalArgumentException.class, () -> pipeline.recognize(empty));
        }
    }

    @Test
    void repeatedRecognitionOfTheSameFrameIsDeterministic() throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);

            RecognitionDecision first = pipeline.recognize(frame).decision();
            RecognitionDecision second = pipeline.recognize(frame).decision();

            assertEquals(first.result().status(), second.result().status(), "Status");
            assertEquals(first.result().fingerprintId(), second.result().fingerprintId(),
                    "Fingerprint");
            assertEquals(first.result().selectedCandidateIndices(),
                    second.result().selectedCandidateIndices(), "Selection");
            assertEquals(first.result().confidence(), second.result().confidence(), 1e-12,
                    "Evidence strength");
            assertEquals(first.bestAssignment().candidatesInFragmentOrder(),
                    second.bestAssignment().candidatesInFragmentOrder(), "Mapping");
            assertEquals(first.uncertaintyReasons(), second.uncertaintyReasons(), "Reasons");
        }
    }

    @Test
    void oneReferenceLibraryServesRepeatedFrames() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);

            for (int frameNumber = 1; frameNumber <= 3; frameNumber++) {
                try (Mat frame = Stage5TestSupport.fixtureFrame()) {
                    RecognitionDecision decision = pipeline.recognize(frame).decision();
                    assertEquals(List.of(0, 3, 6, 7), decision.result().selectedCandidateIndices(),
                            "Frame " + frameNumber + " selection");
                }
            }

            assertFalse(library.target(FingerprintId.FP_1).empty(),
                    "The library is still usable after many frames");
        }
    }

    @Test
    void closedLibraryFailsLoudlyInsteadOfSilently() throws Exception {
        ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary();
        FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
        library.close();

        try (Mat frame = Stage5TestSupport.fixtureFrame()) {
            assertThrows(IllegalStateException.class, () -> pipeline.recognize(frame),
                    "The pipeline borrows the library and never reopens it");
        }
    }
}
