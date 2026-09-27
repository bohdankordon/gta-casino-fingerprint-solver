package io.github.bohdankordon.casinofingerprint.matching;

import io.github.bohdankordon.casinofingerprint.dataset.ReferenceAssetType;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceCrop;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceLayout;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Normalized Stage 1 reference material: one 256x384 target profile and four 128x128 fragment
 * profiles per {@link FingerprintId}.
 *
 * <p>The library reads the existing Stage 1 manifest ({@code dataset/layout/reference-layout.csv})
 * and never duplicates crop coordinates: asset locations come from the manifest records. Every
 * asset is decoded and pushed through the shared {@link StructuralNormalizer}, so reference and
 * gameplay profiles are directly comparable.
 *
 * <p>Ownership: this library owns every Mat it loads and releases them on {@link #close()}. Mats
 * returned by {@link #target(FingerprintId)} and {@link #fragment(FingerprintId, int)} are
 * <em>borrowed</em>: callers must not close or modify them, and they stay valid only until the
 * library is closed.
 */
public final class ReferenceFingerprintLibrary implements AutoCloseable {
    /** Repo-relative Stage 1 manifest path (forward slashes), matching the dataset contract. */
    public static final String MANIFEST_REL = "dataset/layout/reference-layout.csv";

    private final EnumMap<FingerprintId, Mat> targets;
    private final EnumMap<FingerprintId, List<Mat>> fragments;
    private boolean closed;

    private ReferenceFingerprintLibrary(EnumMap<FingerprintId, Mat> targets,
            EnumMap<FingerprintId, List<Mat>> fragments) {
        this.targets = targets;
        this.fragments = fragments;
    }

    /**
     * Loads and normalizes every Stage 1 reference asset below {@code projectRoot}.
     *
     * <p>Ownership invariant: every normalized Mat created here is either transferred into the
     * returned library or closed exactly once before the failure propagates. Profiles normalized
     * for the fingerprint currently being read stay in a local pending list until they are
     * transferred, and that list is closed by the local handler, so a failure part way through a
     * fingerprint cannot strand profiles that were already normalized. Maps only ever hold Mats
     * that have left the pending list, so no Mat is closed twice.
     *
     * @param projectRoot repository root holding the Stage 1 manifest and canonical assets
     * @return an open library that must be closed by the caller
     */
    public static ReferenceFingerprintLibrary load(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        List<ReferenceCrop> crops = ReferenceLayout.read(projectRoot.resolve(MANIFEST_REL));
        StructuralNormalizer normalizer = new StructuralNormalizer();
        EnumMap<FingerprintId, Mat> targets = new EnumMap<>(FingerprintId.class);
        EnumMap<FingerprintId, List<Mat>> fragments = new EnumMap<>(FingerprintId.class);
        try {
            for (FingerprintId id : FingerprintId.values()) {
                List<Mat> pending = new ArrayList<>(5);
                try {
                    try (Mat raw = read(projectRoot, targetCrop(crops, id))) {
                        pending.add(normalizer.normalizeTarget(raw));
                    }
                    for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
                        try (Mat raw = read(projectRoot, fragmentCrop(crops, id, fragmentId))) {
                            pending.add(normalizer.normalizeFragment(raw));
                        }
                    }
                    // Transfer ownership one Mat at a time: a profile leaves the pending list only
                    // after the map insert that makes the library responsible for it succeeded.
                    Mat targetProfile = pending.get(0);
                    targets.put(id, targetProfile);
                    pending.remove(0);
                    List<Mat> fragmentProfiles = List.copyOf(pending);
                    fragments.put(id, fragmentProfiles);
                    pending.clear();
                } catch (Throwable t) {
                    for (Mat profile : pending) {
                        profile.close();
                    }
                    throw t;
                }
            }
        } catch (Throwable t) {
            closeAll(targets, fragments);
            throw t;
        }
        return new ReferenceFingerprintLibrary(targets, fragments);
    }

    private static ReferenceCrop targetCrop(List<ReferenceCrop> crops, FingerprintId id) {
        return crops.stream()
                .filter(c -> c.fingerprintId() == id && c.assetType() == ReferenceAssetType.TARGET)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Manifest has no target for " + id));
    }

    private static ReferenceCrop fragmentCrop(List<ReferenceCrop> crops, FingerprintId id, int fragmentId) {
        return crops.stream()
                .filter(c -> c.fingerprintId() == id
                        && c.assetType() == ReferenceAssetType.FRAGMENT
                        && c.fragmentId() == fragmentId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Manifest has no fragment " + fragmentId + " for " + id));
    }

    /** Borrowed 256x384 normalized target profile for {@code id}; do not close or modify. */
    public Mat target(FingerprintId id) {
        checkOpen();
        Mat target = targets.get(id);
        if (target == null) {
            throw new IllegalArgumentException("No target loaded for " + id);
        }
        return target;
    }

    /**
     * Borrowed 128x128 normalized reference fragment profile; do not close or modify.
     *
     * @param fragmentId canonical reference fragment id, {@code 1..4}
     */
    public Mat fragment(FingerprintId id, int fragmentId) {
        checkOpen();
        if (fragmentId < 1 || fragmentId > 4) {
            throw new IllegalArgumentException("Reference fragment ids are 1..4, got " + fragmentId);
        }
        List<Mat> profiles = fragments.get(id);
        if (profiles == null) {
            throw new IllegalArgumentException("No fragments loaded for " + id);
        }
        return profiles.get(fragmentId - 1);
    }

    /** Releases every normalized reference Mat this library owns. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeAll(targets, fragments);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Reference library is closed");
        }
    }

    private static Mat read(Path projectRoot, ReferenceCrop crop) {
        Path path = projectRoot.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
        Mat decoded = opencv_imgcodecs.imread(path.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
        if (decoded == null || decoded.empty()) {
            throw new IllegalStateException("Could not decode Stage 1 asset: " + path);
        }
        return decoded;
    }

    private static void closeAll(EnumMap<FingerprintId, Mat> targets,
            EnumMap<FingerprintId, List<Mat>> fragments) {
        for (Mat target : targets.values()) {
            target.close();
        }
        for (List<Mat> profiles : fragments.values()) {
            for (Mat fragment : profiles) {
                fragment.close();
            }
        }
    }
}
