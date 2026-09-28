package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import java.util.List;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Owned observation path of {@link FrameRecognitionPipeline}: the same single normalization pass
 * as {@code recognize}, with explicit native ownership.
 */
class FrameRecognitionObservationTest {
    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    @Test
    void observeKeepsTheSameDecisionAsRecognize() throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            FrameRecognitionResult plain = pipeline.recognize(frame);
            try (FrameRecognitionObservation observation = pipeline.observe(frame)) {
                assertEquals(plain.decision().result().status(),
                        observation.decision().result().status());
                assertEquals(plain.decision().result().fingerprintId(),
                        observation.decision().result().fingerprintId());
                assertEquals(plain.decision().result().selectedCandidateIndices(),
                        observation.decision().result().selectedCandidateIndices());
                assertEquals(plain.decision().result().confidence(),
                        observation.decision().result().confidence(), 1e-12);
                assertEquals(RecognitionResult.Status.RECOGNIZED,
                        observation.decision().result().status());
                assertEquals(FingerprintId.FP_1,
                        observation.decision().result().fingerprintId().orElseThrow());
                assertEquals(List.of(0, 3, 6, 7),
                        observation.decision().result().selectedCandidateIndices());
                assertTrue(observation.extractionNanos() >= 0);
                assertTrue(observation.recognitionNanos() >= 0);
                assertFalse(observation.closed());
                assertFalse(observation.puzzle().target().empty(),
                        "The observation owns the normalized puzzle");
                assertEquals(8, observation.puzzle().candidates().size());
            }
        }
    }

    @Test
    void recognizeRetainsItsApiAndReturnsNoNativeOwnership() throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionResult result =
                    Stage5TestSupport.pipeline(library).recognize(frame);
            assertEquals(RecognitionResult.Status.RECOGNIZED, result.decision().result().status());
            assertTrue(result.extractionNanos() >= 0);
            assertTrue(result.recognitionNanos() >= 0);
            // The plain result carries no native ownership: only the decision and two longs.
            assertEquals(3, FrameRecognitionResult.class.getRecordComponents().length);
        }
    }

    @Test
    void observationCloseIsIdempotentAndReleasesThePuzzle() throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionObservation observation =
                    Stage5TestSupport.pipeline(library).observe(frame);
            assertFalse(observation.closed());
            observation.close();
            assertTrue(observation.closed());
            observation.close();
            assertTrue(observation.closed(), "close is idempotent");
            assertThrows(IllegalStateException.class, observation::puzzle,
                    "The puzzle must not be usable after close");
            // The decision is plain data and stays valid after close.
            assertEquals(RecognitionResult.Status.RECOGNIZED,
                    observation.decision().result().status());
        }
    }

    @Test
    void observeDoesNotModifyTheSourceFrame() throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame();
                Mat original = frame.clone();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            try (FrameRecognitionObservation ignored =
                    Stage5TestSupport.pipeline(library).observe(frame)) {
                // owned observation closed here
            }
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
        }
    }

    @Test
    void observeRejectsBadFramesLikeRecognize() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (Mat empty = new Mat()) {
                assertThrows(IllegalArgumentException.class, () -> pipeline.observe(empty));
            }
            try (Mat logical = Stage5TestSupport.logicalSizeFrame()) {
                assertThrows(UnsupportedFrameSizeException.class, () -> pipeline.observe(logical));
            }
            assertThrows(NullPointerException.class, () -> pipeline.observe(null));
        }
    }

    @Test
    void liveRuntimeBehaviourIsUnchangedByTheObservationPath() throws Exception {
        // The live runtime still uses recognize(); the observation path adds no behaviour change.
        try (Mat fixture = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FakeScreenCapture capture = FakeScreenCapture.create().frames(fixture, 3);
            LiveRecognitionRuntime runtime = new LiveRecognitionRuntime(capture,
                    Stage5TestSupport.pipeline(library),
                    new RecognitionConsensusTracker(3));
            runtime.poll();
            runtime.poll();
            LiveFrameOutcome third = runtime.poll();
            assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, third.status().state());
            assertEquals(FingerprintId.FP_1, third.status().fingerprint().orElseThrow());
        }
    }
}
