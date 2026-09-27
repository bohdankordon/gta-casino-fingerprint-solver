package io.github.bohdankordon.casinofingerprint.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.IOException;
import java.nio.file.Path;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reference loading, normalization and ownership of the Stage 1 material. */
class ReferenceFingerprintLibraryTest {
    @BeforeAll
    static void loadNativeLibrary() {
        MatchingTestSupport.loadNativeLibrary();
    }

    @Test
    void allFourReferenceTargetsLoadAsTargetProfiles() throws Exception {
        try (ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary()) {
            for (FingerprintId id : FingerprintId.values()) {
                Mat target = library.target(id);
                assertFalse(target.empty(), id + " target must not be empty");
                assertEquals(256, target.cols(), id + " target width");
                assertEquals(384, target.rows(), id + " target height");
                assertEquals(opencv_core.CV_8UC1, target.type(), id + " target type");
                assertTrue(opencv_core.countNonZero(target) > 0, id + " target must carry ridge structure");
            }
        }
    }

    @Test
    void everyTargetHasExactlyReferenceFragmentsOneToFour() throws Exception {
        try (ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary()) {
            for (FingerprintId id : FingerprintId.values()) {
                for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
                    Mat fragment = library.fragment(id, fragmentId);
                    assertFalse(fragment.empty(), id + " fragment " + fragmentId + " must not be empty");
                    assertEquals(128, fragment.cols(), id + " fragment " + fragmentId + " width");
                    assertEquals(128, fragment.rows(), id + " fragment " + fragmentId + " height");
                    assertEquals(opencv_core.CV_8UC1, fragment.type(),
                            id + " fragment " + fragmentId + " type");
                }
                assertThrows(IllegalArgumentException.class, () -> library.fragment(id, 0),
                        "fragment id 0 is not a reference fragment id");
                assertThrows(IllegalArgumentException.class, () -> library.fragment(id, 5),
                        "fragment id 5 is not a reference fragment id");
            }
        }
    }

    @Test
    void closeReleasesResourcesAndAccessAfterCloseFails() throws Exception {
        ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary();
        library.close();
        library.close();
        assertThrows(IllegalStateException.class, () -> library.target(FingerprintId.FP_1));
        assertThrows(IllegalStateException.class, () -> library.fragment(FingerprintId.FP_1, 1));
    }

    @Test
    void loadingTwiceProducesIdenticalProfiles() throws Exception {
        try (ReferenceFingerprintLibrary first = MatchingTestSupport.openLibrary();
                ReferenceFingerprintLibrary second = MatchingTestSupport.openLibrary()) {
            for (FingerprintId id : FingerprintId.values()) {
                assertEquals(0, countDifferentPixels(first.target(id), second.target(id)),
                        id + " target must normalize deterministically");
                for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
                    assertEquals(0, countDifferentPixels(first.fragment(id, fragmentId),
                            second.fragment(id, fragmentId)),
                            id + " fragment " + fragmentId + " must normalize deterministically");
                }
            }
        }
    }

    @Test
    void missingManifestIsReported(@TempDir Path emptyRoot) {
        assertThrows(IOException.class, () -> ReferenceFingerprintLibrary.load(emptyRoot));
    }

    private static int countDifferentPixels(Mat left, Mat right) {
        assertEquals(left.rows(), right.rows(), "Row count");
        assertEquals(left.cols(), right.cols(), "Column count");
        assertEquals(left.type(), right.type(), "Mat type");
        try (Mat difference = new Mat()) {
            opencv_core.absdiff(left, right, difference);
            return opencv_core.countNonZero(difference);
        }
    }
}
