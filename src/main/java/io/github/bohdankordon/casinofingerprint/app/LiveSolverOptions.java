package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;

/**
 * Parsed command-line options of the guarded live solver: recognition plus verified gameplay
 * input on Windows only.
 *
 * <p>Live input stays strictly opt-in: without {@code --enable-input} (and without an explicit
 * {@code --target-exe} plus an explicit {@code --abort-key}) the live mode refuses to start.
 * There is no default abort key: F12 proved to be a Steam screenshot shortcut in the real
 * Stage 8C run, so the user must name an unbound key after checking the Steam and GTA
 * bindings. Parsing is strict and pure like the dry-run options: unknown options, missing
 * values, contradictory modes and out-of-range counts fail with an
 * {@link IllegalArgumentException} before anything is captured.
 */
public record LiveSolverOptions(Integer monitorIndex, Mode mode, long intervalMillis,
        int stableFrames, boolean inputEnabled, String targetExecutable, EmergencyAbortKey abortKey,
        boolean experimental1080p, boolean help) {

    /** What the live solver should do with the desktop. */
    public enum Mode {
        /** Enumerate monitors and exit. */
        LIST_MONITORS,
        /** Capture, plan and send guarded input until interrupted; Windows only. */
        WATCH,
        /** Print help and exit. */
        HELP
    }

    /** Conservative watch interval between recognition frames. */
    public static final long DEFAULT_INTERVAL_MILLIS = 200;
    /** Upper bound keeps a mis-typed interval from looking like a hang. */
    public static final long MAX_INTERVAL_MILLIS = 60_000;
    /** Conservative default stability requirement. */
    public static final int DEFAULT_STABLE_FRAMES = 3;
    /** Upper bound keeps a mis-typed count from being unsatisfiable in practice. */
    public static final int MAX_STABLE_FRAMES = 100;

    /** @param monitorIndex optional capture monitor override */
    /** @param abortKey explicitly configured emergency abort key; required for live input, null otherwise */
    public LiveSolverOptions {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (!help) {
            if (intervalMillis < 1 || intervalMillis > MAX_INTERVAL_MILLIS) {
                throw new IllegalArgumentException("--interval-ms must be between 1 and "
                        + MAX_INTERVAL_MILLIS + ", got " + intervalMillis);
            }
            if (stableFrames < 1 || stableFrames > MAX_STABLE_FRAMES) {
                throw new IllegalArgumentException("--stable-frames must be between 1 and "
                        + MAX_STABLE_FRAMES + ", got " + stableFrames);
            }
            if (monitorIndex != null && monitorIndex < 0) {
                throw new IllegalArgumentException(
                        "--monitor must be a non-negative index, got " + monitorIndex);
            }
            if (inputEnabled
                    && (targetExecutable == null || targetExecutable.isBlank())) {
                throw new IllegalArgumentException(
                        "--enable-input requires an explicit --target-exe <name>");
            }
            if (!inputEnabled && targetExecutable != null) {
                throw new IllegalArgumentException(
                        "--target-exe needs --enable-input: live input stays strictly opt-in");
            }
            if (inputEnabled && abortKey == null) {
                throw new IllegalArgumentException("--abort-key <" + EmergencyAbortKey.SYMBOLS
                        + "> is required with --enable-input: the live solver never picks an emergency key"
                        + " automatically, because F12 (the old default) is a Steam screenshot shortcut; check"
                        + " your Steam and GTA bindings and name the unbound key you chose");
            }
            if (!inputEnabled && abortKey != null) {
                throw new IllegalArgumentException(
                        "--abort-key needs --enable-input: live input stays strictly opt-in");
            }
        }
    }

    /**
     * Parses {@code args}.
     *
     * @throws IllegalArgumentException for unknown options, missing values, malformed numbers,
     *         contradictory modes and out-of-range values
     */
    public static LiveSolverOptions parse(String[] args) {
        boolean listMonitors = false;
        boolean watch = false;
        boolean help = false;
        boolean inputEnabled = false;
        boolean experimental1080p = false;
        String targetExecutable = null;
        String abortKeyName = null;
        Integer monitorIndex = null;
        long intervalMillis = DEFAULT_INTERVAL_MILLIS;
        int stableFrames = DEFAULT_STABLE_FRAMES;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--list-monitors" -> listMonitors = true;
                case "--watch" -> watch = true;
                case "--enable-input" -> inputEnabled = true;
                case "--enable-experimental-1080p" -> experimental1080p = true;
                case "--help", "-h" -> help = true;
                case "--monitor" -> monitorIndex = intValue(args, ++index, argument);
                case "--interval-ms" -> intervalMillis = longValue(args, ++index, argument);
                case "--stable-frames" -> stableFrames = intValue(args, ++index, argument);
                case "--target-exe" -> targetExecutable = textValue(args, ++index, argument);
                case "--abort-key" -> {
                    if (abortKeyName != null) {
                        throw new IllegalArgumentException("duplicate option --abort-key");
                    }
                    abortKeyName = textValue(args, ++index, argument);
                }
                default -> throw new IllegalArgumentException("unknown option " + argument);
            }
        }
        if (help) {
            return new LiveSolverOptions(null, Mode.HELP, DEFAULT_INTERVAL_MILLIS,
                    DEFAULT_STABLE_FRAMES, false, null, null, false, true);
        }
        int modes = (listMonitors ? 1 : 0) + (watch ? 1 : 0);
        if (modes == 0) {
            throw new IllegalArgumentException("nothing to do: choose --list-monitors or --watch");
        }
        if (modes > 1) {
            throw new IllegalArgumentException("choose exactly one of --list-monitors and --watch");
        }
        Mode mode = listMonitors ? Mode.LIST_MONITORS : Mode.WATCH;
        if (mode != Mode.WATCH && (intervalMillis != DEFAULT_INTERVAL_MILLIS
                || stableFrames != DEFAULT_STABLE_FRAMES || inputEnabled
                || targetExecutable != null || abortKeyName != null)) {
            throw new IllegalArgumentException(
                    "--watch options are only valid with --watch");
        }
        if (mode == Mode.WATCH && !inputEnabled) {
            throw new IllegalArgumentException(
                    "live input requires the explicit opt-in flag --enable-input "
                            + "plus --target-exe <name>: refusing to start");
        }
        EmergencyAbortKey abortKey = null;
        if (abortKeyName != null) {
            abortKey = EmergencyAbortKey.parse(abortKeyName);
        }
        if (mode == Mode.WATCH && inputEnabled && abortKey == null) {
            throw new IllegalArgumentException("--abort-key <" + EmergencyAbortKey.SYMBOLS
                    + "> is required with --enable-input: the live solver never picks an emergency key"
                    + " automatically, because F12 (the old default) is a Steam screenshot shortcut; check"
                    + " your Steam and GTA bindings and name the unbound key you chose");
        }
        if (abortKey != null && !inputEnabled) {
            throw new IllegalArgumentException("--abort-key needs --enable-input: live input stays strictly opt-in");
        }
        return new LiveSolverOptions(monitorIndex, mode, intervalMillis, stableFrames,
                inputEnabled, targetExecutable, abortKey, experimental1080p, false);
    }

    /** Command-line help text, also printed for usage errors. */
    public static String usage() {
        String separator = System.lineSeparator();
        return "Guarded live casino fingerprint solver (Windows only): it watches the screen,"
                + separator
                + "recognizes the puzzle and sends verified keyboard input to the game."
                + separator
                + "Every input is preflighted and visually verified; any mismatch stops input."
                + separator + separator
                + "Usage: --list-monitors"
                + separator
                + "       [--monitor <index>] --watch --enable-input --target-exe <name> --abort-key <name>"
                + separator
                + "       [--interval-ms <ms>] [--stable-frames <n>] [--enable-experimental-1080p]"
                + separator + separator
                + "  --list-monitors       list monitors with logical bounds and physical display mode"
                + separator
                + "  --monitor <index>     capture this monitor; required when more than one monitor"
                + separator
                + "                        reports a physical display mode matching the layout"
                + separator
                + "  --watch               capture, plan and send guarded input until interrupted"
                + separator
                + "  --enable-input        explicit opt-in: without it live mode refuses to start"
                + separator
                + "  --enable-experimental-1080p"
                + separator
                + "                        EXPERIMENTAL native 1920x1080 profile instead of the stable"
                + separator
                + "                        2560x1440 profile. Offline real-recording evidence only;"
                + separator
                + "                        independent live-PC validation is still pending. Without"
                + separator
                + "                        this flag the stable 1440p profile is always used."
                + separator
                + "  --target-exe <name>   exact foreground executable, e.g. GTA5_Enhanced.exe (required)"
                + separator
                + "  --abort-key <name>    REQUIRED emergency abort key; supported keys are "
                + EmergencyAbortKey.SYMBOLS + ". No key is picked automatically: F12 is a Steam"
                + separator
                + "                        screenshot shortcut. Check your own Steam and GTA bindings first."
                + separator
                + "                        Documented conflicts: F1/F2/F3 Rockstar Editor shortcuts, F9/F10 GTA"
                + separator
                + "                        drop-weapon and drop-ammo defaults, F11 Ctrl+F11 Steam manual recording,"
                + separator
                + "                        F12 Steam screenshot (Ctrl+F12 Steam Game Recording marker). The list is"
                + separator
                + "                        not exhaustive; your bindings win. UP, DOWN, LEFT, RIGHT, ENTER and TAB"
                + separator
                + "                        can never be abort keys. Input delivery is SCANCODE_BATCH."
                + separator
                + "  --interval-ms <ms>    watch capture interval, default "
                + DEFAULT_INTERVAL_MILLIS
                + separator
                + "  --stable-frames <n>   required consecutive identical answers, default "
                + DEFAULT_STABLE_FRAMES + separator
                + "  --help                print this help" + separator + separator
                + "Safety: input needs the target executable in the foreground, a visually"
                + separator
                + "verified start (selector C0, nothing selected), and per-action visual"
                + separator
                + "confirmation. The configured --abort-key is the emergency abort key. Any mismatch latches the"
                + separator
                + "execution: no retry without an explicit restart." + separator;
    }

    private static int intValue(String[] args, int index, String option) {
        long value = longValue(args, ++index - 1, option);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(option + " value is out of range");
        }
        return (int) value;
    }

    private static long longValue(String[] args, int index, String option) {
        if (index < 0 || index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        try {
            return Long.parseLong(args[index]);
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
