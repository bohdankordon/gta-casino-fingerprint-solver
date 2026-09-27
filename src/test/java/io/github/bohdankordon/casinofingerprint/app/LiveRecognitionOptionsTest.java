package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Command-line contract of the recognition-only runtime. */
class LiveRecognitionOptionsTest {
    @Test
    void listMonitorsModeIsParsed() {
        LiveRecognitionOptions options = LiveRecognitionOptions.parse(new String[] {"--list-monitors"});

        assertEquals(LiveRecognitionOptions.Mode.LIST_MONITORS, options.mode(), "Mode");
        assertNull(options.monitorIndex(), "No monitor requested");
        assertFalse(options.help(), "Not help");
    }

    @Test
    void onceModeAcceptsAnExplicitMonitor() {
        LiveRecognitionOptions options =
                LiveRecognitionOptions.parse(new String[] {"--monitor", "1", "--once"});

        assertEquals(LiveRecognitionOptions.Mode.ONCE, options.mode(), "Mode");
        assertEquals(1, options.monitorIndex(), "Monitor index");
    }

    @Test
    void watchModeUsesConservativeDefaults() {
        LiveRecognitionOptions options = LiveRecognitionOptions.parse(new String[] {"--watch"});

        assertEquals(LiveRecognitionOptions.Mode.WATCH, options.mode(), "Mode");
        assertEquals(200, options.intervalMillis(), "Default interval");
        assertEquals(3, options.stableFrames(), "Default stable frames");
        assertEquals(200, LiveRecognitionOptions.DEFAULT_INTERVAL_MILLIS, "Documented default");
        assertEquals(3, LiveRecognitionOptions.DEFAULT_STABLE_FRAMES, "Documented default");
    }

    @Test
    void watchModeAcceptsOverrides() {
        LiveRecognitionOptions options = LiveRecognitionOptions.parse(
                new String[] {"--watch", "--interval-ms", "500", "--stable-frames", "5"});

        assertEquals(500, options.intervalMillis(), "Interval");
        assertEquals(5, options.stableFrames(), "Stable frames");
    }

    @Test
    void helpIsRecognizedInBothSpellings() {
        assertTrue(LiveRecognitionOptions.parse(new String[] {"--help"}).help(), "--help");
        assertTrue(LiveRecognitionOptions.parse(new String[] {"-h"}).help(), "-h");
        assertTrue(LiveRecognitionOptions.usage().contains("--stable-frames"), "Usage text");
    }

    @Test
    void unknownOptionsAreRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiveRecognitionOptions.parse(new String[] {"--solve-it"}));

        assertTrue(failure.getMessage().contains("--solve-it"), "Diagnostic: " + failure.getMessage());
    }

    @Test
    void missingAndMalformedValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LiveRecognitionOptions.parse(new String[] {"--monitor"}));
        assertThrows(IllegalArgumentException.class, () -> LiveRecognitionOptions.parse(
                new String[] {"--once", "--monitor", "first"}));
        assertThrows(IllegalArgumentException.class, () -> LiveRecognitionOptions.parse(
                new String[] {"--watch", "--interval-ms", "soon"}));
    }

    @Test
    void contradictoryAndMissingModesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LiveRecognitionOptions.parse(new String[] {"--once", "--watch"}));
        assertThrows(IllegalArgumentException.class,
                () -> LiveRecognitionOptions.parse(new String[] {"--list-monitors", "--once"}));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiveRecognitionOptions.parse(new String[] {}));

        assertTrue(failure.getMessage().contains("--list-monitors, --once or --watch"),
                "Diagnostic: " + failure.getMessage());
    }

    @Test
    void watchOnlyOptionsAreRejectedInOtherModes() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiveRecognitionOptions.parse(new String[] {"--once", "--stable-frames", "2"}));

        assertTrue(failure.getMessage().contains("only valid with --watch"),
                "Diagnostic: " + failure.getMessage());
    }

    @Test
    void outOfRangeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LiveRecognitionOptions.parse(
                new String[] {"--watch", "--stable-frames", "0"}));
        assertThrows(IllegalArgumentException.class, () -> LiveRecognitionOptions.parse(
                new String[] {"--watch", "--stable-frames", "101"}));
        assertThrows(IllegalArgumentException.class, () -> LiveRecognitionOptions.parse(
                new String[] {"--watch", "--interval-ms", "0"}));
        assertThrows(IllegalArgumentException.class, () -> LiveRecognitionOptions.parse(
                new String[] {"--watch", "--interval-ms", "60001"}));
        assertThrows(IllegalArgumentException.class,
                () -> LiveRecognitionOptions.parse(new String[] {"--monitor", "-1", "--once"}));
    }
}
