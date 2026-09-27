package io.github.bohdankordon.casinofingerprint.capture;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * One resolution variant of a HiDPI-aware screen capture: the pixel grid the backend actually
 * produced.
 *
 * <p>A scaled desktop typically returns several variants for the same capture request, for
 * example a 2048x1152 logical variant and a 2560x1440 physical one. The resolution is read from
 * the image itself, so no variant can claim a size it does not have.
 */
public record ResolutionVariant(BufferedImage image) {
    public ResolutionVariant {
        Objects.requireNonNull(image, "image");
        if (image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new IllegalArgumentException("Resolution variant image is empty");
        }
    }

    /** Pixel size of this variant. */
    public Resolution resolution() {
        return new Resolution(image.getWidth(), image.getHeight());
    }

    @Override
    public String toString() {
        return resolution().describe();
    }
}
