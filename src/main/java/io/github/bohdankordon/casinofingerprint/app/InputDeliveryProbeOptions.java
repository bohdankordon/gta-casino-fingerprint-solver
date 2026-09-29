package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticAbortKey;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryPlan;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticInputDeliveryMode;

/**
 * Parsed command-line options of the Stage 8C.2 input-delivery characterization probe.
 *
 * <p>An instance exists only after the explicit {@code --enable-input} opt-in plus an exact
 * {@code --target-exe}, exactly one allowed control, exactly one explicitly selected delivery
 * mode and an explicitly named abort key. Parsing is pure: no native object and no input
 * backend is touched here.
 *
 * <p>The hold rule is part of the parser contract: the HOLD modes take {@code --hold-ms} inside
 * 10..200 ms and default to 50 ms when it is omitted, while the BATCH modes reject
 * {@code --hold-ms} outright because they submit one batch with no sleep.
 *
 * <p>Allowed controls are the five Stage 8C controls {@code UP}, {@code DOWN}, {@code LEFT},
 * {@code RIGHT} and {@code SELECT}. There is deliberately no alias that could expose
 * {@link GameControl#PROCEED} (Tab), in any delivery mode.
 *
 * @param targetExecutable exact foreground executable file name, for example
 *        {@code GTA5_Enhanced.exe}
 * @param control the single allowed control to tap; null only for {@code --help}
 * @param deliveryMode the explicitly selected delivery mode; null only for {@code --help}
 * @param holdMillis the effective hold: 10..200 for the HOLD modes, zero for the BATCH modes
 * @param abortKey the explicitly chosen emergency abort key; null only for {@code --help}
 * @param countdownSeconds seconds to switch to GTA before the foreground gates
 * @param help true when the invocation only asks for help
 */
public record InputDeliveryProbeOptions(String targetExecutable, GameControl control,
        DiagnosticInputDeliveryMode deliveryMode, int holdMillis, DiagnosticAbortKey abortKey,
        int countdownSeconds, boolean help) {

    public InputDeliveryProbeOptions {
        if (!help) {
            if (targetExecutable == null || targetExecutable.isBlank()) {
                throw new IllegalArgumentException(
                        "--target-exe <exact executable name> is required");
            }
            if (control == null) {
                throw new IllegalArgumentException(
                        "--control <UP|DOWN|LEFT|RIGHT|SELECT> is required");
            }
            if (control == GameControl.PROCEED) {
                throw new IllegalArgumentException(
                        "UNSUPPORTED CONTROL: PROCEED is forbidden in Stage 8C: Tab is never"
                                + " sent by this probe in any delivery mode");
            }
            if (deliveryMode == null) {
                throw new IllegalArgumentException("--delivery-mode <"
                        + DiagnosticInputDeliveryMode.NAMES + "> is required: one invocation"
                        + " characterizes exactly one explicitly selected mode");
            }
            if (abortKey == null) {
                throw new IllegalArgumentException("--abort-key <" + DiagnosticAbortKey.SYMBOLS
                        + "> is required: this probe never picks an emergency key automatically,"
                        + " because F12 (the old default) is a Steam screenshot shortcut; check"
                        + " your Steam and GTA bindings and name the unbound key you chose");
            }
            if (countdownSeconds < InputDiagnosticRequest.MIN_COUNTDOWN_SECONDS
                    || countdownSeconds > InputDiagnosticRequest.MAX_COUNTDOWN_SECONDS) {
                throw new IllegalArgumentException("--countdown-seconds must be between "
                        + InputDiagnosticRequest.MIN_COUNTDOWN_SECONDS + " and "
                        + InputDiagnosticRequest.MAX_COUNTDOWN_SECONDS + ", got "
                        + countdownSeconds);
            }
            DiagnosticDeliveryPlan.validateHold(deliveryMode, holdMillis);
        }
    }

    /**
     * Parses {@code args}.
     *
     * @throws IllegalArgumentException for unknown options, duplicate options, missing values,
     *         malformed or out-of-range numbers, a missing opt-in, a missing mode, a missing
     *         abort key, a hold that contradicts the selected mode and any control that is not
     *         one of the five allowed Stage 8C controls
     */
    public static InputDeliveryProbeOptions parse(String[] args) {
        boolean enableInput = false;
        boolean help = false;
        String targetExecutable = null;
        String controlName = null;
        String modeName = null;
        String abortKeyName = null;
        Integer holdMillis = null;
        Integer countdownSeconds = null;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--enable-input" -> enableInput = true;
                case "--help", "-h" -> help = true;
                case "--target-exe" -> {
                    if (targetExecutable != null) {
                        throw new IllegalArgumentException("duplicate option --target-exe");
                    }
                    targetExecutable = textValue(args, ++index, argument);
                }
                case "--control" -> {
                    if (controlName != null) {
                        throw new IllegalArgumentException("duplicate option --control");
                    }
                    controlName = textValue(args, ++index, argument);
                }
                case "--delivery-mode" -> {
                    if (modeName != null) {
                        throw new IllegalArgumentException("duplicate option --delivery-mode");
                    }
                    modeName = textValue(args, ++index, argument);
                }
                case "--abort-key" -> {
                    if (abortKeyName != null) {
                        throw new IllegalArgumentException("duplicate option --abort-key");
                    }
                    abortKeyName = textValue(args, ++index, argument);
                }
                case "--hold-ms" -> {
                    if (holdMillis != null) {
                        throw new IllegalArgumentException("duplicate option --hold-ms");
                    }
                    holdMillis = intValue(args, ++index, argument);
                }
                case "--countdown-seconds" -> {
                    if (countdownSeconds != null) {
                        throw new IllegalArgumentException(
                                "duplicate option --countdown-seconds");
                    }
                    countdownSeconds = intValue(args, ++index, argument);
                }
                default -> throw new IllegalArgumentException("unknown option " + argument);
            }
        }
        if (help) {
            return new InputDeliveryProbeOptions(null, null, null, 0, null,
                    InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS, true);
        }
        if (!enableInput) {
            throw new IllegalArgumentException("INPUT DISABLED: --enable-input is required;"
                    + " this probe sends at most one tap and never sends input by default"
                    + " (see --help)");
        }
        if (targetExecutable == null) {
            throw new IllegalArgumentException("--target-exe <exact executable name> is required");
        }
        if (controlName == null) {
            throw new IllegalArgumentException(
                    "--control <UP|DOWN|LEFT|RIGHT|SELECT> is required");
        }
        if (modeName == null) {
            throw new IllegalArgumentException("--delivery-mode <"
                    + DiagnosticInputDeliveryMode.NAMES + "> is required: one invocation"
                    + " characterizes exactly one explicitly selected mode");
        }
        if (abortKeyName == null) {
            throw new IllegalArgumentException("--abort-key <" + DiagnosticAbortKey.SYMBOLS
                    + "> is required: this probe never picks an emergency key automatically,"
                    + " because F12 (the old default) is a Steam screenshot shortcut; check your"
                    + " Steam and GTA bindings and name the unbound key you chose");
        }
        GameControl control = parseControl(controlName);
        DiagnosticInputDeliveryMode deliveryMode =
                DiagnosticInputDeliveryMode.parse(modeName);
        DiagnosticAbortKey abortKey = DiagnosticAbortKey.parse(abortKeyName);
        if (holdMillis != null && !deliveryMode.holds()) {
            throw new IllegalArgumentException("contradictory options: --hold-ms applies only to"
                    + " the HOLD delivery modes (VK_HOLD, SCANCODE_HOLD); " + deliveryMode
                    + " submits one no-sleep batch with no hold");
        }
        int hold = deliveryMode.holds()
                ? (holdMillis == null ? DiagnosticDeliveryPlan.DEFAULT_HOLD_MILLIS : holdMillis)
                : 0;
        int countdown = countdownSeconds == null
                ? InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS
                : countdownSeconds;
        return new InputDeliveryProbeOptions(targetExecutable, control, deliveryMode, hold,
                abortKey, countdown, false);
    }

    /** Command-line help text, also printed for usage errors. */
    public static String usage() {
        return String.join(System.lineSeparator(),
                "Stage 8C.2 GTA input-delivery characterization probe (Windows only): it sends",
                "AT MOST ONE harmless gameplay key tap with ONE explicitly selected delivery",
                "mode, after the explicit opt-in, a countdown and strict foreground and",
                "emergency-abort gates. One invocation is one tap: there is no matrix mode, no",
                "repetition, no retry, and no solver, recognition, capture or navigation.",
                "",
                "Usage: --enable-input --target-exe <exact.exe>",
                "       --control <UP|DOWN|LEFT|RIGHT|SELECT>",
                "       --delivery-mode <VK_BATCH|VK_HOLD|SCANCODE_BATCH|SCANCODE_HOLD>",
                "       --abort-key <F1..F24|PAUSE|SCROLL_LOCK>",
                "       [--hold-ms <n>] [--countdown-seconds <n>]",
                "       --help",
                "",
                "  --enable-input           explicit opt-in: without it nothing is sent",
                "  --target-exe <exact.exe> exact foreground executable file name, for example",
                "                           GTA5_Enhanced.exe (not a path)",
                "  --control <name>         exactly one of UP, DOWN, LEFT, RIGHT, SELECT (arrow",
                "                           keys and Enter); PROCEED/Tab is forbidden in every",
                "                           mode and sends nothing",
                "  --delivery-mode <mode>   exactly one of VK_BATCH, VK_HOLD, SCANCODE_BATCH and",
                "                           SCANCODE_HOLD:",
                "                             VK_BATCH       virtual-key key-down + key-up in ONE",
                "                                            batch, no hold (the exact production",
                "                                            Stage 7B batch: reused, not rewritten)",
                "                             VK_HOLD        virtual-key key-down, hold, key-up as",
                "                                            separate native submissions",
                "                             SCANCODE_BATCH scan-code key-down + key-up in ONE",
                "                                            batch; wVk stays zero and extended",
                "                                            keys carry KEYEVENTF_EXTENDEDKEY",
                "                             SCANCODE_HOLD  scan-code key-down, hold, scan-code",
                "                                            key-up as separate submissions",
                "  --hold-ms <n>            explicit hold for the HOLD modes only, 10..200 ms,",
                "                           default 50; refused for the BATCH modes, which never",
                "                           sleep between key-down and key-up",
                "  --abort-key <name>       REQUIRED emergency abort key; supported keys are",
                "                           F1..F24, PAUSE and SCROLL_LOCK. No key is picked",
                "                           automatically: F12 (the old default) is a Steam",
                "                           screenshot shortcut. Check your own Steam and GTA",
                "                           bindings first. Documented conflicts: F1/F2/F3",
                "                           Rockstar Editor shortcuts, F9/F10 GTA drop-weapon and",
                "                           drop-ammo defaults, F11 Ctrl+F11 Steam manual",
                "                           recording, F12 Steam screenshot (Ctrl+F12 Steam Game",
                "                           Recording marker), Home Rockstar overlay. The list is",
                "                           not exhaustive; your bindings win. UP, DOWN, LEFT,",
                "                           RIGHT, ENTER and TAB can never be abort keys.",
                "  --countdown-seconds <n>  seconds to switch to GTA, 1..30, default 5",
                "  --help                   print this help",
                "",
                "The probe polls the configured abort key during the countdown, before the tap",
                "and during a HOLD-mode hold. After a key-down has been submitted the key-up is",
                "always submitted: an abort or an error during the hold releases the key early",
                "and never suppresses the key-up. Exit codes: 0 tap sent (or help), 2 usage",
                "refusal, 3 runtime refusal or input failure. Ordinary refusals happen before any",
                "input and send nothing; an INPUT ERROR follows the one attempted tap and does",
                "not confirm complete delivery. A SENT line means the native submission",
                "succeeded; whether GTA visibly reacted is determined by the human operator.",
                "");
    }

    private static GameControl parseControl(String name) {
        if (name.equalsIgnoreCase(GameControl.PROCEED.name())) {
            throw new IllegalArgumentException("UNSUPPORTED CONTROL: PROCEED is forbidden in"
                    + " Stage 8C: Tab is never sent by this probe in any delivery mode and no"
                    + " alias exposes it");
        }
        return switch (name) {
            case "UP" -> GameControl.UP;
            case "DOWN" -> GameControl.DOWN;
            case "LEFT" -> GameControl.LEFT;
            case "RIGHT" -> GameControl.RIGHT;
            case "SELECT" -> GameControl.SELECT;
            default -> throw new IllegalArgumentException("UNSUPPORTED CONTROL: unknown --control \""
                    + name + "\": allowed controls are UP, DOWN, LEFT, RIGHT and SELECT");
        };
    }

    private static int intValue(String[] args, int index, String option) {
        if (index < 0 || index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " value is not a number: " + args[index]);
        }
    }

    private static String textValue(String[] args, int index, String option) {
        if (index < 0 || index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
