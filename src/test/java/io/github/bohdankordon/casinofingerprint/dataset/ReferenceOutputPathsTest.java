package io.github.bohdankordon.casinofingerprint.dataset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies manifest output paths cannot escape the canonical dataset tree. */
class ReferenceOutputPathsTest {
    @Test
    void absolutePathsAreRejected() throws IOException {
        for (String bad : List.of(
                "/dataset/reference/fp_1/target.png",
                "C:/dataset/reference/fp_1/target.png")) {
            List<ReferenceCrop> crops = withOutputPath(validCrops(), 0, bad);
            assertThrows(IllegalArgumentException.class,
                    () -> ReferenceLayout.validate(crops, 1500, 1900),
                    "Absolute path should be rejected: " + bad);
        }
    }

    @Test
    void traversalPathsAreRejected() throws IOException {
        List<ReferenceCrop> crops =
                withOutputPath(validCrops(), 0, "dataset/reference/fp_1/../../outside.png");
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceLayout.validate(crops, 1500, 1900));
    }

    @Test
    void pathsOutsideDatasetReferenceAreRejected() throws IOException {
        for (String bad : List.of(
                "dataset/source/evil.png",
                "other/fp_1/target.png")) {
            List<ReferenceCrop> crops = withOutputPath(validCrops(), 0, bad);
            assertThrows(IllegalArgumentException.class,
                    () -> ReferenceLayout.validate(crops, 1500, 1900),
                    "Path outside dataset/reference should be rejected: " + bad);
        }
    }

    @Test
    void fingerprintDirectoryMustMatchFingerprintId() throws IOException {
        List<ReferenceCrop> crops =
                withOutputPath(validCrops(), 0, "dataset/reference/fp_2/target.png");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ReferenceLayout.validate(crops, 1500, 1900));
        assertTrue(failure.getMessage().contains("dataset/reference/fp_1/target.png"),
                "Failure should name the expected canonical path, got: " + failure.getMessage());
    }

    @Test
    void fragmentNumberMustMatchFragmentId() throws IOException {
        List<ReferenceCrop> crops = validCrops();
        int index = indexOfFragment(crops, "FP_2", 3);
        List<ReferenceCrop> mutated =
                withOutputPath(crops, index, "dataset/reference/fp_2/fragments/fragment_2.png");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ReferenceLayout.validate(mutated, 1500, 1900));
        assertTrue(failure.getMessage().contains("dataset/reference/fp_2/fragments/fragment_3.png"),
                "Failure should name the expected canonical path, got: " + failure.getMessage());
    }

    private static List<ReferenceCrop> validCrops() throws IOException {
        Path manifest = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .resolve(ReferenceDatasetGenerator.MANIFEST_REL);
        return new ArrayList<>(ReferenceLayout.read(manifest));
    }

    private static List<ReferenceCrop> withOutputPath(List<ReferenceCrop> base, int index, String badPath) {
        List<ReferenceCrop> copy = new ArrayList<>(base);
        ReferenceCrop original = copy.get(index);
        copy.set(index, new ReferenceCrop(original.fingerprintId(), original.assetType(),
                original.fragmentId(), original.x(), original.y(),
                original.width(), original.height(), badPath));
        return copy;
    }

    private static int indexOfFragment(List<ReferenceCrop> crops, String fingerprintId, int fragmentId) {
        for (int i = 0; i < crops.size(); i++) {
            ReferenceCrop crop = crops.get(i);
            if (crop.fingerprintId().name().equals(fingerprintId)
                    && crop.assetType() == ReferenceAssetType.FRAGMENT
                    && crop.fragmentId() == fragmentId) {
                return i;
            }
        }
        throw new IllegalStateException("Test setup is missing " + fingerprintId + " fragment " + fragmentId);
    }
}
