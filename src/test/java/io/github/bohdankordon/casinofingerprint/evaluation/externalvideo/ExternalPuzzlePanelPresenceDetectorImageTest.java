package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tiny generated-matrix presence checks: synthetic frames against a tiny layout, no images.
 */
class ExternalPuzzlePanelPresenceDetectorImageTest {
    @BeforeAll
    static void loadNativeLibrary() {
        org.bytedeco.javacpp.Loader.load(opencv_core.class);
    }

    private static GameplayLayout tinyLayout(Path dir) throws Exception {
        String csv = "region_type,candidate_index,x,y,width,height\n"
                + "PANEL,,100,50,600,500\n"
                + "TARGET,,400,100,150,200\n"
                + "CANDIDATE,0,150,150,40,40\n"
                + "CANDIDATE,1,210,150,40,40\n"
                + "CANDIDATE,2,150,220,40,40\n"
                + "CANDIDATE,3,210,220,40,40\n"
                + "CANDIDATE,4,150,290,40,40\n"
                + "CANDIDATE,5,210,290,40,40\n"
                + "CANDIDATE,6,150,360,40,40\n"
                + "CANDIDATE,7,210,360,40,40\n";
        Path manifest = dir.resolve("tiny-layout.csv");
        Files.writeString(manifest, csv, StandardCharsets.UTF_8);
        return GameplayLayout.read(manifest, 800, 600);
    }

    private static void fillRect(Mat bgr, Rect box, Scalar color) {
        opencv_imgproc.rectangle(bgr, box, color, -1, opencv_imgproc.LINE_8, 0);
    }

    private static Mat presentFrame(GameplayLayout layout) {
        Mat bgr = new Mat(layout.sourceHeight(), layout.sourceWidth(),
                opencv_core.CV_8UC3, Scalar.BLACK);
        Rect panel = ExternalPuzzlePanelPresenceDetector.panelRect(layout);
        Scalar white = new Scalar(220, 220, 220, 0);
        fillRect(bgr, ExternalPuzzlePanelPresenceDetector.anchorRect(panel, 0.04, 0.12, 0.012, 0.034), white);
        fillRect(bgr, ExternalPuzzlePanelPresenceDetector.anchorRect(panel, 0.80, 0.92, 0.012, 0.034), white);
        fillRect(bgr, ExternalPuzzlePanelPresenceDetector.anchorRect(panel, 0.04, 0.12, 0.145, 0.167), white);
        fillRect(bgr, ExternalPuzzlePanelPresenceDetector.anchorRect(panel, 0.89, 0.95, 0.085, 0.125), white);
        return bgr;
    }

    @Test
    void syntheticPresentFrameIsPresent(@TempDir Path temp) throws Exception {
        GameplayLayout layout = tinyLayout(temp);
        try (Mat frame = presentFrame(layout)) {
            ExternalPanelPresenceResult result =
                    ExternalPuzzlePanelPresenceDetector.detect(frame, layout);
            assertEquals(ExternalPanelPresence.PRESENT, result.presence());
        }
    }

    @Test
    void syntheticDarkFrameIsAbsent(@TempDir Path temp) throws Exception {
        GameplayLayout layout = tinyLayout(temp);
        try (Mat frame = new Mat(layout.sourceHeight(), layout.sourceWidth(),
                opencv_core.CV_8UC3, Scalar.BLACK)) {
            ExternalPanelPresenceResult result =
                    ExternalPuzzlePanelPresenceDetector.detect(frame, layout);
            assertEquals(ExternalPanelPresence.ABSENT, result.presence());
        }
    }

    @Test
    void syntheticGreenOverlayForcesAbsent(@TempDir Path temp) throws Exception {
        GameplayLayout layout = tinyLayout(temp);
        try (Mat frame = presentFrame(layout)) {
            Rect panel = ExternalPuzzlePanelPresenceDetector.panelRect(layout);
            fillRect(frame,
                    ExternalPuzzlePanelPresenceDetector.anchorRect(panel, 0.30, 0.70, 0.40, 0.60),
                    new Scalar(30, 220, 30, 0));
            ExternalPanelPresenceResult result =
                    ExternalPuzzlePanelPresenceDetector.detect(frame, layout);
            assertEquals(ExternalPanelPresence.ABSENT, result.presence());
        }
    }
}
