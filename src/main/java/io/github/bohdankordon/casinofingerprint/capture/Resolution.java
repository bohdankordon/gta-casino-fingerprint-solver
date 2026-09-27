package io.github.bohdankordon.casinofingerprint.capture;

/**
 * An immutable pixel size.
 *
 * <p>Physical resolutions and logical desktop sizes are both expressed with this record, so
 * callers must be explicit about which one they mean: the gameplay layout is bound to PHYSICAL
 * pixels, while an AWT configuration bounds is a LOGICAL (user-space) size on a scaled display.
 */
public record Resolution(int width, int height) {
    public Resolution {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Resolution must be positive, got " + width + "x" + height);
        }
    }

    /** Human-readable {@code width x height}. */
    public String describe() {
        return width + "x" + height;
    }

    /** Whether this resolution is exactly {@code otherWidth x otherHeight}. */
    public boolean matches(int otherWidth, int otherHeight) {
        return width == otherWidth && height == otherHeight;
    }

    @Override
    public String toString() {
        return describe();
    }
}
