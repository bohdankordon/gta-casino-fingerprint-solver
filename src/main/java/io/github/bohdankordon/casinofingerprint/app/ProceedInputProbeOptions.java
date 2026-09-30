package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticAbortKey;

/**
 * Parsed command-line options of the dedicated Stage 8C.3 proceed probe.
 *
 * <p>An instance exists only after BOTH explicit opt-ins plus an exact
 * {@code --target-exe}, plus an explicitly named abort key: {@code --enable-input} and the
 * additional {@code --enable-proceed-test} acknowledgement. Parsing is pure: no native
 * object and no input backend is touched here.
 *
 * <p>The CLI is permanently {@code control = PROCEED}, {@code delivery = SCANCODE_BATCH},
 * {@code hold = 0}. There is deliberately no {@code --control}, {@code --delivery-mode} or
 * {@code --hold-ms} option: those spellings are unknown options and refuse before any
 * backend exists. This keeps the surface tiny and prevents any broadening of the general
 * probe, which must continue to refuse PROCEED.
 *
 * @param targetExecutable exact foreground executable file name, for example
 *        {@code GTA5_Enhanced.exe}
 * @param abortKey the explicitly chosen emergency abort key; null only for {@code --help}
 * @param countdownSeconds seconds to switch to GTA before the foreground gates
 * @param help true when the invocation only asks for help
 */
public record ProceedInputProbeOptions(String targetExecutable, DiagnosticAbortKey abortKey,
        int countdownSeconds, boolean help) {

    public ProceedInputProbeOptions {
        if (!help) {
            if (targetExecutable == null || targetExecutable.isBlank()) {
                throw new IllegalArgumentException(
                        "--target-exe <exact executable name> is required");
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
        }
    }

    /**
     * Parses {@code args}.
     *
     * @throws IllegalArgumentException for unknown options (including any spelling of
     *         {@code --control}, {@code --delivery-mode} or {@code --hold-ms}, which this
     *         probe never accepts), duplicate options, missing values, malformed or
     *         out-of-range numbers, a missing input opt-in, a missing proceed-test
     *         acknowledgement, a missing target and a missing abort key
     */
    public static ProceedInputProbeOptions parse(String[] args) {
        boolean enableInput = false;
        boolean enableProceedTest = false;
        boolean help = false;
        String targetExecutable = null;
        String abortKeyName = null;
        Integer countdownSeconds = null;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--enable-input" -> enableInput = true;
                case "--enable-proceed-test" -> enableProceedTest = true;
                case "--help", "-h" -> help = true;
                case "--target-exe" -> {
                    if (targetExecutable != null) {
                        throw new IllegalArgumentException("duplicate option --target-exe");
                    }
                    targetExecutable = textValue(args, ++index, argument);
                }
                case "--abort-key" -> {
                    if (abortKeyName != null) {
                        throw new IllegalArgumentException("duplicate option --abort-key");
                    }
                    abortKeyName = textValue(args, ++index, argument);
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
            return new ProceedInputProbeOptions(null, null,
                    InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS, true);
        }
        if (!enableInput) {
            throw new IllegalArgumentException("INPUT DISABLED: --enable-input is required;"
                    + " this probe sends at most one Tab tap and never sends input by default"
                    + " (see --help)");
        }
        if (!enableProceedTest) {
            throw new IllegalArgumentException("INPUT DISABLED: --enable-proceed-test is required;"
                    + " the dedicated Tab probe never sends input without the explicit"
                    + " proceed-test acknowledgement (see --help)");
        }
        if (targetExecutable == null) {
            throw new IllegalArgumentException("--target-exe <exact executable name> is required");
        }
        if (abortKeyName == null) {
            throw new IllegalArgumentException("--abort-key <" + DiagnosticAbortKey.SYMBOLS
                    + "> is required: this probe never picks an emergency key automatically,"
                    + " because F12 (the old default) is a Steam screenshot shortcut; check your"
                    + " Steam and GTA bindings and name the unbound key you chose");
        }
        DiagnosticAbortKey abortKey = DiagnosticAbortKey.parse(abortKeyName);
        int countdown = countdownSeconds == null
                ? InputDiagnosticRequest.DEFAULT_COUNTDOWN_SECONDS
                : countdownSeconds;
        return new ProceedInputProbeOptions(targetExecutable, abortKey, countdown, false);
    }

    /** Command-line help text, also printed for usage errors. */
    public static String usage() {
        return String.join(System.lineSeparator(),
                "Stage 8C.3 dedicated GTA proceed (Tab) scan-code probe (Windows only): it sends",
                "AT MOST ONE Tab tap with the fixed SCANCODE_BATCH delivery, after both explicit",
                "opt-ins, a countdown and strict foreground and emergency-abort gates. One",
                "invocation is one Tab: there is no mode choice, no hold choice, no control",
                "choice, no repetition, no retry, and no solver, recognition, capture or",
                "navigation. The normal InputDeliveryProbeMain keeps refusing PROCEED.",
                "",
                "Usage: --enable-input --enable-proceed-test --target-exe <exact.exe>",
                "       --abort-key <F1..F24|PAUSE|SCROLL_LOCK>",
                "       [--countdown-seconds <n>]",
                "       --help",
                "",
                "  --enable-input           explicit opt-in: without it nothing is sent",
                "  --enable-proceed-test    second explicit opt-in for Tab: without it nothing",
                "                           is sent; there is no default acknowledgement",
                "  --target-exe <exact.exe> exact foreground executable file name, for example",
                "                           GTA5_Enhanced.exe (not a path)",
                "  --abort-key <name>       REQUIRED emergency abort key; supported keys are",
                "                           F1..F24, PAUSE and SCROLL_LOCK. No key is picked",
                "                           automatically: F12 (the old default) is a Steam",
                "                           screenshot shortcut. Check your own Steam and GTA",
                "                           bindings first. UP, DOWN, LEFT, RIGHT, ENTER and TAB",
                "                           can never be abort keys.",
                "  --countdown-seconds <n>  seconds to switch to GTA, 1..30, default 5",
                "  --help                   print this help",
                "",
                "Fixed contract: control PROCEED (the main keyboard Tab key), delivery",
                "SCANCODE_BATCH (scan-code key-down + key-up in ONE batch, no hold, no sleep),",
                "Tab Set-1 make code 0x0F non-extended (wVk zero, KEYEVENTF_SCANCODE, no",
                "KEYEVENTF_EXTENDEDKEY, no VK_TAB). The probe polls the configured abort key",
                "during the countdown and before the tap. After the one allowed attempt the",
                "run ends: exit codes 0 tap sent (or help), 2 usage refusal, 3 runtime refusal",
                "or input failure. Ordinary refusals happen before any input and send nothing;",
                "an INPUT ERROR follows the one attempted Tab and does not confirm complete",
                "delivery. A SENT line means the native submission succeeded; whether GTA",
                "visibly reacted is determined by the human operator on the pause map.",
                "");
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
