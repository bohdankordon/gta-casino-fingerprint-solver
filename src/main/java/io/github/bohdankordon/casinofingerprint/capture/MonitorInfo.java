package io.github.bohdankordon.casinofingerprint.capture;

import java.util.Objects;

/**
 * One attached monitor as the live runtime sees it: a stable runtime index, the backend device
 * id, whether it is the primary screen, its logical bounds and its physical display mode.
 *
 * <p>Ownership: this is an immutable value snapshot. It carries no AWT handle, so selection
 * rules can be tested without a display; the AWT backend resolves the real
 * {@code GraphicsDevice} from the recorded id when a capture is requested.
 */
public record MonitorInfo(
        int index,
        String deviceId,
        boolean primary,
        ScreenBounds logicalBounds,
        PhysicalDisplayMode displayMode) {

    public MonitorInfo {
        if (index < 0) {
            throw new IllegalArgumentException("Monitor index must be non-negative, got " + index);
        }
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(logicalBounds, "logicalBounds");
        Objects.requireNonNull(displayMode, "displayMode");
    }

    /**
     * Whether this monitor's PHYSICAL display mode is exactly {@code width x height}. A logical
     * bounds of the same size never counts: a scaled display can report matching logical pixels
     * while its capture still lacks the physical grid the gameplay layout needs.
     */
    public boolean supportsPhysicalResolution(int width, int height) {
        return displayMode.known() && displayMode.width() == width && displayMode.height() == height;
    }

    /** One-line description: {@code [0] \\.\DISPLAY1 primary logical 0,0 2560x1440 physical 2560x1440 @ 59.9 Hz}. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append('[').append(index).append("] ").append(deviceId);
        if (primary) {
            text.append(" primary");
        }
        text.append(" logical ").append(logicalBounds.describe());
        text.append(" physical ").append(displayMode.describe());
        return text.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
