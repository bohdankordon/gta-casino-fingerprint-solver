package io.github.bohdankordon.casinofingerprint.capture;

import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * One AWT capture together with the HiDPI diagnostics behind it.
 *
 * <p>Ownership: the caller owns {@link #frame()} and must close it. The images of the discarded
 * variants are released by the garbage collector, and no AWT image is retained here beyond the
 * capture call, so a live loop keeps no accumulating screen images.
 *
 * @param frame captured frame converted to {@code CV_8UC3} BGR; caller-owned
 * @param monitor monitor the capture was taken from
 * @param logicalBounds logical rectangle the capture was requested for
 * @param variants every resolution variant the backend returned, in capture order
 * @param selectedVariant variant that was converted into {@link #frame()}
 * @param multiResolutionCapture whether the HiDPI-aware multi-resolution path was used; when
 *        {@code false} the backend fell back to a single-image capture and the only variant came
 *        from that image
 */
public record AwtCaptureResult(
        Mat frame,
        MonitorInfo monitor,
        ScreenBounds logicalBounds,
        List<Resolution> variants,
        Resolution selectedVariant,
        boolean multiResolutionCapture) {

    public AwtCaptureResult {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(logicalBounds, "logicalBounds");
        Objects.requireNonNull(variants, "variants");
        Objects.requireNonNull(selectedVariant, "selectedVariant");
        variants = List.copyOf(variants);
    }
}
