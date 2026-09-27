package io.github.bohdankordon.casinofingerprint.gameplay;

import java.util.Objects;

/**
 * One immutable region definition from a gameplay layout manifest.
 *
 * <p>Coordinates use source-image pixels with top-left origin: {@code x} increases
 * right, {@code y} increases downward. Each rectangle is {@code (x, y, width, height)}.
 * Only {@link GameplayRegionType#CANDIDATE} regions carry a candidate index, using
 * row-major ordering over the 2x4 candidate grid:
 *
 * <pre>
 * 0 1
 * 2 3
 * 4 5
 * 6 7
 * </pre>
 */
public record GameplayRegion(
        GameplayRegionType regionType,
        Integer candidateIndex,
        int x,
        int y,
        int width,
        int height) {
    public GameplayRegion {
        Objects.requireNonNull(regionType, "regionType");
        if (regionType == GameplayRegionType.CANDIDATE) {
            if (candidateIndex == null || candidateIndex < 0 || candidateIndex > 7) {
                throw new IllegalArgumentException("candidate regions need candidate_index 0..7");
            }
        } else if (candidateIndex != null) {
            throw new IllegalArgumentException("only candidate regions may have a candidate_index");
        }
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("x/y must be non-negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height must be positive");
        }
    }
}
