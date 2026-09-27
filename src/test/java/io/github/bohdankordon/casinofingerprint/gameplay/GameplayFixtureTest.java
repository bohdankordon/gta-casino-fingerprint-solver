package io.github.bohdankordon.casinofingerprint.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Validates the representative gameplay fixture, layout and ROI extraction. */
class GameplayFixtureTest {
    private static Path projectRoot;
    private static Path sourcePath;
    private static Path manifestPath;

    @BeforeAll
    static void locateProject() {
        Loader.load(opencv_core.class);
        projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        sourcePath = projectRoot.resolve(GameplayFixture.SOURCE_REL);
        manifestPath = projectRoot.resolve(GameplayFixture.LAYOUT_REL);
    }

    @Test
    void gameplaySourceFixtureExists() {
        assertTrue(Files.isRegularFile(sourcePath), "Gameplay fixture must exist: " + sourcePath);
    }

    @Test
    void openCvDecodesFixtureAt2560x1440() {
        try (Mat frame = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
            assertTrue(frame != null && !frame.empty(), "OpenCV must decode: " + sourcePath);
            assertEquals(GameplayFixture.EXPECTED_WIDTH, frame.cols(), "Fixture width");
            assertEquals(GameplayFixture.EXPECTED_HEIGHT, frame.rows(), "Fixture height");
        }
    }

    @Test
    void fixtureBytesMatchDocumentedSha256() throws Exception {
        byte[] bytes = Files.readAllBytes(sourcePath);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha256.digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        assertEquals(GameplayFixture.EXPECTED_SOURCE_SHA256.toLowerCase(Locale.ROOT),
                hex.toString(),
                "Gameplay fixture bytes changed; provenance requires byte-for-byte preservation");
    }

    @Test
    void layoutContainsOneTargetAndCandidatesZeroThroughSeven() throws Exception {
        GameplayLayout layout = GameplayLayout.representative(manifestPath);
        assertEquals(2560, layout.sourceWidth());
        assertEquals(1440, layout.sourceHeight());
        GameplayRegion target = layout.target();
        assertEquals(GameplayRegionType.TARGET, target.regionType());
        List<GameplayRegion> candidates = layout.candidatesRowMajor();
        assertEquals(8, candidates.size(), "Exactly 8 candidates");
        Set<Integer> indices = new HashSet<>();
        for (GameplayRegion candidate : candidates) {
            assertEquals(GameplayRegionType.CANDIDATE, candidate.regionType());
            assertTrue(indices.add(candidate.candidateIndex()),
                    "Duplicate candidate index: " + candidate.candidateIndex());
        }
        assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6, 7), indices, "No missing candidate index");
        for (int i = 0; i < 8; i++) {
            assertEquals(i, candidates.get(i).candidateIndex(), "Row-major order at position " + i);
        }
    }

    @Test
    void allRoisArePositiveAndInside2560x1440() throws Exception {
        GameplayLayout layout = GameplayLayout.representative(manifestPath);
        for (GameplayRegion region : layout.regions()) {
            assertTrue(region.x() >= 0 && region.y() >= 0, "Non-negative origin: " + region);
            assertTrue(region.width() > 0 && region.height() > 0, "Positive size: " + region);
            assertTrue(region.x() + region.width() <= 2560 && region.y() + region.height() <= 1440,
                    "Inside 2560x1440: " + region);
        }
    }

    @Test
    void extractorReturnsOneTargetAndEightRowMajorCandidates() throws Exception {
        GameplayLayout layout = GameplayLayout.representative(manifestPath);
        try (Mat frame = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
                ExtractedPuzzleFrame puzzle = new GameplayFrameExtractor(layout).extract(frame)) {
            assertEquals(layout.target().width(), puzzle.target().cols(), "Target width");
            assertEquals(layout.target().height(), puzzle.target().rows(), "Target height");
            assertEquals(8, puzzle.candidates().size(), "Exactly 8 candidates");
            List<GameplayRegion> regions = layout.candidatesRowMajor();
            for (int i = 0; i < 8; i++) {
                assertEquals(i, regions.get(i).candidateIndex(), "Manifest order " + i);
                assertEquals(regions.get(i).width(), puzzle.candidates().get(i).cols(),
                        "Candidate " + i + " width");
                assertEquals(regions.get(i).height(), puzzle.candidates().get(i).rows(),
                        "Candidate " + i + " height");
            }
        }
    }

    @Test
    void extractionDoesNotMutateSource() throws Exception {
        GameplayLayout layout = GameplayLayout.representative(manifestPath);
        try (Mat frame = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
                Mat before = frame.clone();
                ExtractedPuzzleFrame puzzle = new GameplayFrameExtractor(layout).extract(frame)) {
            assertEquals(0, countDifferentPixels(before, frame), "Extraction must not mutate the source Mat");
        }
    }

    @Test
    void extractedMatsAreIndependentClones() throws Exception {
        GameplayLayout layout = GameplayLayout.representative(manifestPath);
        try (Mat frame = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
                ExtractedPuzzleFrame puzzle = new GameplayFrameExtractor(layout).extract(frame)) {
            List<Mat> snapshots = new ArrayList<>();
            snapshots.add(puzzle.target().clone());
            for (Mat candidate : puzzle.candidates()) {
                snapshots.add(candidate.clone());
            }
            try {
                frame.convertTo(frame, -1, 0, 0);
                assertEquals(0, countDifferentPixels(snapshots.get(0), puzzle.target()),
                        "Target must survive source overwrite (clone, not view)");
                for (int i = 0; i < 8; i++) {
                    assertEquals(0, countDifferentPixels(snapshots.get(i + 1), puzzle.candidates().get(i)),
                            "Candidate " + i + " must survive source overwrite (clone, not view)");
                }
            } finally {
                for (Mat snapshot : snapshots) {
                    snapshot.close();
                }
            }
        }
    }

    private static int countDifferentPixels(Mat a, Mat b) {
        assertEquals(a.rows(), b.rows(), "Row count");
        assertEquals(a.cols(), b.cols(), "Column count");
        assertEquals(a.type(), b.type(), "Mat type");
        try (Mat diff = new Mat()) {
            opencv_core.absdiff(a, b, diff);
            try (Mat gray = new Mat()) {
                if (diff.channels() > 1) {
                    opencv_core.extractChannel(diff, gray, 0);
                    try (Mat rest = new Mat()) {
                        for (int c = 1; c < diff.channels(); c++) {
                            opencv_core.extractChannel(diff, rest, c);
                            opencv_core.bitwise_or(gray, rest, gray);
                        }
                    }
                } else {
                    diff.copyTo(gray);
                }
                return opencv_core.countNonZero(gray);
            }
        }
    }
}
