package io.github.bohdankordon.casinofingerprint.capture;

import java.util.List;
import java.util.Objects;

/**
 * The capture backend returned no resolution variant matching the required physical resolution.
 *
 * <p>This is the DPI failure mode Stage 5 refuses to paper over: a scaled desktop can offer only
 * a logical-resolution image (for example 2048x1152 for a 2560x1440 panel at 125% scaling).
 * Upscaling that image into the physical layout would silently move every Stage 2 ROI, so the
 * capture fails with a diagnostic listing the monitor, its logical bounds, its physical display
 * mode and every variant that was actually returned.
 */
public final class UnsupportedResolutionException extends CaptureException {
    private final transient MonitorInfo monitor;
    private final transient Resolution required;
    private final transient List<Resolution> returnedVariants;

    public UnsupportedResolutionException(
            MonitorInfo monitor, Resolution required, List<Resolution> returnedVariants) {
        super(buildMessage(monitor, required, returnedVariants));
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.required = Objects.requireNonNull(required, "required");
        this.returnedVariants =
                List.copyOf(Objects.requireNonNull(returnedVariants, "returnedVariants"));
    }

    /** Monitor whose capture could not deliver {@link #required()}. */
    public MonitorInfo monitor() {
        return monitor;
    }

    /** Physical resolution the gameplay layout needs. */
    public Resolution required() {
        return required;
    }

    /** Resolutions the backend did return, in capture order. */
    public List<Resolution> returnedVariants() {
        return returnedVariants;
    }

    private static String buildMessage(
            MonitorInfo monitor, Resolution required, List<Resolution> returnedVariants) {
        List<Resolution> variants = List.copyOf(returnedVariants);
        String separator = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("No capture variant matches the required physical resolution ")
                .append(required).append('.').append(separator);
        text.append("  monitor         : ").append(monitor.index())
                .append(" (").append(monitor.deviceId()).append(')').append(separator);
        text.append("  logical bounds  : ").append(monitor.logicalBounds().describe())
                .append(separator);
        text.append("  display mode    : ").append(monitor.displayMode().describe())
                .append(separator);
        text.append("  capture variants: ")
                .append(variants.isEmpty() ? "(none returned)" : variants.toString())
                .append(separator);
        text.append("  This is the Windows display-scaling case: a logical-resolution image is never");
        text.append(" resized into the physical gameplay layout, because that would shift every ROI.");
        return text.toString();
    }
}
