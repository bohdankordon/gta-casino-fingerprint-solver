package io.github.bohdankordon.casinofingerprint.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic monitor selection for the recognition-only runtime.
 *
 * <p>Two rules keep selection honest:
 *
 * <ul>
 *   <li>an explicitly requested index is honoured exactly, and is rejected when that monitor's
 *       PHYSICAL display mode is not the required resolution;</li>
 *   <li>automatic selection happens only when exactly one monitor matches. Zero matches and
 *       several matches both fail with a diagnostic, so a secondary display is never picked
 *       silently and a matching display is never guessed.</li>
 * </ul>
 *
 * <p>The gameplay layout is fixed to physical pixels, so matching is always done on the physical
 * display mode: a monitor whose LOGICAL bounds happen to be 2560x1440 while its panel runs a
 * different physical mode is not a match.
 */
public final class MonitorSelector {
    private MonitorSelector() {
    }

    /**
     * Resolves the monitor to capture.
     *
     * @param monitors attached monitors in runtime index order
     * @param requestedIndex explicit {@code --monitor <index>} selection, or {@code null} to
     *        select automatically when exactly one monitor matches
     * @param required physical resolution the gameplay layout needs
     * @throws CaptureException when the desktop has no monitors, the requested index does not
     *         exist, the requested monitor cannot deliver {@code required}, no monitor matches,
     *         or several monitors match without an explicit selection
     */
    public static MonitorInfo resolve(List<MonitorInfo> monitors, Integer requestedIndex,
            Resolution required) {
        Objects.requireNonNull(monitors, "monitors");
        Objects.requireNonNull(required, "required");
        List<MonitorInfo> detected = List.copyOf(monitors);
        if (detected.isEmpty()) {
            throw new CaptureException("No monitors were detected on this desktop.");
        }
        if (requestedIndex != null) {
            return explicit(detected, requestedIndex, required);
        }
        return automatic(detected, required);
    }

    /** Monitors whose physical display mode is exactly {@code required}, in index order. */
    public static List<MonitorInfo> matchingPhysicalResolution(
            List<MonitorInfo> monitors, Resolution required) {
        Objects.requireNonNull(monitors, "monitors");
        Objects.requireNonNull(required, "required");
        List<MonitorInfo> matching = new ArrayList<>();
        for (MonitorInfo monitor : monitors) {
            if (monitor.supportsPhysicalResolution(required.width(), required.height())) {
                matching.add(monitor);
            }
        }
        return List.copyOf(matching);
    }

    private static MonitorInfo explicit(
            List<MonitorInfo> monitors, int requestedIndex, Resolution required) {
        MonitorInfo requested = monitors.stream()
                .filter(monitor -> monitor.index() == requestedIndex)
                .findFirst()
                .orElseThrow(() -> new CaptureException(
                        "Monitor " + requestedIndex + " does not exist; available indexes: "
                                + indexes(monitors) + "." + System.lineSeparator()
                                + describeAll(monitors)));
        if (!requested.supportsPhysicalResolution(required.width(), required.height())) {
            throw new CaptureException("Monitor " + requestedIndex
                    + " cannot deliver the required physical resolution " + required + "."
                    + System.lineSeparator()
                    + "  " + requested.describe() + System.lineSeparator()
                    + "  The gameplay layout is only valid for physical " + required
                    + "; a logical image is never resized to fit it.");
        }
        return requested;
    }

    private static MonitorInfo automatic(List<MonitorInfo> monitors, Resolution required) {
        List<MonitorInfo> matching = matchingPhysicalResolution(monitors, required);
        if (matching.isEmpty()) {
            throw new CaptureException("No monitor reports a physical " + required
                    + " display mode." + System.lineSeparator()
                    + describeAll(monitors));
        }
        if (matching.size() > 1) {
            throw new CaptureException(matching.size() + " monitors report a physical " + required
                    + " display mode; select one explicitly with --monitor <index>."
                    + System.lineSeparator()
                    + describeMatching(matching));
        }
        return matching.get(0);
    }

    private static String indexes(List<MonitorInfo> monitors) {
        return monitors.stream()
                .map(monitor -> Integer.toString(monitor.index()))
                .toList()
                .toString();
    }

    private static String describeAll(List<MonitorInfo> monitors) {
        StringBuilder text = new StringBuilder("  attached monitors:");
        for (MonitorInfo monitor : monitors) {
            text.append(System.lineSeparator()).append("  ").append(monitor.describe());
        }
        return text.toString();
    }

    private static String describeMatching(List<MonitorInfo> matching) {
        StringBuilder text = new StringBuilder("  matching monitors:");
        for (MonitorInfo monitor : matching) {
            text.append(System.lineSeparator()).append("  ").append(monitor.describe());
        }
        return text.toString();
    }
}
