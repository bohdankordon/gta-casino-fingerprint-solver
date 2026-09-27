package io.github.bohdankordon.casinofingerprint.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Monitor selection rules: one unambiguous match is selected automatically, zero matches and
 * several matches both fail with a diagnostic, and an explicit index is honoured exactly.
 */
class MonitorSelectorTest {
    private static final Resolution REQUIRED = new Resolution(2560, 1440);

    /** The user's target display: 2560x1440 physical, 2048x1152 logical at 125% scaling. */
    private static final MonitorInfo SCALED_TARGET = monitor(0, true, 2048, 1152, 2560, 1440);
    /** A 1920x1080 secondary display. */
    private static final MonitorInfo SECONDARY = monitor(1, false, 1920, 1080, 1920, 1080);
    /** A second 2560x1440 display, so automatic selection is ambiguous. */
    private static final MonitorInfo SECOND_TARGET = monitor(1, false, 2048, 1152, 2560, 1440);

    @Test
    void singleMatchingMonitorIsSelectedAutomatically() {
        MonitorInfo selected =
                MonitorSelector.resolve(List.of(SCALED_TARGET, SECONDARY), null, REQUIRED);

        assertEquals(SCALED_TARGET, selected, "The only matching monitor is selected");
    }

    @Test
    void matchingUsesThePhysicalModeNotTheLogicalBounds() {
        MonitorInfo logicalMatchOnly = monitor(0, true, 2560, 1440, 1920, 1080);

        CaptureException failure = assertThrows(CaptureException.class,
                () -> MonitorSelector.resolve(List.of(logicalMatchOnly), null, REQUIRED));

        assertTrue(failure.getMessage().contains("physical 2560x1440"),
                "Diagnostic explains the physical requirement: " + failure.getMessage());
    }

    @Test
    void automaticSelectionIsDeterministic() {
        List<MonitorInfo> monitors = List.of(SECONDARY, SCALED_TARGET);

        MonitorInfo first = MonitorSelector.resolve(monitors, null, REQUIRED);
        MonitorInfo second = MonitorSelector.resolve(monitors, null, REQUIRED);
        MonitorInfo third = MonitorSelector.resolve(monitors, null, REQUIRED);

        assertEquals(SCALED_TARGET, first, "Selected monitor");
        assertEquals(first, second, "Repeated selection");
        assertEquals(second, third, "Repeated selection");
        assertEquals(List.of(SCALED_TARGET), MonitorSelector.matchingPhysicalResolution(monitors, REQUIRED),
                "Matching list stays in index order");
    }

    @Test
    void zeroMatchingMonitorsIsReportedClearly() {
        CaptureException failure = assertThrows(CaptureException.class,
                () -> MonitorSelector.resolve(List.of(SECONDARY), null, REQUIRED));

        String message = failure.getMessage();
        assertTrue(message.contains("No monitor reports a physical 2560x1440"),
                "Diagnostic states the missing mode: " + message);
        assertTrue(message.contains("DISPLAY-2"), "Diagnostic lists the attached monitor: " + message);
        assertTrue(message.contains("1920x1080"), "Diagnostic shows the available mode: " + message);
    }

    @Test
    void severalMatchingMonitorsRequireAnExplicitSelection() {
        CaptureException failure = assertThrows(CaptureException.class,
                () -> MonitorSelector.resolve(List.of(SCALED_TARGET, SECOND_TARGET), null, REQUIRED));

        String message = failure.getMessage();
        assertTrue(message.contains("2 monitors report a physical 2560x1440"),
                "Diagnostic counts the matches: " + message);
        assertTrue(message.contains("--monitor <index>"), "Diagnostic asks for an explicit choice: " + message);
        assertTrue(message.contains("DISPLAY-1") && message.contains("DISPLAY-2"),
                "Diagnostic lists both matches: " + message);
    }

    @Test
    void explicitSelectionPicksExactlyThatIndex() {
        MonitorInfo selected =
                MonitorSelector.resolve(List.of(SCALED_TARGET, SECOND_TARGET), 1, REQUIRED);

        assertEquals(SECOND_TARGET, selected, "The explicitly requested monitor is used");
        assertEquals(1, selected.index(), "Index");
    }

    @Test
    void explicitSelectionOfAnUnsupportedMonitorIsRejected() {
        CaptureException failure = assertThrows(CaptureException.class,
                () -> MonitorSelector.resolve(List.of(SCALED_TARGET, SECONDARY), 1, REQUIRED));

        String message = failure.getMessage();
        assertTrue(message.contains("Monitor 1 cannot deliver"), "Diagnostic names the monitor: " + message);
        assertTrue(message.contains("2560x1440"), "Diagnostic names the requirement: " + message);
        assertTrue(message.contains("never resized"), "Diagnostic states the refusal: " + message);
    }

    @Test
    void unknownIndexListsTheAvailableIndexes() {
        CaptureException failure = assertThrows(CaptureException.class,
                () -> MonitorSelector.resolve(List.of(SCALED_TARGET, SECONDARY), 7, REQUIRED));

        String message = failure.getMessage();
        assertTrue(message.contains("Monitor 7 does not exist"), "Diagnostic: " + message);
        assertTrue(message.contains("[0, 1]"), "Diagnostic lists the available indexes: " + message);
    }

    @Test
    void emptyDesktopIsReported() {
        CaptureException failure = assertThrows(CaptureException.class,
                () -> MonitorSelector.resolve(List.of(), null, REQUIRED));

        assertTrue(failure.getMessage().contains("No monitors were detected"),
                "Diagnostic: " + failure.getMessage());
    }

    private static MonitorInfo monitor(int index, boolean primary, int logicalWidth, int logicalHeight,
            int physicalWidth, int physicalHeight) {
        return new MonitorInfo(
                index,
                "DISPLAY-" + (index + 1),
                primary,
                new ScreenBounds(0, 0, logicalWidth, logicalHeight),
                new PhysicalDisplayMode(physicalWidth, physicalHeight, 59.94));
    }
}
