package io.github.bohdankordon.casinofingerprint.capture;

/**
 * Logical (user-space) bounds of one screen in the AWT virtual desktop coordinate space.
 *
 * <p>On a display with Windows scaling these bounds are SMALLER than the physical display mode:
 * a 2560x1440 panel at 125% scaling can report a 2048x1152 logical rectangle. The rectangle is
 * what a HiDPI-aware capture is asked for; the returned resolution variants decide whether the
 * physical pixels are actually available.
 */
public record ScreenBounds(int x, int y, int width, int height) {
    public ScreenBounds {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Screen bounds must have a positive size, got " + width + "x" + height);
        }
    }

    /** Human-readable {@code x,y width x height}. */
    public String describe() {
        return x + "," + y + " " + width + "x" + height;
    }

    @Override
    public String toString() {
        return describe();
    }
}
