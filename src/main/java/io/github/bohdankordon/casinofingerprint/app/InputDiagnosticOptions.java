package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.GameControl;

/**
 * Parsed command-line options of the Stage 8C.1 single-tap input diagnostic.
 *
 * <p>An instance exists only after the explicit {@code --enable-input} opt-in plus an exact
 * {@code --target-exe} and exactly one allowed control were given: without the opt-in, with a
 * missing value, with an unknown option, with a malformed countdown or with any spelling of
 * PROCEED, parsing throws and the CLI sends zero input. Parsing is pure: no native object and
 * no input backend is touched here.
 *
 * <p>Allowed controls are the five Stage 8C controls {@code UP}, {@code DOWN}, {@code LEFT},
 * {@code RIGHT} and {@code SELECT}. There is deliberately no alias that could expose
 * {@link GameControl#PROCEED} (Tab).
 *
 * @param targetExecutable exact foreground executable file name, for example {@code GTA5.exe}
 * @param control the single allowed control to tap; null only for {@code --help}
 * @param countdownSeconds seconds to switch to GTA before the foreground gates
 * @param help true when the invocation only asks for help
 */
public record InputDiagnosticOptions(String targetExecutable, GameControl control,
        int countdownSeconds, boolean help) {

    public InputDiagnosticOptions {
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
                        "UNSUPPORTED CONTROL: PROCEED is forbidden in Stage 8C:"
                                + " Tab is never sent by this diagnostic");
            }
            if (countdownSeconds < InputDiagnosticRequest.MIN_COUNTDOWN_SECONDS
                    || countdownSeconds > InputDiagnosticRequest.MAX_COUNTDOWN_SECONDS) {
                throw new IllegalArgumentException("--countdown-seconds must be between "
                        + InputDiagnosticRequest.MIN_COUNTDOWN_SECONDS + " and "
                        + InputDiagnosticRequest.MAX_COUNTDOWN_SECONDS + ", got "
                        + countdownSeconds);
            }
        }
    }

    /**
     * Parses {@code args}.
     *
     * @throws IllegalArgumentException for unknown options, duplicate options, missing values,
     *         malformed or out-of-range numbers, a missing opt-in and any control that is not
     *         one of the five allowed Stage 8C controls
     */
    public static InputDiagnosticOptions parse(String[] args) {
        boolean enableInput = false;
        boolean help = false;
        String targetExecutable = null;
        String controlName = null;
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
            return new InputDiagnosticOptions(null, null,
                    InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS, true);
        }
        if (!enableInput) {
            throw new IllegalArgumentException("INPUT DISABLED: --enable-input is required;"
                    + " this diagnostic sends at most one tap and never sends input by default"
                    + " (see --help)");
        }
        if (targetExecutable == null) {
            throw new IllegalArgumentException("--target-exe <exact executable name> is required");
        }
        if (controlName == null) {
            throw new IllegalArgumentException("--control <UP|DOWN|LEFT|RIGHT|SELECT> is required");
        }
        GameControl control = parseControl(controlName);
        int countdown = countdownSeconds == null
                ? InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS
                : countdownSeconds;
        return new InputDiagnosticOptions(targetExecutable, control, countdown, false);
    }

    /** Command-line help text, also printed for usage errors. */
    public static String usage() {
        String separator = System.lineSeparator();
        return "Guarded single-tap input diagnostic (Stage 8C.1, Windows only): it sends AT MOST"
                + separator
                + "ONE harmless gameplay key tap to GTA, after a countdown and after strict"
                + separator
                + "foreground and emergency-abort gates. It performs no recognition, no"
                + separator
                + "navigation, no lifecycle work and no repetition: one invocation is one tap."
                + separator + separator
                + "Usage: --enable-input --target-exe <exact.exe>"
                + separator
                + "       --control <UP|DOWN|LEFT|RIGHT|SELECT> [--countdown-seconds <n>]"
                + separator
                + "       --help"
                + separator + separator
                + "  --enable-input                  explicit opt-in: without it nothing is sent"
                + separator
                + "  --target-exe <exact.exe>        exact foreground executable file name,"
                + separator
                + "                                  e.g. GTA5.exe (not a path)"
                + separator
                + "  --control <name>                exactly one of UP, DOWN, LEFT, RIGHT, SELECT"
                + separator
                + "                                  (arrow keys and Enter); PROCEED/Tab is"
                + separator
                + "                                  forbidden in Stage 8C and sends nothing"
                + separator
                + "  --countdown-seconds <n>         seconds to switch to GTA, "
                + InputDiagnosticRequest.MIN_COUNTDOWN_SECONDS + ".."
                + InputDiagnosticRequest.MAX_COUNTDOWN_SECONDS + ", default "
                + InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS
                + separator
                + "  --help                          print this help"
                + separator + separator
                + "F12 aborts during the countdown and immediately before the tap; after an"
                + separator
                + "abort the invocation ends and nothing is retried. Exit codes: 0 tap sent"
                + separator
                + "(or help), 2 usage refusal, 3 runtime refusal or input failure. Ordinary"
                + separator
                + "refusals happen before any input and send nothing; an INPUT ERROR follows the"
                + separator
                + "one attempted tap and does not confirm complete delivery."
                + separator;
    }

    private static GameControl parseControl(String name) {
        if (name.equalsIgnoreCase(GameControl.PROCEED.name())) {
            throw new IllegalArgumentException("UNSUPPORTED CONTROL: PROCEED is forbidden in"
                    + " Stage 8C: Tab is never sent by this diagnostic and no alias exposes it");
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
