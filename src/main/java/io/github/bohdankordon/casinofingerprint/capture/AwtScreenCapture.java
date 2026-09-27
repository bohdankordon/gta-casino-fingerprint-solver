package io.github.bohdankordon.casinofingerprint.capture;

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * First desktop capture backend: java.awt.Robot plus the HiDPI-aware
 * Robot.createMultiResolutionScreenCapture facility.
 *
 * <p>Capture procedure for one frame:
 *
 * <ol>
 *   <li>take the selected device's LOGICAL bounds from its default configuration and its
 *       PHYSICAL display mode from the device itself;</li>
 *   <li>request a multi-resolution capture of the logical rectangle;</li>
 *   <li>inspect every returned resolution variant;</li>
 *   <li>convert only the variant whose pixels are EXACTLY the required physical resolution.</li>
 * </ol>
 *
 * <p>A logical image is never resized into the physical layout: when no variant matches, the
 * capture fails with UnsupportedResolutionException instead of returning a distorted frame.
 * ScreenCapture stays the abstraction boundary, so a future Windows Desktop Duplication or
 * Windows Graphics Capture backend can replace this class without touching the recognition core.
 *
 * <p>Known limitation: this backend is the first AWT/Robot implementation and is not validated
 * against the game itself. Exclusive-fullscreen modes can produce black or stale frames on some
 * driver combinations, so borderless or windowed desktop-composited modes are the expected first
 * environment. No such backend is implemented here on speculation.
 *
 * <p>Ownership: every returned Mat is newly allocated and owned by the caller, who must close it.
 * No BufferedImage of a captured frame is retained after a capture returns.
 */
public final class AwtScreenCapture implements ScreenCapture {
    private final MonitorInfo monitor;
    private final GraphicsDevice device;
    private final Resolution required;

    AwtScreenCapture(MonitorInfo monitor, GraphicsDevice device, Resolution required) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.device = Objects.requireNonNull(device, "device");
        this.required = Objects.requireNonNull(required, "required");
    }

    /**
     * Binds a capture to {@code monitor} by re-resolving its AWT device.
     *
     * @param monitor monitor selected by {@link MonitorSelector}
     * @param required physical resolution the capture must deliver exactly
     * @throws CaptureException when the JVM has no interactive desktop or the monitor is gone
     */
    public static AwtScreenCapture forMonitor(MonitorInfo monitor, Resolution required) {
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(required, "required");
        if (GraphicsEnvironment.isHeadless()) {
            throw new CaptureException(
                    "No interactive desktop is available (headless JVM); screen capture cannot run.");
        }
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice idMatch = null;
        for (GraphicsDevice candidate : environment.getScreenDevices()) {
            if (!candidate.getIDstring().equals(monitor.deviceId())) {
                continue;
            }
            if (idMatch == null) {
                idMatch = candidate;
            }
            if (matchesBounds(candidate, monitor.logicalBounds())) {
                return new AwtScreenCapture(monitor, candidate, required);
            }
        }
        if (idMatch != null) {
            return new AwtScreenCapture(monitor, idMatch, required);
        }
        throw new CaptureException(
                "Monitor " + monitor.describe() + " is no longer attached to this desktop.");
    }

    /** Logical rectangle a capture is requested for; the physical size is decided by the variants. */
    public ScreenBounds logicalBounds() {
        Rectangle bounds = device.getDefaultConfiguration().getBounds();
        return new ScreenBounds(
                bounds.x, bounds.y, Math.max(bounds.width, 1), Math.max(bounds.height, 1));
    }

    /**
     * Captures one frame as a caller-owned CV_8UC3 BGR Mat.
     *
     * @throws CaptureException when the backend fails or no variant matches the required
     *         physical resolution
     */
    @Override
    public Mat capture() {
        return captureDetailed().frame();
    }

    /**
     * Captures one frame and keeps the HiDPI diagnostics: the logical bounds that were requested,
     * every variant that came back and the variant that was selected.
     *
     * @return capture result whose frame is owned by the caller
     */
    public AwtCaptureResult captureDetailed() {
        ScreenBounds bounds = logicalBounds();
        Rectangle rectangle = new Rectangle(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        Robot robot = robot();
        List<ResolutionVariant> variants = new ArrayList<>(2);
        boolean multiResolution = false;
        try {
            MultiResolutionImage captured = robot.createMultiResolutionScreenCapture(rectangle);
            multiResolution = true;
            variants.addAll(toVariants(captured));
        } catch (UnsupportedOperationException e) {
            // A peer without HiDPI support can still be correct at 100% scaling: a plain capture
            // is accepted when it already carries the physical resolution, and rejected by exact
            // variant selection otherwise. Unscaled pixels are never invented here.
            variants.add(new ResolutionVariant(robot.createScreenCapture(rectangle)));
        }
        ResolutionVariant selected = ResolutionVariantSelector.selectExact(variants, monitor, required);
        Mat frame = BufferedImageMatConverter.toBgrMat(selected.image());
        if (frame.cols() != required.width() || frame.rows() != required.height()) {
            frame.close();
            throw new CaptureException("Capture selection produced "
                    + frame.cols() + "x" + frame.rows() + " instead of " + required);
        }
        List<Resolution> resolutions = new ArrayList<>(variants.size());
        for (ResolutionVariant variant : variants) {
            resolutions.add(variant.resolution());
        }
        return new AwtCaptureResult(
                frame, monitor, bounds, resolutions, selected.resolution(), multiResolution);
    }

    private Robot robot() {
        try {
            return new Robot(device);
        } catch (AWTException e) {
            throw new CaptureException("Could not create an AWT Robot for "
                    + monitor.describe() + ": " + e.getMessage(), e);
        }
    }

    private static List<ResolutionVariant> toVariants(MultiResolutionImage captured) {
        List<ResolutionVariant> variants = new ArrayList<>(2);
        List<Image> resolutionVariants = captured.getResolutionVariants();
        if (resolutionVariants != null) {
            for (Image variant : resolutionVariants) {
                BufferedImage image = toBufferedImage(variant);
                if (image != null) {
                    variants.add(new ResolutionVariant(image));
                }
            }
        }
        if (variants.isEmpty()) {
            BufferedImage image = captured instanceof Image base ? toBufferedImage(base) : null;
            if (image != null) {
                variants.add(new ResolutionVariant(image));
            }
        }
        return List.copyOf(variants);
    }

    private static BufferedImage toBufferedImage(Image image) {
        if (image instanceof BufferedImage buffered) {
            return buffered.getWidth() > 0 && buffered.getHeight() > 0 ? buffered : null;
        }
        int width = image.getWidth(null);
        int height = image.getHeight(null);
        if (width <= 0 || height <= 0) {
            return null;
        }
        BufferedImage converted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = converted.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return converted;
    }

    private static boolean matchesBounds(GraphicsDevice device, ScreenBounds bounds) {
        Rectangle rectangle = device.getDefaultConfiguration().getBounds();
        return rectangle.x == bounds.x()
                && rectangle.y == bounds.y()
                && rectangle.width == bounds.width()
                && rectangle.height == bounds.height();
    }
}
