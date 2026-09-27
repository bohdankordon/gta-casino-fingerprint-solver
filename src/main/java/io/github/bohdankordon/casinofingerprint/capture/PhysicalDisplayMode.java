package io.github.bohdankordon.casinofingerprint.capture;

import java.util.Locale;

/**
 * Physical display mode of one monitor as reported by the operating system: the real device
 * pixel grid plus the refresh rate when the platform exposes one.
 *
 * <p>The gameplay layout is measured in these physical pixels, so this is the resolution a
 * capture must reproduce exactly. A non-positive width or height means the platform did not
 * report a usable mode ({@link #known()} is then {@code false}); such a monitor cannot be
 * matched against a required resolution.
 */
public record PhysicalDisplayMode(int width, int height, double refreshRate) {
    /** Whether the platform reported a usable physical resolution. */
    public boolean known() {
        return width > 0 && height > 0;
    }

    /** Human-readable {@code 2560x1440 @ 59.9 Hz} or {@code unknown}. */
    public String describe() {
        if (!known()) {
            return "unknown";
        }
        String mode = width + "x" + height;
        if (refreshRate > 0) {
            mode = String.format(Locale.ROOT, "%s @ %.1f Hz", mode, refreshRate);
        }
        return mode;
    }

    @Override
    public String toString() {
        return describe();
    }
}
