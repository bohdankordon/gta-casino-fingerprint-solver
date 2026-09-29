package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Passive external-video CLI parsing: strict options and no gameplay-input flag. */
class ExternalVideoValidationOptionsTest {
    @Test
    void watchRequiresASession() {
        ExternalVideoValidationOptions options = ExternalVideoValidationOptions.parse(
                new String[] {"--monitor", "0", "--watch", "--session", "youtube-01"});
        assertEquals(ExternalVideoValidationOptions.Mode.WATCH, options.mode());
        assertEquals("youtube-01", options.session());
        assertEquals(Integer.valueOf(0), options.monitorIndex());
    }

    @Test
    void enableInputIsExplicitlyRejected() {
        assertThrows(IllegalArgumentException.class, () -> ExternalVideoValidationOptions.parse(
                new String[] {"--watch", "--session", "youtube-01", "--enable-input"}));
    }

    @Test
    void unknownFlagsAndContradictionsFail() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalVideoValidationOptions.parse(new String[] {"--watch"}));
        assertThrows(IllegalArgumentException.class, () -> ExternalVideoValidationOptions.parse(
                new String[] {"--list-monitors", "--watch", "--session", "x"}));
        assertThrows(IllegalArgumentException.class, () -> ExternalVideoValidationOptions.parse(
                new String[] {"--watch", "--session", "../escape"}));
        assertThrows(IllegalArgumentException.class, () -> ExternalVideoValidationOptions.parse(
                new String[] {"--bogus"}));
    }

    @Test
    void usageDocumentsPassiveWorkflow() {
        String usage = ExternalVideoValidationOptions.usage();
        assertTrue(usage.contains("--session"), "session flag: " + usage);
        assertTrue(usage.contains("never sends"), "no-input promise: " + usage);
    }
}

