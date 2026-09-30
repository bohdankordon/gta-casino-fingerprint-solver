package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticAbortKey;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.3 dedicated CLI contract: double opt-in, exact target, explicit abort key and
 * nothing else. There is no control, mode or hold to choose: those spellings refuse as
 * unknown options before any backend exists. Parsing is pure: no native object is touched.
 */
class ProceedInputProbeOptionsTest {
    @Test
    void helpNeedsNoOtherOption() {
        assertTrue(ProceedInputProbeOptions.parse(new String[] {"--help"}).help(), "--help");
        assertTrue(ProceedInputProbeOptions.parse(new String[] {"-h"}).help(), "-h");
    }

    @Test
    void emptyArgumentsRefuseAsInputDisabled() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[0]));
        assertTrue(error.getMessage().contains("INPUT DISABLED"), error.getMessage());
    }

    @Test
    void missingEnableInputRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[] {"--enable-proceed-test",
                        "--target-exe", "GTA5_Enhanced.exe", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("INPUT DISABLED"), error.getMessage());
        assertTrue(error.getMessage().contains("--enable-input"), error.getMessage());
    }

    @Test
    void missingEnableProceedTestRefusesWithNoDefault() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5_Enhanced.exe", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("INPUT DISABLED"), error.getMessage());
        assertTrue(error.getMessage().contains("--enable-proceed-test"), error.getMessage());
    }

    @Test
    void missingTargetRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                        "--enable-proceed-test", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("--target-exe"), error.getMessage());
    }

    @Test
    void blankTargetRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                        "--enable-proceed-test", "--target-exe", "  ",
                        "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("--target-exe"), error.getMessage());
    }

    @Test
    void missingAbortKeyRefusesWithNoDefault() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                        "--enable-proceed-test", "--target-exe", "GTA5_Enhanced.exe"}));
        assertTrue(error.getMessage().contains("--abort-key"), error.getMessage());
        assertTrue(error.getMessage().contains("never picks an emergency key automatically"),
                error.getMessage());
    }

    @Test
    void controlDeliveryModeAndHoldAreUnknownOptions() {
        for (String forbidden : new String[] {"--control", "--delivery-mode", "--hold-ms"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                            "--enable-proceed-test", "--target-exe", "GTA5_Enhanced.exe",
                            "--abort-key", "SCROLL_LOCK", forbidden, "X"}),
                    forbidden + " must be unknown");
            assertTrue(error.getMessage().contains("unknown option " + forbidden),
                    forbidden + ": " + error.getMessage());
        }
    }

    @Test
    void successfulParseKeepsTargetAbortAndCountdown() {
        ProceedInputProbeOptions options = ProceedInputProbeOptions.parse(new String[] {
                "--enable-input", "--enable-proceed-test", "--target-exe", "GTA5_Enhanced.exe",
                "--abort-key", "SCROLL_LOCK"});
        assertEquals("GTA5_Enhanced.exe", options.targetExecutable(), "Target");
        assertEquals(DiagnosticAbortKey.SCROLL_LOCK, options.abortKey(), "Abort key");
        assertEquals(InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS, options.countdownSeconds(),
                "Default countdown is 5");
        assertFalse(options.help(), "Not help");
    }

    @Test
    void explicitCountdownIsPreserved() {
        ProceedInputProbeOptions options = ProceedInputProbeOptions.parse(new String[] {
                "--enable-input", "--enable-proceed-test", "--target-exe", "GTA5_Enhanced.exe",
                "--abort-key", "F8", "--countdown-seconds", "1"});
        assertEquals(1, options.countdownSeconds(), "Explicit countdown");
    }

    @Test
    void countdownBoundsAreOneToThirty() {
        for (String invalid : new String[] {"0", "31", "100", "-5"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                            "--enable-proceed-test", "--target-exe", "GTA5_Enhanced.exe",
                            "--abort-key", "F8", "--countdown-seconds", invalid}),
                    "countdown " + invalid + " must be refused");
            assertTrue(error.getMessage().contains("--countdown-seconds"),
                    error.getMessage());
        }
        assertEquals(1, fullParseWithCountdown("1").countdownSeconds(), "lower bound");
        assertEquals(30, fullParseWithCountdown("30").countdownSeconds(), "upper bound");
    }

    @Test
    void malformedCountdownRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> fullParseWithCountdown("soon"));
        assertTrue(error.getMessage().contains("not a number"), error.getMessage());
    }

    @Test
    void abortKeyIsExplicitlyParsedAndNeverDefaulted() {
        assertEquals(DiagnosticAbortKey.SCROLL_LOCK, fullParseWithAbort("SCROLL_LOCK").abortKey(),
                "SCROLL_LOCK");
        assertEquals(DiagnosticAbortKey.F8, fullParseWithAbort("F8").abortKey(), "F8");
        assertEquals(DiagnosticAbortKey.PAUSE, fullParseWithAbort("PAUSE").abortKey(), "PAUSE");
        assertEquals(DiagnosticAbortKey.F8, fullParseWithAbort("f8").abortKey(),
                "names ignore case");
    }

    @Test
    void controlsCannotBeAbortKeys() {
        for (String reserved : new String[] {"UP", "DOWN", "LEFT", "RIGHT", "ENTER", "TAB",
                "SELECT", "PROCEED", "up", "Tab"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> fullParseWithAbort(reserved), reserved + " must be refused");
            assertTrue(error.getMessage().contains("REFUSED ABORT KEY"),
                    reserved + ": " + error.getMessage());
        }
    }

    @Test
    void unknownAbortKeyRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> fullParseWithAbort("HOME"));
        assertTrue(error.getMessage().contains("F1..F24"), error.getMessage());
    }

    @Test
    void unknownOptionRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                        "--enable-proceed-test", "--target-exe", "GTA5_Enhanced.exe",
                        "--abort-key", "F8", "--matrix"}));
        assertTrue(error.getMessage().contains("unknown option --matrix"), error.getMessage());
    }

    @Test
    void duplicateOptionsRefuse() {
        assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                        "--enable-proceed-test", "--target-exe", "A.exe", "--target-exe", "B.exe",
                        "--abort-key", "F8"}));
        assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                        "--enable-proceed-test", "--target-exe", "A.exe", "--abort-key", "F8",
                        "--abort-key", "F9"}));
        assertThrows(IllegalArgumentException.class,
                () -> ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                        "--enable-proceed-test", "--target-exe", "A.exe", "--abort-key", "F8",
                        "--countdown-seconds", "5", "--countdown-seconds", "6"}));
    }

    @Test
    void usageNamesDoubleOptInAndFixedTabContract() {
        String usage = ProceedInputProbeOptions.usage();
        assertTrue(usage.contains("--enable-input"), usage);
        assertTrue(usage.contains("--enable-proceed-test"), usage);
        assertTrue(usage.contains("--target-exe"), usage);
        assertTrue(usage.contains("--abort-key"), usage);
        assertTrue(usage.contains("SCANCODE_BATCH"), usage);
        assertTrue(usage.contains("0x0F"), usage);
        assertFalse(usage.contains("--control"), "No control choice: " + usage);
        assertFalse(usage.contains("--delivery-mode"), "No mode choice: " + usage);
        assertFalse(usage.contains("--hold-ms"), "No hold choice: " + usage);
    }

    private static ProceedInputProbeOptions fullParseWithCountdown(String countdown) {
        return ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                "--enable-proceed-test", "--target-exe", "GTA5_Enhanced.exe", "--abort-key",
                "F8", "--countdown-seconds", countdown});
    }

    private static ProceedInputProbeOptions fullParseWithAbort(String abortKey) {
        return ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                "--enable-proceed-test", "--target-exe", "GTA5_Enhanced.exe", "--abort-key",
                abortKey});
    }
}
