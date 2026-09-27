package io.github.bohdankordon.casinofingerprint.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceDatasetTest {
    private static Path projectRoot;
    private static Path sourcePath;
    private static Path manifestPath;

    @BeforeAll
    static void locateProject() {
        Loader.load(opencv_core.class);
        projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        sourcePath = projectRoot.resolve(ReferenceDatasetGenerator.SOURCE_REL);
        manifestPath = projectRoot.resolve(ReferenceDatasetGenerator.MANIFEST_REL);
    }

    @Test
    void sourceImageLoadsWithExpectedDimensions() {
        try (Mat source = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
            assertTrue(source != null && !source.empty(), "Source image must decode: " + sourcePath);
            assertEquals(1500, source.cols(), "Source width");
            assertEquals(1900, source.rows(), "Source height");
        }
    }

    @Test
    void manifestContainsFourTargetsSixteenFragmentsTwentyTotal() throws IOException {
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        assertEquals(20, crops.size(), "Total records");
        assertEquals(4, crops.stream().filter(c -> c.assetType() == ReferenceAssetType.TARGET).count());
        assertEquals(16, crops.stream().filter(c -> c.assetType() == ReferenceAssetType.FRAGMENT).count());
    }

    @Test
    void eachFingerprintHasOneTargetAndFourFragments() throws IOException {
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        Map<FingerprintId, List<ReferenceCrop>> byId =
                crops.stream().collect(Collectors.groupingBy(ReferenceCrop::fingerprintId));
        assertEquals(Set.of(FingerprintId.values()), byId.keySet());
        for (FingerprintId id : FingerprintId.values()) {
            List<ReferenceCrop> group = byId.get(id);
            assertEquals(1, group.stream().filter(c -> c.assetType() == ReferenceAssetType.TARGET).count(),
                    id + " targets");
            assertEquals(4, group.stream().filter(c -> c.assetType() == ReferenceAssetType.FRAGMENT).count(),
                    id + " fragments");
        }
    }

    @Test
    void fragmentIdsAreOneThroughFour() throws IOException {
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        for (FingerprintId id : FingerprintId.values()) {
            List<Integer> ids = crops.stream()
                    .filter(c -> c.fingerprintId() == id && c.assetType() == ReferenceAssetType.FRAGMENT)
                    .map(ReferenceCrop::fragmentId)
                    .sorted()
                    .toList();
            assertEquals(List.of(1, 2, 3, 4), ids, id + " fragment ids");
        }
    }

    @Test
    void cropRectanglesArePositiveAndInsideSource() throws IOException {
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        for (ReferenceCrop crop : crops) {
            assertTrue(crop.x() >= 0 && crop.y() >= 0, "Non-negative origin: " + crop);
            assertTrue(crop.width() > 0 && crop.height() > 0, "Positive size: " + crop);
            assertTrue(crop.x() + crop.width() <= 1500 && crop.y() + crop.height() <= 1900,
                    "Inside 1500x1900: " + crop);
        }
    }

    @Test
    void outputPathsAreUnique() throws IOException {
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        Set<String> paths = new HashSet<>();
        for (ReferenceCrop crop : crops) {
            assertTrue(paths.add(crop.outputPath()), "Duplicate output_path: " + crop.outputPath());
        }
        assertEquals(20, paths.size());
    }

    @Test
    void generatedFilesExistWithManifestDimensions() throws IOException {
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        for (ReferenceCrop crop : crops) {
            Path output = projectRoot.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
            assertTrue(Files.isRegularFile(output), "Missing canonical asset: " + output);
            try (Mat decoded = opencv_imgcodecs.imread(output.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
                assertTrue(decoded != null && !decoded.empty(), "Must decode: " + output);
                assertEquals(crop.width(), decoded.cols(), "Width: " + output);
                assertEquals(crop.height(), decoded.rows(), "Height: " + output);
            }
        }
    }

    @Test
    void regeneratingProducesPixelEquivalentOutput(@TempDir Path tempDir) throws IOException {
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        // Copy source + manifest into an isolated root and regenerate there,
        // so committed bytes are never touched by this test.
        Path tempSource = tempDir.resolve(ReferenceDatasetGenerator.SOURCE_REL);
        Path tempManifest = tempDir.resolve(ReferenceDatasetGenerator.MANIFEST_REL);
        Files.createDirectories(tempSource.getParent());
        Files.createDirectories(tempManifest.getParent());
        Files.copy(sourcePath, tempSource);
        Files.copy(manifestPath, tempManifest);
        ReferenceDatasetGenerator.generate(tempDir);
        try (Mat source = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
            assertTrue(!source.empty());
            for (ReferenceCrop crop : crops) {
                Path committed = projectRoot.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
                Path regenerated = tempDir.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
                assertTrue(Files.isRegularFile(regenerated), "Regenerated missing: " + regenerated);
                try (Mat expected = new Mat(source, new Rect(crop.x(), crop.y(), crop.width(), crop.height())).clone();
                        Mat committedPixels = opencv_imgcodecs.imread(
                                committed.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
                        Mat regeneratedPixels = opencv_imgcodecs.imread(
                                regenerated.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
                    assertTrue(!committedPixels.empty() && !regeneratedPixels.empty());
                    assertEquals(0, countDifferentPixels(expected, committedPixels),
                            "Committed pixels differ from source crop: " + committed);
                    assertEquals(0, countDifferentPixels(expected, regeneratedPixels),
                            "Regenerated pixels differ from source crop: " + regenerated);
                    assertEquals(0, countDifferentPixels(committedPixels, regeneratedPixels),
                            "Regeneration changed pixels: " + crop.outputPath());
                }
            }
        }
        // Determinism: a second run over the same temp root must not change pixels.
        List<Mat> firstRun = readAll(tempDir, crops);
        try {
            ReferenceDatasetGenerator.generate(tempDir);
            List<Mat> secondRun = readAll(tempDir, crops);
            try {
                assertEquals(firstRun.size(), secondRun.size());
                for (int i = 0; i < firstRun.size(); i++) {
                    assertEquals(0, countDifferentPixels(firstRun.get(i), secondRun.get(i)),
                            "Non-deterministic regeneration: " + crops.get(i).outputPath());
                }
            } finally {
                secondRun.forEach(Mat::close);
            }
        } finally {
            firstRun.forEach(Mat::close);
        }
    }

    private static List<Mat> readAll(Path root, List<ReferenceCrop> crops) {
        return crops.stream()
                .sorted(Comparator.comparing(ReferenceCrop::outputPath))
                .map(crop -> {
                    Path output = root.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
                    Mat decoded = opencv_imgcodecs.imread(output.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
                    if (decoded == null || decoded.empty()) {
                        throw new IllegalStateException("Could not decode: " + output);
                    }
                    return decoded;
                })
                .toList();
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
                // Collapse multi-channel differences: any non-zero across channels counts.
                if (diff.channels() > 1) {
                    // gray already holds OR across channels
                }
                return opencv_core.countNonZero(gray);
            }
        }
    }
}
