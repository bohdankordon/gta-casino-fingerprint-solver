package io.github.bohdankordon.casinofingerprint.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import java.util.Set;
import java.util.TreeSet;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Synthetic score-logic checks for the control-state decision plus one geometric integration
 * test over a drawn frame: no private recordings anywhere in CI.
 */
class PuzzleControlStateDetectorTest {
    private static final ControlThresholds LIMITS = ControlThresholds.PRODUCTION_1440P;

    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    private static int[] scores(int winner, int winnerScore, int rest) {
        int[] scores = new int[8];
        for (int tile = 0; tile < 8; tile++) {
            scores[tile] = tile == winner ? winnerScore : rest;
        }
        return scores;
    }

    private static int[] inners(int... selected) {
        int[] inners = new int[8];
        for (int tile = 0; tile < 8; tile++) {
            inners[tile] = 23;
        }
        for (int candidate : selected) {
            inners[candidate] = 76;
        }
        return inners;
    }

    @Test
    void cleanWinnerReadsFocusAndEmptySelection() {
        PuzzleControlState state = PuzzleControlStateDetector.decide(
                new int[] {346, 56, 71, 0, 0, 0, 0, 0}, inners(), LIMITS);
        assertTrue(state.valid(), "clean start frame is actionable");
        assertEquals(GridPosition.C0, state.focus().orElseThrow());
        assertTrue(state.selected().isEmpty(), "nothing selected");
    }

    @Test
    void selectedSetFollowsInteriorBrightness() {
        PuzzleControlState state = PuzzleControlStateDetector.decide(
                scores(1, 348, 70), new int[] {23, 60, 22, 23, 71, 76, 91, 23}, LIMITS);
        assertTrue(state.valid(), "completed round reads clean");
        assertEquals(GridPosition.C1, state.focus().orElseThrow());
        assertEquals(new TreeSet<>(Set.of(1, 4, 5, 6)), state.selected());
    }

    @Test
    void blankPanelHasNoWinner() {
        PuzzleControlState state =
                PuzzleControlStateDetector.decide(new int[8], inners(), LIMITS);
        assertTrue(!state.valid(), "blank panel is never actionable");
        assertTrue(state.focus().isEmpty(), "no focus without validity");
        assertTrue(state.selected().isEmpty(), "no selection without validity");
        assertTrue(state.detail().startsWith("NO_WINNER"), "reason: " + state.detail());
    }

    @Test
    void contestedWinnerIsAmbiguous() {
        PuzzleControlState state = PuzzleControlStateDetector.decide(
                new int[] {76, 348, 0, 234, 0, 121, 0, 0}, inners(1, 4, 5, 6), LIMITS);
        assertTrue(!state.valid(), "weak overlay edge refuses the frame");
        assertTrue(state.detail().startsWith("MARGIN"), "reason: " + state.detail());
    }

    @Test
    void floodedTileTripsTheCeiling() {
        PuzzleControlState state = PuzzleControlStateDetector.decide(
                new int[] {76, 348, 0, 1555, 0, 1344, 0, 0}, inners(1, 4, 5, 6), LIMITS);
        assertTrue(!state.valid(), "overlay banner refuses the frame");
        assertTrue(state.detail().startsWith("CEILING"), "reason: " + state.detail());
    }

    @Test
    void tiedWinnerIsAmbiguous() {
        PuzzleControlState state =
                PuzzleControlStateDetector.decide(scores(0, 0, 0), inners(), LIMITS);
        assertTrue(!state.valid(), "a tie is never a winner");
    }

    @Test
    void thresholdEdgesAreInclusive() {
        int[] tight = {150, 0, 0, 0, 0, 0, 0, 0};
        PuzzleControlState floor =
                PuzzleControlStateDetector.decide(tight, inners(), LIMITS);
        assertTrue(floor.valid(), "winner exactly on the floor passes");
        int[] margin = {300, 150, 0, 0, 0, 0, 0, 0};
        PuzzleControlState edged =
                PuzzleControlStateDetector.decide(margin, inners(), LIMITS);
        assertTrue(edged.valid(), "margin exactly on the bound passes");
        int[] selectEdge = {23, 23, 23, 23, 23, 23, 23, 23};
        selectEdge[2] = 45;
        PuzzleControlState selected = PuzzleControlStateDetector.decide(
                scores(0, 300, 0), selectEdge, LIMITS);
        assertTrue(selected.valid(), "frame stays valid");
        assertEquals(new TreeSet<>(Set.of(2)), selected.selected(),
                "floor plus delta selects the tile");
    }

    @Test
    void selectionNeedsBothFloorAndDelta() {
        int[] darkish = {23, 23, 23, 23, 23, 23, 23, 23};
        darkish[2] = 44;
        PuzzleControlState belowFloor = PuzzleControlStateDetector.decide(
                scores(0, 300, 0), darkish, LIMITS);
        assertTrue(belowFloor.selected().isEmpty(), "below the floor never selects");
        int[] raised = {40, 40, 40, 40, 40, 40, 40, 59};
        PuzzleControlState noDelta = PuzzleControlStateDetector.decide(
                scores(0, 300, 0), raised, LIMITS);
        assertTrue(noDelta.selected().isEmpty(), "without the delta never selects");
    }

    @Test
    void scoreShapesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> PuzzleControlStateDetector.decide(
                new int[7], inners(), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> PuzzleControlStateDetector.decide(
                scores(0, 300, 0), new int[2], LIMITS));
    }

    @Test
    void drawnFrameIntegratesGeometryAndDecision() throws Exception {
        GameplayLayout layout =
                GameplayLayout.representative(Stage5TestSupport.PROJECT_ROOT.resolve(
                        io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture.LAYOUT_REL));
        try (Mat frame = new Mat(1440, 2560, org.bytedeco.opencv.global.opencv_core.CV_8UC3,
                Scalar.BLACK)) {
            paintBrackets(frame, 0);
            paintInterior(frame, 5, 76);
            PuzzleControlState state = PuzzleControlStateDetector.detect(
                    frame, layout, ControlThresholds.PRODUCTION_1440P);
            assertTrue(state.valid(), "drawn focus reads: " + state);
            assertEquals(GridPosition.C0, state.focus().orElseThrow());
            assertEquals(new TreeSet<>(Set.of(5)), state.selected());
        }
        try (Mat black = new Mat(1440, 2560, org.bytedeco.opencv.global.opencv_core.CV_8UC3,
                Scalar.BLACK)) {
            PuzzleControlState state = PuzzleControlStateDetector.detect(
                black, layout, ControlThresholds.PRODUCTION_1440P);
            assertTrue(!state.valid(), "blank frame is never actionable");
        }
    }

    /**
     * Draws a thin bracket-like arm in the real band geometry of the focused tile: the width
     * parameter paints one pixel wider (OpenCV rectangles include both endpoints), so 2
     * paints a 3 px arm of 456 hot pixels, inside every bound with a clear margin.
     */
    private static void paintBrackets(Mat frame, int tile) throws Exception {
        paintArm(frame, tile, 2);
    }

    private static void paintArm(Mat frame, int tile, int armWidth) throws Exception {
        GameplayLayout layout =
                GameplayLayout.representative(Stage5TestSupport.PROJECT_ROOT.resolve(
                        io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture.LAYOUT_REL));
        var region = layout.candidatesRowMajor().get(tile);
        Scalar white = new Scalar(215, 215, 215, 0);
        opencv_imgproc.rectangle(frame,
                new Rect(region.x() - 24, region.y(), armWidth, region.height()), white, -1,
                8, 0);
    }

    private static void paintInterior(Mat frame, int tile, int gray) throws Exception {
        GameplayLayout layout =
                GameplayLayout.representative(Stage5TestSupport.PROJECT_ROOT.resolve(
                        io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture.LAYOUT_REL));
        var region = layout.candidatesRowMajor().get(tile);
        Scalar fill = new Scalar(gray, gray, gray, 0);
        opencv_imgproc.rectangle(frame,
                new Rect(region.x() + 6, region.y() + 6, region.width() - 12,
                        region.height() - 12),
                fill, -1, 8, 0);
    }
}
