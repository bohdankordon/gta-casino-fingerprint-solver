package io.github.bohdankordon.casinofingerprint.capture;

import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * AWT monitor discovery: one {@link MonitorInfo} per {@code GraphicsDevice}, recorded in the
 * order {@code GraphicsEnvironment.getScreenDevices()} returns them.
 *
 * <p>The logical bounds come from the device configuration and the physical resolution from
 * {@code GraphicsDevice.getDisplayMode()}. On a Windows display with scaling enabled these two
 * differ, and both are recorded: the layout needs the physical one, while the logical rectangle
 * is what a HiDPI-aware capture is asked for.
 *
 * <p>This class only reads desktop metadata. It never captures anything and never creates a
 * {@code Robot}; {@link AwtScreenCapture} owns the capture side.
 */
public final class AwtMonitorEnumerator implements MonitorEnumerator {
    @Override
    public List<MonitorInfo> enumerate() {
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = environment.getScreenDevices();
        GraphicsDevice primary = environment.getDefaultScreenDevice();
        List<MonitorInfo> monitors = new ArrayList<>(devices.length);
        for (int index = 0; index < devices.length; index++) {
            monitors.add(describe(index, devices[index], devices[index] == primary));
        }
        return List.copyOf(monitors);
    }

    /**
     * Enumerator for this desktop.
     *
     * @throws CaptureException when the JVM has no interactive desktop (headless environment)
     */
    public static AwtMonitorEnumerator create() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new CaptureException(
                    "No interactive desktop is available (headless JVM); monitors cannot be listed.");
        }
        return new AwtMonitorEnumerator();
    }

    private static MonitorInfo describe(int index, GraphicsDevice device, boolean primary) {
        Rectangle bounds = device.getDefaultConfiguration().getBounds();
        return new MonitorInfo(
                index,
                device.getIDstring(),
                primary,
                new ScreenBounds(
                        bounds.x,
                        bounds.y,
                        Math.max(bounds.width, 1),
                        Math.max(bounds.height, 1)),
                physicalMode(device.getDisplayMode()));
    }

    private static PhysicalDisplayMode physicalMode(DisplayMode mode) {
        if (mode == null) {
            return new PhysicalDisplayMode(0, 0, 0);
        }
        return new PhysicalDisplayMode(mode.getWidth(), mode.getHeight(), mode.getRefreshRate());
    }
}
