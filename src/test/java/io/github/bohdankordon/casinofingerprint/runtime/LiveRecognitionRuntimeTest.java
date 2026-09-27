package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The live loop against a fake capture: consensus over consecutive fixture frames, uncertainty
 * that blocks stability, wrong-size captures and backend failures reported as states instead of
 * crashes, and frame ownership.
 */
class LiveRecognitionRuntimeTest {
    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    @Test
    void threeFixtureFramesProduceOneStableRecognition() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FakeScreenCapture capture = FakeScreenCapture.create().frames(fixture, 3);
            LiveRecognitionRuntime runtime = runtime(capture, library, 3);

            LiveFrameOutcome first = runtime.poll();
            LiveFrameOutcome second = runtime.poll();
            LiveFrameOutcome third = runtime.poll();

            assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, first.status().state(), "Frame 1");
            assertEquals(1, first.status().streak(), "Frame 1 streak");
            assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, second.status().state(), "Frame 2");
            assertEquals(2, second.status().streak(), "Frame 2 streak");
            assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, third.status().state(), "Frame 3");
            assertEquals(3, third.status().streak(), "Frame 3 streak");
            assertEquals(FingerprintId.FP_1, third.status().fingerprint().orElseThrow(), "Fingerprint");
            assertEquals(List.of(0, 3, 6, 7), third.status().selectedCandidates(), "Candidates");
            assertEquals(3, capture.captureCount(), "One capture per frame");
            assertTrue(third.totalNanos() > 0, "Timings recorded");
            assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, runtime.currentStatus().state(),
                    "The runtime reports the state of the last frame");
        }
    }

    @Test
    void runtimeClosesEveryCapturedFrame() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FakeScreenCapture capture = FakeScreenCapture.create().frames(fixture, 3);
            LiveRecognitionRuntime runtime = runtime(capture, library, 3);

            runtime.poll();
            runtime.poll();
            runtime.poll();

            for (Mat frame : capture.capturedFrames()) {
                assertTrue(frame.isNull(), "The runtime closed a captured frame");
            }
        }
    }

    @Test
    void uncertainFrameBetweenRecognizedOnesPreventsStability() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame();
                Mat noise = Stage5TestSupport.noiseFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FakeScreenCapture capture =
                    FakeScreenCapture.create().frame(fixture).frame(noise).frame(fixture);
            LiveRecognitionRuntime runtime = runtime(capture, library, 3);

            LiveFrameOutcome first = runtime.poll();
            LiveFrameOutcome second = runtime.poll();
            LiveFrameOutcome third = runtime.poll();

            assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, first.status().state(), "Frame 1");
            assertEquals(LiveRecognitionState.UNCERTAIN, second.status().state(), "Frame 2");
            assertTrue(second.status().fingerprint().isEmpty(), "No fingerprint on an uncertain frame");
            assertTrue(!second.status().uncertaintyReasons().isEmpty(), "Uncertainty reasons recorded");
            assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, third.status().state(), "Frame 3");
            assertEquals(1, third.status().streak(), "Frame 3 restarts the streak at one");
            assertNotEquals(LiveRecognitionState.STABLE_RECOGNIZED, third.status().state(),
                    "A gap between identical answers is never stable");
        }
    }

    @Test
    void logicalResolutionCaptureYieldsUnsupportedFrameWithoutSelection() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FakeScreenCapture capture = FakeScreenCapture.create()
                    .resizedFrame(fixture, Stage5TestSupport.LOGICAL_WIDTH,
                            Stage5TestSupport.LOGICAL_HEIGHT)
                    .frame(fixture);
            LiveRecognitionRuntime runtime = runtime(capture, library, 3);

            LiveFrameOutcome unsupported = runtime.poll();

            assertEquals(LiveRecognitionState.UNSUPPORTED_FRAME, unsupported.status().state(), "State");
            assertTrue(unsupported.status().fingerprint().isEmpty(), "No fingerprint");
            assertTrue(unsupported.status().selectedCandidates().isEmpty(), "No candidate selection");
            String message = unsupported.status().message().orElseThrow();
            assertTrue(message.contains("2048x1152"), "Diagnostic: " + message);
            assertTrue(message.contains("2560x1440"), "Diagnostic: " + message);

            LiveFrameOutcome next = runtime.poll();
            assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, next.status().state(),
                    "The runtime keeps working after an unsupported frame");
            assertEquals(1, next.status().streak(), "The unsupported frame reset the streak");
        }
    }

    @Test
    void captureFailureIsReportedAsCaptureErrorAndDoesNotBreakTheLoop() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FakeScreenCapture capture = FakeScreenCapture.create()
                    .frame(fixture)
                    .failure(new CaptureException("display went away"))
                    .frames(fixture, 3);
            LiveRecognitionRuntime runtime = runtime(capture, library, 3);

            assertEquals(1, runtime.poll().status().streak(), "Frame 1");
            LiveFrameOutcome failed = runtime.poll();
            assertEquals(LiveRecognitionState.CAPTURE_ERROR, failed.status().state(), "State");
            assertTrue(failed.status().message().orElseThrow().contains("display went away"),
                    "Diagnostic carries the backend message");
            assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, runtime.poll().status().state(),
                    "Recognition continues after the failure");
            assertEquals(2, runtime.poll().status().streak(), "Frame 4");
            assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, runtime.poll().status().state(),
                    "Frame 5");
        }
    }

    @Test
    void captureFailureEndsAnExistingStreak() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FakeScreenCapture capture = FakeScreenCapture.create()
                    .frames(fixture, 2)
                    .failure(new CaptureException("transient backend error"))
                    .frame(fixture);
            LiveRecognitionRuntime runtime = runtime(capture, library, 3);

            runtime.poll();
            runtime.poll();
            assertEquals(LiveRecognitionState.CAPTURE_ERROR, runtime.poll().status().state(), "Failure");

            LiveFrameOutcome afterFailure = runtime.poll();
            assertEquals(1, afterFailure.status().streak(),
                    "A capture error between recognized frames resets the streak");
        }
    }

    @Test
    void singleFrameModeRecognizesWithoutTouchingTheStreak() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame();
                Mat noise = Stage5TestSupport.noiseFrame();
                ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FakeScreenCapture capture =
                    FakeScreenCapture.create().frame(fixture).frame(noise).frame(fixture);
            LiveRecognitionRuntime runtime = runtime(capture, library, 3);

            LiveFrameOutcome recognized = runtime.recognizeOnce();
            assertEquals(LiveRecognitionState.RECOGNIZED, recognized.status().state(),
                    "One frame is recognized but never stable");
            assertEquals(FingerprintId.FP_1, recognized.status().fingerprint().orElseThrow(),
                    "Fingerprint");
            assertEquals(List.of(0, 3, 6, 7), recognized.status().selectedCandidates(), "Candidates");
            assertEquals(0, recognized.status().streak(), "No streak outside the consensus states");

            LiveFrameOutcome uncertain = runtime.recognizeOnce();
            assertEquals(LiveRecognitionState.UNCERTAIN, uncertain.status().state(), "Decoy frame");
            assertTrue(uncertain.status().fingerprint().isEmpty(), "No fingerprint");

            assertEquals(1, runtime.poll().status().streak(),
                    "Single-frame mode left the consensus streak untouched");
            assertEquals(LiveRecognitionState.WAITING, LiveRecognitionStatus.waiting().state(),
                    "The runtime starts in WAITING before any frame");
        }
    }

    private static LiveRecognitionRuntime runtime(
            FakeScreenCapture capture, ReferenceFingerprintLibrary library, int stableFrames)
            throws Exception {
        return new LiveRecognitionRuntime(capture, Stage5TestSupport.pipeline(library),
                new RecognitionConsensusTracker(stableFrames));
    }
}
