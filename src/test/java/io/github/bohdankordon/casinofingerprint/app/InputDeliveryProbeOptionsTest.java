package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticAbortKey;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryPlan;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticInputDeliveryMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.2 CLI contract: exactly four delivery modes, the five allowed controls, the explicit
 * opt-in, the exact target and an explicitly chosen abort key are mandatory; PROCEED/Tab can
 * never enter any mode, and a hold that contradicts the selected mode is refused before any
 * backend exists. Parsing is pure: no native object is touched.
 */
class InputDeliveryProbeOptionsTest {
    private static final List<GameControl> ALLOWED = List.of(GameControl.UP, GameControl.DOWN,
            GameControl.LEFT, GameControl.RIGHT, GameControl.SELECT);
    private static final List<String> MODES = List.of("VK_BATCH", "VK_HOLD", "SCANCODE_BATCH",
            "SCANCODE_HOLD");

    @Test
    void helpNeedsNoOtherOption() {
        assertTrue(InputDeliveryProbeOptions.parse(new String[] {"--help"}).help(), "--help");
        assertTrue(InputDeliveryProbeOptions.parse(new String[] {"-h"}).help(), "-h");
    }

    @Test
    void missingEnableInputRefusesAndSaysInputIsDisabled() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[] {"--target-exe", "GTA5.exe",
                        "--control", "UP", "--delivery-mode", "VK_HOLD", "--abort-key", "F8"}));
        assertTrue(error.getMessage().contains("INPUT DISABLED"), error.getMessage());
        assertTrue(error.getMessage().contains("never sends input by default"),
                error.getMessage());
    }

    @Test
    void emptyArgumentsRefuse() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[0]));
        assertTrue(error.getMessage().contains("INPUT DISABLED"), error.getMessage());
    }

    @Test
    void everyRequiredOptionRefusesWhenMissing() {
        IllegalArgumentException noTarget = assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[] {"--enable-input",
                        "--control", "UP", "--delivery-mode", "VK_HOLD", "--abort-key", "F8"}));
        assertTrue(noTarget.getMessage().contains("--target-exe"), noTarget.getMessage());

        IllegalArgumentException noControl = assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--delivery-mode", "VK_HOLD",
                        "--abort-key", "F8"}));
        assertTrue(noControl.getMessage().contains("--control"), noControl.getMessage());

        IllegalArgumentException noMode = assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "F8"}));
        assertTrue(noMode.getMessage().contains("--delivery-mode"), noMode.getMessage());
        assertTrue(noMode.getMessage().contains("SCANCODE_HOLD"),
                "The refusal lists the modes: " + noMode.getMessage());

        IllegalArgumentException noAbortKey = assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--delivery-mode",
                        "VK_HOLD"}));
        assertTrue(noAbortKey.getMessage().contains("--abort-key"), noAbortKey.getMessage());
        assertTrue(noAbortKey.getMessage().contains("never picks an emergency key automatically"),
                noAbortKey.getMessage());
        assertTrue(noAbortKey.getMessage().contains("Steam"),
                "The refusal explains the F12/Steam conflict: " + noAbortKey.getMessage());

        IllegalArgumentException blankTarget = assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "  ", "--control", "UP", "--delivery-mode", "VK_HOLD",
                        "--abort-key", "F8"}));
        assertTrue(blankTarget.getMessage().contains("--target-exe"), blankTarget.getMessage());
    }

    @Test
    void exactlyFourDeliveryModesAreAccepted() {
        assertEquals(4, DiagnosticInputDeliveryMode.values().length, "Four modes");
        for (String mode : MODES) {
            InputDeliveryProbeOptions options = parse("UP", mode);
            assertEquals(mode, options.deliveryMode().name(), mode + " parses");
        }
    }

    @Test
    void unknownOrLowercaseDeliveryModesAreRefused() {
        for (String unknown : List.of("VK", "SCANCODE", "vk_hold", "scancode_batch", "HOLD")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> parse("UP", unknown), unknown + " must be refused");
            assertTrue(error.getMessage().contains("UNSUPPORTED DELIVERY MODE"),
                    unknown + ": " + error.getMessage());
        }
    }

    @Test
    void proceedCanNeverEnterAnyMode() {
        for (String mode : MODES) {
            for (String spelling : List.of("PROCEED", "proceed", "Proceed")) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> parse(spelling, mode), spelling + " with " + mode + " must refuse");
                assertTrue(error.getMessage().contains("UNSUPPORTED CONTROL"), error.getMessage());
                assertTrue(error.getMessage().contains("Tab"), error.getMessage());
            }
        }
        IllegalArgumentException asMode = assertThrows(IllegalArgumentException.class,
                () -> parse("UP", "PROCEED"));
        assertTrue(asMode.getMessage().contains("Tab"), asMode.getMessage());
    }

    @Test
    void theOptionsRecordItselfCannotHoldProceed() {
        assertThrows(IllegalArgumentException.class, () -> new InputDeliveryProbeOptions(
                "GTA5.exe", GameControl.PROCEED, DiagnosticInputDeliveryMode.VK_HOLD, 50,
                DiagnosticAbortKey.F8, 5, false));
    }

    @Test
    void exactlyTheFiveAllowedControlsAreAccepted() {
        assertEquals(5, ALLOWED.size(), "Five controls");
        for (String mode : MODES) {
            for (GameControl control : ALLOWED) {
                assertEquals(control, parse(control.name(), mode).control(),
                        control + " with " + mode + " parses");
            }
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("SPACE", "VK_BATCH"));
        assertTrue(error.getMessage().contains("UNSUPPORTED CONTROL"), error.getMessage());
    }

    @Test
    void noAllowedControlEverMapsToTab() {
        List<Integer> virtualKeys = new ArrayList<>();
        for (GameControl control : ALLOWED) {
            assertEquals(historicalVirtualKey(control),
                    DiagnosticDeliveryPlan.forTap(control, DiagnosticInputDeliveryMode.VK_BATCH, 0)
                            .down().wVk(),
                    control + " keeps the historical virtual-key mapping");
            assertNotEquals(0x09, historicalVirtualKey(control),
                    control + " must never be Tab");
            virtualKeys.add(historicalVirtualKey(control));
        }
        assertEquals(5, virtualKeys.stream().distinct().count(), "five distinct keys");
    }

    @Test
    void holdModesDefaultToFiftyMilliseconds() {
        for (String mode : List.of("VK_HOLD", "SCANCODE_HOLD")) {
            InputDeliveryProbeOptions options = parse("UP", mode);
            assertEquals(DiagnosticDeliveryPlan.DEFAULT_HOLD_MILLIS, options.holdMillis(),
                    mode + " defaults to 50 ms");
            assertEquals(50, options.holdMillis(), mode + " default is exactly 50");
        }
    }

    @Test
    void anExplicitHoldIsPreserved() {
        for (String mode : List.of("VK_HOLD", "SCANCODE_HOLD")) {
            assertEquals(100, parse("UP", mode, "--hold-ms", "100").holdMillis(),
                    mode + " keeps the explicit hold");
        }
    }

    @Test
    void holdBoundsAreTenToTwoHundred() {
        for (String mode : List.of("VK_HOLD", "SCANCODE_HOLD")) {
            for (String invalid : List.of("0", "9", "201", "1000", "-5")) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> parse("UP", mode, "--hold-ms", invalid),
                        mode + " with hold " + invalid + " must be refused");
                assertTrue(error.getMessage().contains("between 10 and 200"),
                        mode + ": " + error.getMessage());
            }
            assertEquals(10, parse("UP", mode, "--hold-ms", "10").holdMillis(), "lower bound");
            assertEquals(200, parse("UP", mode, "--hold-ms", "200").holdMillis(), "upper bound");
        }
    }

    @Test
    void malformedHoldRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("UP", "VK_HOLD", "--hold-ms", "soon"));
        assertTrue(error.getMessage().contains("not a number"), error.getMessage());
    }

    @Test
    void batchModesRejectAnExplicitHoldOutright() {
        for (String mode : List.of("VK_BATCH", "SCANCODE_BATCH")) {
            for (String hold : List.of("0", "50", "200")) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> parse("UP", mode, "--hold-ms", hold),
                        mode + " with --hold-ms " + hold + " must be refused");
                assertTrue(error.getMessage().contains("contradictory"),
                        mode + ": " + error.getMessage());
                assertTrue(error.getMessage().contains("no-sleep batch"),
                        mode + ": " + error.getMessage());
            }
        }
    }

    @Test
    void batchModesCarryNoHold() {
        for (String mode : List.of("VK_BATCH", "SCANCODE_BATCH")) {
            assertEquals(0, parse("UP", mode).holdMillis(), mode + " never holds");
        }
    }

    @Test
    void theAbortKeyIsExplicitlyParsedAndNeverDefaulted() {
        assertEquals(DiagnosticAbortKey.F8, parse("UP", "VK_BATCH", "--abort-key", "F8").abortKey(),
                "F8");
        assertEquals(DiagnosticAbortKey.PAUSE,
                parse("UP", "VK_BATCH", "--abort-key", "PAUSE").abortKey(), "PAUSE");
        assertEquals(DiagnosticAbortKey.SCROLL_LOCK,
                parse("UP", "VK_BATCH", "--abort-key", "SCROLL_LOCK").abortKey(), "SCROLL_LOCK");
        assertEquals(DiagnosticAbortKey.F8, parse("UP", "VK_BATCH", "--abort-key", "f8").abortKey(),
                "symbolic names ignore case");
    }

    @Test
    void controlsCannotBeAbortKeys() {
        for (String reserved : List.of("UP", "DOWN", "LEFT", "RIGHT", "ENTER", "TAB", "SELECT",
                "PROCEED", "up", "Tab")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> parse("UP", "VK_BATCH", "--abort-key", reserved),
                    reserved + " must be refused as an abort key");
            assertTrue(error.getMessage().contains("REFUSED ABORT KEY"),
                    reserved + ": " + error.getMessage());
        }
    }

    @Test
    void unknownAbortKeyRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("UP", "VK_BATCH", "--abort-key", "HOME"));
        assertTrue(error.getMessage().contains("F1..F24"), error.getMessage());
    }

    @Test
    void unknownOptionRefuses() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("UP", "VK_BATCH", "--matrix"));
        assertTrue(error.getMessage().contains("unknown option --matrix"), error.getMessage());
    }

    @Test
    void duplicateOptionsRefuse() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("UP", "VK_BATCH", "--control", "DOWN"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("UP", "VK_BATCH", "--delivery-mode", "VK_HOLD"));
        assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--control", "UP", "--delivery-mode",
                        "VK_BATCH", "--abort-key", "F8", "--abort-key", "F9"}));
        assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5.exe", "--target-exe", "GTA5.exe", "--control",
                        "UP", "--delivery-mode", "VK_BATCH", "--abort-key", "F8"}));
        assertThrows(IllegalArgumentException.class,
                () -> parse("UP", "VK_HOLD", "--hold-ms", "50", "--hold-ms", "60"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("UP", "VK_BATCH", "--countdown-seconds", "1",
                        "--countdown-seconds", "2"));
    }

    @Test
    void countdownDefaultsToFiveAndStaysBounded() {
        int defaultCountdown = InputDeliveryProbeOptions.parse(new String[] {"--enable-input",
                "--target-exe", "GTA5.exe", "--control", "UP", "--delivery-mode", "VK_HOLD",
                "--abort-key", "F8"}).countdownSeconds();
        assertEquals(5, defaultCountdown, "Default countdown");
        assertEquals(InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS,
                defaultCountdown, "Shared default");
        assertEquals(1, parse("UP", "VK_BATCH").countdownSeconds(), "Explicit countdown");
        for (String invalid : List.of("0", "31", "-1")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> parse("UP", "VK_BATCH", "--countdown-seconds", invalid),
                    invalid + " must be out of range");
            assertTrue(error.getMessage().contains("between 1 and 30"), error.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> parse("UP", "VK_BATCH", "--countdown-seconds"));
    }

    @Test
    void usageDocumentsTheFullCharacterizationContract() {
        String usage = InputDeliveryProbeOptions.usage();
        for (String mode : MODES) {
            assertTrue(usage.contains(mode), "Usage names " + mode + ": " + usage);
        }
        assertTrue(usage.contains("--enable-input"), "Opt-in");
        assertTrue(usage.contains("without it nothing is sent"), "Opt-in consequence");
        assertTrue(usage.contains("--abort-key"), "Abort key option");
        assertTrue(usage.contains("F1..F24"), "Supported abort keys");
        assertTrue(usage.contains("PAUSE") && usage.contains("SCROLL_LOCK"),
                "Named physical abort keys");
        assertTrue(usage.contains("Steam"), "Steam conflicts documented");
        assertTrue(usage.contains("Rockstar"), "Rockstar conflicts documented");
        assertTrue(usage.contains("default 50") && usage.contains("10..200"),
                "Hold contract documented");
        assertTrue(usage.contains("PROCEED/Tab is forbidden"), "Forbidden control");
        assertTrue(usage.contains("AT MOST ONE"), "One-tap promise");
        assertTrue(usage.contains("no matrix mode"), "No matrix mode");
        assertTrue(usage.contains("3 runtime refusal or input failure"), "Exit codes");
        assertTrue(usage.contains("not confirm complete delivery"),
                "INPUT ERROR semantics");
    }

    @Test
    void parserRefusalsGuaranteeZeroInputBecauseNoTapCanBeReached() {
        IllegalArgumentException disabled = assertThrows(IllegalArgumentException.class,
                () -> InputDeliveryProbeOptions.parse(new String[] {"--target-exe", "GTA5.exe",
                        "--control", "UP", "--delivery-mode", "VK_HOLD", "--abort-key", "F8"}));
        assertTrue(disabled.getMessage().contains("INPUT DISABLED"), disabled.getMessage());
        assertFalse(InputDiagnosticStatus.INPUT_DISABLED.inputAttempted(),
                "A parser refusal is a pre-tap refusal");
        assertTrue(InputDiagnosticStatus.INPUT_DISABLED.zeroInputGuaranteed(),
                "A parser refusal guarantees zero input");
    }

    private static InputDeliveryProbeOptions parse(String control, String mode, String... extra) {
        List<String> args = new ArrayList<>(List.of("--enable-input", "--target-exe",
                "GTA5_Enhanced.exe", "--control", control, "--delivery-mode", mode));
        if (List.of(extra).stream().noneMatch("--abort-key"::equals)) {
            args.addAll(List.of("--abort-key", "F8"));
        }
        if (List.of(extra).stream().noneMatch("--countdown-seconds"::equals)) {
            args.addAll(List.of("--countdown-seconds", "1"));
        }
        args.addAll(List.of(extra));
        return InputDeliveryProbeOptions.parse(args.toArray(String[]::new));
    }
    /** Historical pre-promotion virtual-key baseline (Stage 8C.4 production is scan-code). */
    private static int historicalVirtualKey(GameControl control) {
        return switch (control) {
            case UP -> 0x26;
            case DOWN -> 0x28;
            case LEFT -> 0x25;
            case RIGHT -> 0x27;
            case SELECT -> 0x0D;
            case PROCEED -> throw new IllegalArgumentException("PROCEED has no virtual-key plan");
        };
    }
}
