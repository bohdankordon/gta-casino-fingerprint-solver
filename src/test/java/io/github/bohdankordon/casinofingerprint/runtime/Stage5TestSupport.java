package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.FragmentScoreMatrix;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.matching.SimilarityScore;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.AssignmentSearchResult;
import io.github.bohdankordon.casinofingerprint.recognition.ConstrainedAssignmentSolver;
import io.github.bohdankordon.casinofingerprint.recognition.PuzzleRecognitionEngine;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Shared Stage 5 test material: the real 2560x1440 gameplay fixture, decoy frames of the same
 * size and synthetic Stage 4 decisions.
 *
 * <p>Everything works on the real repository fixtures and the real production pipeline. Frames are
 * always freshly allocated, so tests can hand them to the runtime and let it close them.
 */
public final class Stage5TestSupport {
    public static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath();

    /** Logical size a 2560x1440 display reports at 125% Windows scaling. */
    public static final int LOGICAL_WIDTH = 2048;
    /** Logical size a 2560x1440 display reports at 125% Windows scaling. */
    public static final int LOGICAL_HEIGHT = 1152;

    private Stage5TestSupport() {
    }

    public static void loadNativeLibrary() {
        org.bytedeco.javacpp.Loader.load(opencv_core.class);
    }

    public static GameplayLayout representativeLayout() throws IOException {
        return GameplayLayout.representative(PROJECT_ROOT.resolve(GameplayFixture.LAYOUT_REL));
    }

    /** Freshly decoded 2560x1440 gameplay fixture frame; the caller closes it. */
    public static Mat fixtureFrame() throws IOException {
        Mat frame = opencv_imgcodecs.imread(
                PROJECT_ROOT.resolve(GameplayFixture.SOURCE_REL).toString(),
                opencv_imgcodecs.IMREAD_UNCHANGED);
        if (frame == null || frame.empty()) {
            throw new IllegalStateException("Could not decode the gameplay fixture");
        }
        return frame;
    }

    /**
     * The fixture resized to the logical size a scaled desktop would report (2048x1152): the frame
     * shape a naive capture returns on a 125% scaled 2560x1440 display. The caller closes it.
     */
    public static Mat logicalSizeFrame() throws IOException {
        try (Mat fixture = fixtureFrame()) {
            Mat resized = new Mat();
            opencv_imgproc.resize(fixture, resized, new Size(LOGICAL_WIDTH, LOGICAL_HEIGHT));
            return resized;
        }
    }

    /**
     * Deterministic non-puzzle frame of the supported size: uniform seeded noise, which no
     * reference fingerprint can match. The caller closes it.
     */
    public static Mat noiseFrame() {
        int width = GameplayFixture.EXPECTED_WIDTH;
        int height = GameplayFixture.EXPECTED_HEIGHT;
        byte[] pixels = new byte[width * height * 3];
        new Random(20250927L).nextBytes(pixels);
        try (BytePointer data = new BytePointer(pixels);
                Mat view = new Mat(height, width, opencv_core.CV_8UC3, data, (long) width * 3)) {
            return view.clone();
        }
    }

    public static ReferenceFingerprintLibrary openLibrary() throws IOException {
        return ReferenceFingerprintLibrary.load(PROJECT_ROOT);
    }

    public static FrameRecognitionPipeline pipeline(ReferenceFingerprintLibrary library)
            throws IOException {
        return new FrameRecognitionPipeline(representativeLayout(), library);
    }

    /** Strong, unambiguous synthetic decision for the given answer; pure Java, no native library. */
    public static RecognitionDecision syntheticRecognized(
            FingerprintId fingerprint, List<Integer> selected) {
        return syntheticRecognized(fingerprint, selected, 0.61);
    }

    /**
     * Strong synthetic decision with an explicit target score, so tests can vary the evidence
     * strength of the same answer.
     */
    public static RecognitionDecision syntheticRecognized(
            FingerprintId fingerprint, List<Integer> selected, double targetScore) {
        if (selected.size() != 4) {
            throw new IllegalArgumentException("Synthetic answers select exactly four candidates");
        }
        double[][] values = filled(0.10);
        for (int fragment = 0; fragment < 4; fragment++) {
            values[selected.get(fragment)][fragment] = 0.95;
        }
        AssignmentSearchResult assignment = new ConstrainedAssignmentSolver().solve(matrix(values));
        return PuzzleRecognitionEngine.decide(
                fingerprint, targetScore, 0.18, assignment, RecognitionPolicy.defaultPolicy());
    }

    /** Weak synthetic decision that stays UNCERTAIN (low target score). */
    public static RecognitionDecision syntheticUncertain() {
        double[][] values = filled(0.10);
        values[0][0] = 0.20;
        values[1][1] = 0.20;
        values[2][2] = 0.20;
        values[3][3] = 0.20;
        AssignmentSearchResult assignment = new ConstrainedAssignmentSolver().solve(matrix(values));
        return PuzzleRecognitionEngine.decide(
                FingerprintId.FP_2, 0.20, 0.05, assignment, RecognitionPolicy.defaultPolicy());
    }

    private static double[][] filled(double value) {
        double[][] values = new double[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragment = 0; fragment < 4; fragment++) {
                values[candidate][fragment] = value;
            }
        }
        return values;
    }

    private static FragmentScoreMatrix matrix(double[][] values) {
        SimilarityScore[][] scores = new SimilarityScore[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragment = 0; fragment < 4; fragment++) {
                scores[candidate][fragment] = new SimilarityScore(values[candidate][fragment]);
            }
        }
        return new FragmentScoreMatrix(scores);
    }
}
