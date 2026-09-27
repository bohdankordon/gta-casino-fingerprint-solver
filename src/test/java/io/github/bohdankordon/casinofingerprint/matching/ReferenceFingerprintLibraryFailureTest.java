package io.github.bohdankordon.casinofingerprint.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.dataset.ReferenceAssetType;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceCrop;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceLayout;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Partial-load failure behavior of {@link ReferenceFingerprintLibrary#load(Path)}.
 *
 * <p>Each failure case breaks an asset that is read <em>after</em> the FP_1 target, fragment 1 and
 * fragment 2 have already been decoded and normalized, so the load fails while normalized profiles
 * for the current fingerprint are still pending ownership transfer. The library must report the
 * broken asset and close everything it created; that release is an ownership property of the
 * loading loop rather than something a test can observe through Bytedeco, so the tests below pin the
 * failure path, the reported cause and the still-working success path instead of faking a memory
 * assertion.
 */
class ReferenceFingerprintLibraryFailureTest {
    /** Read third within FP_1, so earlier FP_1 profiles have already been normalized. */
    private static final String FP_1_FRAGMENT_3 = "dataset/reference/fp_1/fragments/fragment_3.png";

    @BeforeAll
    static void loadNativeLibrary() {
        MatchingTestSupport.loadNativeLibrary();
    }

    @Test
    void missingAssetAfterEarlierProfilesFailsClearly(@TempDir Path tempRoot) throws Exception {
        writeSyntheticReferenceTree(tempRoot, FP_1_FRAGMENT_3, BrokenAsset.MISSING);
        assertPartialLoadFailureIsReported(tempRoot, "fragment_3");
    }

    @Test
    void undecodableAssetAfterEarlierProfilesFailsClearly(@TempDir Path tempRoot) throws Exception {
        writeSyntheticReferenceTree(tempRoot, FP_1_FRAGMENT_3, BrokenAsset.UNDECODABLE);
        assertPartialLoadFailureIsReported(tempRoot, "fragment_3");
    }

    @Test
    void completeSyntheticTreeStillLoadsAndCloses(@TempDir Path tempRoot) throws Exception {
        writeSyntheticReferenceTree(tempRoot, null, BrokenAsset.INTACT);
        try (ReferenceFingerprintLibrary library = ReferenceFingerprintLibrary.load(tempRoot)) {
            for (FingerprintId id : FingerprintId.values()) {
                Mat target = library.target(id);
                assertEquals(256, target.cols(), id + " target width");
                assertEquals(384, target.rows(), id + " target height");
                for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
                    Mat fragment = library.fragment(id, fragmentId);
                    assertEquals(128, fragment.cols(), id + " fragment " + fragmentId + " width");
                    assertEquals(128, fragment.rows(), id + " fragment " + fragmentId + " height");
                }
            }
        }
    }

    private static void assertPartialLoadFailureIsReported(Path tempRoot, String expectedAsset) {
        // The assets read before the broken one are valid, so the failure happens with normalized
        // profiles for FP_1 already created and not yet owned by any library.
        assertDecodable(tempRoot.resolve(relative("dataset/reference/fp_1/target.png")));
        assertDecodable(tempRoot.resolve(relative("dataset/reference/fp_1/fragments/fragment_1.png")));
        assertDecodable(tempRoot.resolve(relative("dataset/reference/fp_1/fragments/fragment_2.png")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ReferenceFingerprintLibrary.load(tempRoot));
        assertTrue(failure.getMessage() != null && failure.getMessage().contains(expectedAsset),
                "Failure must identify the broken asset, got: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("fp_1"),
                "Failure must identify the fingerprint directory, got: " + failure.getMessage());
    }

    private static void assertDecodable(Path asset) {
        try (Mat decoded = opencv_imgcodecs.imread(asset.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
            assertFalse(decoded == null || decoded.empty(), "Fixture setup asset must decode: " + asset);
        }
    }

    private static Path relative(String repoRelative) {
        return Path.of(repoRelative.replace('/', File.separatorChar));
    }

    /** How the synthetic tree differs from a complete, decodable Stage 1 asset tree. */
    private enum BrokenAsset {
        INTACT,
        MISSING,
        UNDECODABLE
    }

    /**
     * Writes the real Stage 1 manifest (validation contract unchanged) plus synthetic decodable
     * assets below {@code root}, leaving {@code brokenAsset} missing or replaced with junk bytes.
     */
    private static void writeSyntheticReferenceTree(Path root, String brokenAsset, BrokenAsset kind)
            throws IOException {
        Path manifest = root.resolve(relative(ReferenceFingerprintLibrary.MANIFEST_REL));
        Files.createDirectories(manifest.getParent());
        Files.copy(MatchingTestSupport.PROJECT_ROOT.resolve(ReferenceFingerprintLibrary.MANIFEST_REL),
                manifest);
        for (ReferenceCrop crop : ReferenceLayout.read(manifest)) {
            boolean broken = crop.outputPath().equals(brokenAsset);
            if (broken && kind == BrokenAsset.MISSING) {
                continue;
            }
            Path output = root.resolve(relative(crop.outputPath()));
            Files.createDirectories(output.getParent());
            if (broken) {
                Files.writeString(output, "this is not a decodable image", StandardCharsets.UTF_8);
            } else {
                writeSyntheticAsset(output, crop.assetType());
            }
        }
    }

    private static void writeSyntheticAsset(Path output, ReferenceAssetType assetType) {
        int width = assetType == ReferenceAssetType.TARGET ? 32 : 24;
        int height = assetType == ReferenceAssetType.TARGET ? 48 : 24;
        try (Mat image = new Mat(height, width, opencv_core.CV_8UC3)) {
            try (UByteIndexer indexer = image.createIndexer()) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int intensity = (x * 7 + y * 13) % 255;
                        indexer.put(y, x, 0, intensity);
                        indexer.put(y, x, 1, intensity);
                        indexer.put(y, x, 2, intensity);
                    }
                }
            }
            assertTrue(opencv_imgcodecs.imwrite(output.toString(), image),
                    "Synthetic asset must be writable: " + output);
        }
    }
}
