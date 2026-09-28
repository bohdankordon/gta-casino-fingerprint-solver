package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.EvaluationLayoutScaler;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegion;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Shared Stage 6C.1C test material.
 *
 * <p>Everything here is deterministic, needs no recording and no private data: frames are built by
 * pasting the committed reference crops into a scaled copy of the production layout, so the
 * production extractor, normalizer, scorer, matcher and decision path all run for real on content
 * whose structure the test chose. One fixture frame content is one explicit test input.
 */
final class WitnessTestSupport {
    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    /** Uniform factor of the test layout; 0.25 keeps frames at 640x360 and stays 16:9. */
    static final double SCALE = 0.25;
    static final int WIDTH = (int) Math.round(GameplayLayout.REPRESENTATIVE_WIDTH * SCALE);
    static final int HEIGHT = (int) Math.round(GameplayLayout.REPRESENTATIVE_HEIGHT * SCALE);

    private WitnessTestSupport() {
    }

    /** Plain-data features for rule and distribution tests: no native material involved. */
    static PuzzleContentFeatures features(double targetSimilarity,
            List<Double> candidateSimilarities) {
        return new PuzzleContentFeatures(0.0,
                new RegionContentSimilarity(targetSimilarity, candidateSimilarities), 0.0, 0.0,
                0.0, false, 0.9, 0.5, 0.4, 0);
    }

    /** Plain-data features with every measurement set explicitly. */
    static PuzzleContentFeatures features(double rawPanelDelta, double targetSimilarity,
            List<Double> candidateSimilarities, double targetVectorDelta, double gridMeanDelta) {
        return new PuzzleContentFeatures(rawPanelDelta,
                new RegionContentSimilarity(targetSimilarity, candidateSimilarities),
                targetVectorDelta, gridMeanDelta, gridMeanDelta, false, 0.9, 0.5, 0.4, 0);
    }

    /** Plain-data frame row with an explicit scope and identity metadata. */
    static WitnessFrameRow row(String sourceId, String roundScope, String baselineId,
            WitnessScope scope, long frameIndex, long timestampMs, String identity,
            PuzzleContentFeatures features) {
        boolean recognized = identity != null && !identity.isEmpty();
        return new WitnessFrameRow(sourceId, "640x360", 1, roundScope, baselineId, scope,
                frameIndex, timestampMs,
                recognized ? RecognitionResult.Status.RECOGNIZED
                        : RecognitionResult.Status.UNCERTAIN,
                recognized ? identity : "",
                recognized ? LiveRecognitionState.STABLE_RECOGNIZED
                        : LiveRecognitionState.UNCERTAIN,
                recognized ? 3 : 0, List.of(), features);
    }

    /** Production layout scaled by {@link #SCALE}, written and re-read through the production reader. */
    static GameplayLayout scaledLayout(Path workspace) throws IOException {
        GameplayLayout production =
                GameplayLayout.representative(PROJECT_ROOT.resolve(GameplayFixture.LAYOUT_REL));
        return EvaluationLayoutScaler.writeAndRead(
                workspace.resolve("witness-test-layout.csv"), production, WIDTH, HEIGHT);
    }

    /** Reference library of the committed dataset; the caller closes it. */
    static ReferenceFingerprintLibrary library() throws IOException {
        return ReferenceFingerprintLibrary.load(PROJECT_ROOT);
    }

    /** Witness over one layout and library; the library stays owned by the caller. */
    static PuzzleContentWitness witness(GameplayLayout layout, ReferenceFingerprintLibrary library) {
        return new PuzzleContentWitness(layout, library);
    }

    /** One candidate tile of a synthetic frame: content owner, fragment id and brightness. */
    record Tile(FingerprintId owner, int fragmentId, double brightness) {
        static Tile of(FingerprintId owner, int fragmentId) {
            return new Tile(owner, fragmentId, 1.0);
        }
    }

    /** A black frame with only the target ROI content specified; candidates stay empty. */
    static Mat frame(GameplayLayout layout, FingerprintId target) {
        return frame(layout, target, List.of());
    }

    /**
     * One synthetic gameplay frame: the target crop of {@code target} in the target ROI and one
     * crop per entry of {@code tiles} in row-major candidate order.
     *
     * @param layout layout the frame must match
     * @param target fingerprint whose committed target crop fills the target ROI
     * @param tiles candidate tiles; missing entries stay black
     */
    static Mat frame(GameplayLayout layout, FingerprintId target, List<Tile> tiles) {
        Loader.load(opencv_core.class);
        Mat frame = new Mat(HEIGHT, WIDTH, opencv_core.CV_8UC3, new Scalar(0, 0, 0, 0));
        try {
            GameplayRegion targetRegion = layout.target();
            try (Mat crop = crop(target, 0)) {
                writeRegion(frame, targetRegion, crop, 1.0);
            }
            List<GameplayRegion> candidateRegions = layout.candidatesRowMajor();
            for (int index = 0; index < tiles.size(); index++) {
                Tile tile = tiles.get(index);
                try (Mat crop = crop(tile.owner(), tile.fragmentId())) {
                    writeRegion(frame, candidateRegions.get(index), crop, tile.brightness());
                }
            }
            return frame;
        } catch (RuntimeException e) {
            frame.close();
            throw e;
        }
    }

    /** The same frame with one candidate's brightness scaled; the content stays identical. */
    static Mat brightened(GameplayLayout layout, Mat frame, int candidateIndex, double factor) {
        Mat copy = frame.clone();
        GameplayRegion region = layout.candidatesRowMajor().get(candidateIndex);
        try (Mat view = new Mat(copy, rect(region))) {
            view.convertTo(view, -1, factor, 0.0);
        }
        return copy;
    }

    /** The same frame with two candidates' contents exchanged: both positions change structure. */
    static Mat swapped(GameplayLayout layout, Mat frame, int firstCandidate, int secondCandidate) {
        Mat copy = frame.clone();
        List<GameplayRegion> regions = layout.candidatesRowMajor();
        Mat first = null;
        Mat second = null;
        try {
            first = cloneRegion(copy, regions.get(firstCandidate));
            second = cloneRegion(copy, regions.get(secondCandidate));
            writeRegion(copy, regions.get(firstCandidate), second, 1.0);
            writeRegion(copy, regions.get(secondCandidate), first, 1.0);
        } finally {
            if (first != null) {
                first.close();
            }
            if (second != null) {
                second.close();
            }
        }
        return copy;
    }

    /** The same frame with one candidate's content replaced by another reference crop. */
    static Mat replaced(GameplayLayout layout, Mat frame, int candidateIndex, FingerprintId owner,
            int fragmentId) {
        Mat copy = frame.clone();
        try (Mat crop = crop(owner, fragmentId)) {
            writeRegion(copy, layout.candidatesRowMajor().get(candidateIndex), crop, 1.0);
        }
        return copy;
    }

    /** Committed reference crop, resized to the ROI it is pasted into. */
    private static Mat crop(FingerprintId owner, int fragmentId) {
        Path path = fragmentId == 0
                ? PROJECT_ROOT.resolve("dataset/reference/" + owner.name().toLowerCase()
                        + "/target.png")
                : PROJECT_ROOT.resolve("dataset/reference/" + owner.name().toLowerCase()
                        + "/fragments/fragment_" + fragmentId + ".png");
        Mat image = opencv_imgcodecs.imread(path.toString(), opencv_imgcodecs.IMREAD_GRAYSCALE);
        if (image == null || image.empty()) {
            throw new IllegalStateException("Could not read " + path);
        }
        return image;
    }

    /**
     * Resizes {@code content} to the region, applies {@code brightness} and writes it into the
     * frame at that region. The destination is always written through a same-sized copy, never by
     * writing a differently sized Mat into a view (which would detach the view instead of filling
     * it).
     */
    private static void writeRegion(Mat frame, GameplayRegion region, Mat content, double brightness) {
        try (Mat bgr = new Mat(); Mat sized = new Mat(); Mat scaled = new Mat();
                Mat view = new Mat(frame, rect(region))) {
            if (content.channels() == 1) {
                opencv_imgproc.cvtColor(content, bgr, opencv_imgproc.COLOR_GRAY2BGR);
            } else if (content.channels() == 4) {
                opencv_imgproc.cvtColor(content, bgr, opencv_imgproc.COLOR_BGRA2BGR);
            } else {
                content.copyTo(bgr);
            }
            opencv_imgproc.resize(bgr, sized, new Size(region.width(), region.height()), 0, 0,
                    opencv_imgproc.INTER_AREA);
            if (brightness == 1.0) {
                sized.copyTo(view);
            } else {
                sized.convertTo(scaled, opencv_core.CV_8UC3, brightness, 0.0);
                scaled.copyTo(view);
            }
        }
    }

    private static Mat cloneRegion(Mat frame, GameplayRegion region) {
        try (Mat view = new Mat(frame, rect(region))) {
            return view.clone();
        }
    }

    private static Rect rect(GameplayRegion region) {
        return new Rect(region.x(), region.y(), region.width(), region.height());
    }
}
