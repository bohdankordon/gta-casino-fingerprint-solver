package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsSendInputSink;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.1 CLI contract: the explicit opt-in, the exact target and the five allowed controls
 * are mandatory, PROCEED/Tab is forbidden in every spelling, and every malformed invocation
 * fails before any input backend exists.
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
                        new String[] {"--target-exe", "GTA5.exe", "--control", "UP"}));
        assertTrue(error.getMessage().contains("INPUT DISABLED"), error.getMessage());
    }

    @Test
    void missingTargetExecutableRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(
                        new String[] {"--enable-input", "--control", "UP"}));
        assertTrue(error.getMessage().contains("--target-exe"), error.getMessage());
    }

    @Test
    void missingControlRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(
                        new String[] {"--enable-input", "--target-exe", "GTA5.exe"}));
        assertTrue(error.getMessage().contains("--control"), error.getMessage());
    }

    @Test
    void blankTargetExecutableRefuses() {
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "  ", "--control", "UP"}));
    }

    @Test
    void proceedIsForbiddenAndTheMessageMentionsTab() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "PROCEED"}));
        assertTrue(error.getMessage().contains("UNSUPPORTED CONTROL"), error.getMessage());
        assertTrue(error.getMessage().contains("Tab"), error.getMessage());
    }

    @Test
    void proceedIsForbiddenInAnySpelling() {
        for (String spelling : List.of("proceed", "Proceed", "PROCEED")) {
            assertThrows(IllegalArgumentException.class,
                    () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                            "--target-exe", "GTA5.exe", "--control", spelling}),
                    spelling + " must be refused");
        }
    }

    @Test
    void theOptionsRecordItselfCannotHoldProceed() {
        assertThrows(IllegalArgumentException.class,
                () -> new InputDiagnosticOptions("GTA5.exe", GameControl.PROCEED, 5, false));
    }

    @Test
    void unknownControlRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "SPACE"}));
        assertTrue(error.getMessage().contains("UNSUPPORTED CONTROL"), error.getMessage());
    }

    @Test
    void unknownOptionRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--watch"}));
        assertTrue(error.getMessage().contains("unknown option --watch"), error.getMessage());
    }

    @Test
    void malformedCountdownRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP",
                        "--countdown-seconds", "soon"}));
        assertTrue(error.getMessage().contains("not a number"), error.getMessage());
        for (String value : List.of("0", "-3", "31", "3600")) {
            IllegalArgumentException range = assertThrows(IllegalArgumentException.class,
                    () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                            "--target-exe", "GTA5.exe", "--control", "UP",
                            "--countdown-seconds", value}),
                    value + " must be out of range");
            assertTrue(range.getMessage().contains("between 1 and 30"), range.getMessage());
        }
    }

    @Test
    void countdownWithoutAValueRefuses() {
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--countdown-seconds"}));
    }

    @Test
    void duplicateValueOptionsRefuse() {
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--control", "DOWN"}));
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--target-exe", "GTA5.exe",
                        "--control", "UP"}));
        assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP",
                        "--countdown-seconds", "5", "--countdown-seconds", "5"}));
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
                "--enable-input", "--target-exe", "GTA5.exe", "--control", "SELECT"});
        assertEquals(5, options.countdownSeconds(), "Default countdown");
        assertEquals(InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS, options.countdownSeconds());
        assertEquals(GameControl.SELECT, options.control(), "Control");
        assertEquals("GTA5.exe", options.targetExecutable(), "Target");
        assertFalse(options.help(), "Not help");
    }

    @Test
    void parsesEveryAllowedControl() {
        for (GameControl control : ALLOWED) {
            InputDiagnosticOptions options = InputDiagnosticOptions.parse(new String[] {
                    "--enable-input", "--target-exe", "GTA5.exe", "--control", control.name(),
                    "--countdown-seconds", "2"});
            assertEquals(control, options.control(), control + " parses");
            assertEquals(2, options.countdownSeconds(), control + " countdown");
        }
    }

    @Test
    void allowedControlsNeverMapToTab() {
        for (GameControl control : ALLOWED) {
            assertNotEquals(0x09, WindowsSendInputSink.virtualKey(control),
                    control + " must never be Tab");
        }
    }

    @Test
    void usageExplainsTheOptInAndTheForbiddenTab() {
        String usage = InputDiagnosticOptions.usage();
        assertTrue(usage.contains("--enable-input"), "Opt-in: " + usage);
        assertTrue(usage.contains("without it nothing is sent"), "Opt-in consequence");
        assertTrue(usage.contains("--target-exe"), "Target option");
        assertTrue(usage.contains("PROCEED/Tab is"), "Forbidden control");
        assertTrue(usage.contains("AT MOST"), "One-tap promise");
    }
}
