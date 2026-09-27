package io.github.bohdankordon.casinofingerprint.dataset;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.Objects;

/**
 * One immutable crop definition from the reference layout manifest.
 *
 * <p>Coordinates use source-image pixels with top-left origin: {@code x} increases
 * right, {@code y} increases downward. Each rectangle is {@code (x, y, width, height)}.
 */
public record ReferenceCrop(
        FingerprintId fingerprintId,
        ReferenceAssetType assetType,
        Integer fragmentId,
        int x,
        int y,
        int width,
        int height,
        String outputPath) {
    public ReferenceCrop {
        Objects.requireNonNull(fingerprintId, "fingerprintId");
        Objects.requireNonNull(assetType, "assetType");
        Objects.requireNonNull(outputPath, "outputPath");
        if (outputPath.isBlank()) {
            throw new IllegalArgumentException("outputPath must not be blank");
        }
        if (assetType == ReferenceAssetType.TARGET) {
            if (fragmentId != null) {
                throw new IllegalArgumentException("target crops must not have a fragment_id");
            }
        } else {
            if (fragmentId == null || fragmentId < 1 || fragmentId > 4) {
                throw new IllegalArgumentException("fragment crops need fragment_id 1..4");
            }
        }
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("x/y must be non-negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height must be positive");
        }
    }
}
