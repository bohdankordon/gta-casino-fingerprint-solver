package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.4 CLI contract: the explicit opt-in, the exact target, the five allowed controls
 * and an explicitly named abort key are mandatory, PROCEED/Tab is forbidden in every
 * spelling, and every malformed invocation fails before any input backend exists.
 */
class InputDiagnosticOptionsTest {
    private static final List<GameControl> ALLOWED = List.of(GameControl.UP, GameControl.DOWN,
            GameControl.LEFT, GameControl.RIGHT, GameControl.SELECT);

    @Test
    void helpNeedsNoOtherOption() {
        assertTrue(InputDiagnosticOptions.parse(new String[] {"--help"}).help(), "--help");
        assertTrue(InputDiagnosticOptions.parse(new String[] {"-h"}).help(), "-h");
    }

    @Test
    void missingEnableInputRefusesAndSaysInputIsDisabled() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(
                        new String[] {"--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("INPUT DISABLED"), error.getMessage());
    }

    @Test
    void missingTargetExecutableRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(
                        new String[] {"--enable-input", "--control", "UP", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("--target-exe"), error.getMessage());
    }

    @Test
    void missingControlRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(
                        new String[] {"--enable-input", "--target-exe", "GTA5.exe", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("--control"), error.getMessage());
    }

    @Test
    void missingAbortKeyRefusesAndNamesTheSupportedKeys() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(
                        new String[] {"--enable-input", "--target-exe", "GTA5.exe", "--control", "UP"}));
        assertTrue(error.getMessage().contains("--abort-key"), error.getMessage());
        assertTrue(error.getMessage().contains("never picks an emergency key automatically"), error.getMessage());
        assertTrue(error.getMessage().contains("Steam"), error.getMessage());
    }

    @Test
    void blankTargetExecutableRefuses() {
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "  ", "--control", "UP", "--abort-key", "SCROLL_LOCK"}));
    }

    @Test
    void proceedIsForbiddenAndTheMessageMentionsTab() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "PROCEED", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("UNSUPPORTED CONTROL"), error.getMessage());
        assertTrue(error.getMessage().contains("Tab"), error.getMessage());
    }

    @Test
    void proceedIsForbiddenInAnySpelling() {
        for (String spelling : List.of("proceed", "Proceed", "PROCEED")) {
            assertThrows(IllegalArgumentException.class,
                    () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                            "--target-exe", "GTA5.exe", "--control", spelling, "--abort-key", "SCROLL_LOCK"}),
                    spelling + " must be refused");
        }
    }

    @Test
    void theOptionsRecordItselfCannotHoldProceed() {
        assertThrows(IllegalArgumentException.class,
                () -> new InputDiagnosticOptions("GTA5.exe", GameControl.PROCEED, EmergencyAbortKey.SCROLL_LOCK, 5, false));
    }

    @Test
    void theOptionsRecordItselfCannotHoldANullAbortKey() {
        assertThrows(IllegalArgumentException.class,
                () -> new InputDiagnosticOptions("GTA5.exe", GameControl.UP, null, 5, false));
    }

    @Test
    void unknownControlRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "SPACE", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("UNSUPPORTED CONTROL"), error.getMessage());
    }

    @Test
    void unknownAbortKeyRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "HOME"}));
        assertTrue(error.getMessage().contains("F1..F24"), error.getMessage());
    }

    @Test
    void gameplayControlCanNeverBeAnAbortKey() {
        for (String reserved : List.of("UP", "TAB", "SELECT", "ENTER")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                            "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", reserved}),
                    reserved + " must be refused");
            assertTrue(error.getMessage().contains("REFUSED ABORT KEY"), error.getMessage());
        }
    }

    @Test
    void unknownOptionRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK", "--watch"}));
        assertTrue(error.getMessage().contains("unknown option --watch"), error.getMessage());
    }

    @Test
    void malformedCountdownRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK",
                        "--countdown-seconds", "soon"}));
        assertTrue(error.getMessage().contains("not a number"), error.getMessage());
        for (String value : List.of("0", "-3", "31", "3600")) {
            IllegalArgumentException range = assertThrows(IllegalArgumentException.class,
                    () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                            "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK",
                            "--countdown-seconds", value}),
                    value + " must be out of range");
            assertTrue(range.getMessage().contains("between 1 and 30"), range.getMessage());
        }
    }

    @Test
    void countdownWithoutAValueRefuses() {
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK", "--countdown-seconds"}));
    }

    @Test
    void duplicateValueOptionsRefuse() {
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK", "--control", "DOWN"}));
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--target-exe", "GTA5.exe",
                        "--control", "UP", "--abort-key", "SCROLL_LOCK"}));
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK",
                        "--countdown-seconds", "5", "--countdown-seconds", "5"}));
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK",
                        "--abort-key", "F8"}));
    }

    @Test
    void emptyArgumentsRefuse() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[0]));
        assertTrue(error.getMessage().contains("INPUT DISABLED"), error.getMessage());
    }

    @Test
    void defaultsTheCountdownToFiveSeconds() {
        InputDiagnosticOptions options = InputDiagnosticOptions.parse(new String[] {
                "--enable-input", "--target-exe", "GTA5.exe", "--control", "SELECT", "--abort-key", "SCROLL_LOCK"});
        assertEquals(5, options.countdownSeconds(), "Default countdown");
        assertEquals(InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS, options.countdownSeconds());
        assertEquals(GameControl.SELECT, options.control(), "Control");
        assertEquals("GTA5.exe", options.targetExecutable(), "Target");
        assertEquals(EmergencyAbortKey.SCROLL_LOCK, options.abortKey(), "Abort key");
        assertFalse(options.help(), "Not help");
    }

    @Test
    void parsesEveryAllowedControlWithAnExplicitAbortKey() {
        for (GameControl control : ALLOWED) {
            InputDiagnosticOptions options = InputDiagnosticOptions.parse(new String[] {
                    "--enable-input", "--target-exe", "GTA5.exe", "--control", control.name(),
                    "--abort-key", "SCROLL_LOCK", "--countdown-seconds", "2"});
            assertEquals(control, options.control(), control + " parses");
            assertEquals(2, options.countdownSeconds(), control + " countdown");
            assertEquals(EmergencyAbortKey.SCROLL_LOCK, options.abortKey());
        }
    }

    @Test
    void scrollLockParsesForTheValidatedSmokeInvocation() {
        InputDiagnosticOptions options = InputDiagnosticOptions.parse(new String[] {
                "--enable-input", "--target-exe", "GTA5_Enhanced.exe", "--control", "UP",
                "--abort-key", "SCROLL_LOCK", "--countdown-seconds", "5"});
        assertEquals(EmergencyAbortKey.SCROLL_LOCK, options.abortKey());
        assertEquals(0x91, options.abortKey().virtualKeyCode());
    }

    @Test
    void usageExplainsTheOptInAbortKeyAndTheForbiddenTab() {
        String usage = InputDiagnosticOptions.usage();
        assertTrue(usage.contains("--enable-input"), "Opt-in: " + usage);
        assertTrue(usage.contains("without it nothing is sent"), "Opt-in consequence");
        assertTrue(usage.contains("--target-exe"), "Target option");
        assertTrue(usage.contains("--abort-key"), "Abort key option: " + usage);
        assertTrue(usage.contains("PROCEED/Tab is"), "Forbidden control");
        assertTrue(usage.contains("AT MOST"), "One-tap promise");
        assertTrue(usage.contains("SCANCODE_BATCH"), "Production delivery: " + usage);
    }

    @Test
    void parserRefusalsGuaranteeZeroInputBecauseNoTapCanBeReached() {
        IllegalArgumentException disabled = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(
                        new String[] {"--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(disabled.getMessage().contains("INPUT DISABLED"), disabled.getMessage());
        assertTrue(disabled.getMessage().contains("never sends input by default"),
                disabled.getMessage());
        assertTrue(InputDiagnosticStatus.INPUT_DISABLED.zeroInputGuaranteed(),
                "INPUT DISABLED is a pre-tap refusal");

        IllegalArgumentException proceed = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "PROCEED", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(proceed.getMessage().contains("UNSUPPORTED CONTROL"), proceed.getMessage());
        assertTrue(proceed.getMessage().contains("Tab is never sent"), proceed.getMessage());
        assertTrue(InputDiagnosticStatus.UNSUPPORTED_CONTROL.zeroInputGuaranteed(),
                "UNSUPPORTED CONTROL is a pre-tap refusal");

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "SPACE", "--abort-key", "SCROLL_LOCK"}));
        assertTrue(unknown.getMessage().contains("UNSUPPORTED CONTROL"), unknown.getMessage());
    }

    @Test
    void usageDocumentsExitCodesWithoutPromisingZeroInputForInputErrors() {
        String usage = InputDiagnosticOptions.usage();
        assertTrue(usage.contains("3 runtime refusal or input failure"), usage);
        assertFalse(usage.contains("3 refused with zero input"),
                "Exit code 3 no longer means zero input by itself: " + usage);
        assertTrue(usage.contains("INPUT ERROR"), "Names the after-tap failure: " + usage);
        assertTrue(usage.contains("does not confirm complete delivery"), usage);
    }
}
