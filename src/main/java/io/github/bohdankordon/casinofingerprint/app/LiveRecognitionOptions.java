package io.github.bohdankordon.casinofingerprint.app;

/**
 * Parsed command-line options of the recognition-only live runtime.
 *
 * <p>Parsing is deliberately strict and pure: unknown options, missing values, contradictory
 * modes and out-of-range counts fail with an {@link IllegalArgumentException} carrying a
 * user-facing message, so no AWT or OpenCV object is touched until the command line is valid.
 */
public record LiveRecognitionOptions(
        Integer monitorIndex,
        Mode mode,
        long intervalMillis,
        int stableFrames,
        boolean help) {

    /** What the runtime should do with the desktop. */
    public enum Mode {
        /** Enumerate monitors and exit. */
        LIST_MONITORS,
        /** Capture and recognize exactly one frame. */
        ONCE,
        /** Capture and recognize until interrupted, reporting consensus results. */
        WATCH,
        /** Print help and exit. */
        HELP
    }

    /** Conservative watch interval: 5 captures per second are enough for a recognition-only pass. */
    public static final long DEFAULT_INTERVAL_MILLIS = 200;
    /** Upper bound keeps a mis-typed interval from looking like a hang. */
    public static final long MAX_INTERVAL_MILLIS = 60_000;
    /** Conservative default stability requirement. */
    public static final int DEFAULT_STABLE_FRAMES = 3;
    /** Upper bound keeps a mis-typed count from being unsatisfiable in practice. */
    public static final int MAX_STABLE_FRAMES = 100;

    public LiveRecognitionOptions {
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
        }
    }

    /**
     * Parses {@code args}.
     *
     * @throws IllegalArgumentException for unknown options, missing values, malformed numbers,
     *         contradictory modes and out-of-range values
     */
    public static LiveRecognitionOptions parse(String[] args) {
        boolean listMonitors = false;
        boolean once = false;
        boolean watch = false;
        boolean help = false;
        Integer monitorIndex = null;
        long intervalMillis = DEFAULT_INTERVAL_MILLIS;
        int stableFrames = DEFAULT_STABLE_FRAMES;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--list-monitors" -> listMonitors = true;
                case "--once" -> once = true;
                case "--watch" -> watch = true;
                case "--help", "-h" -> help = true;
                case "--monitor" -> monitorIndex = intValue(args, ++i, argument);
                case "--interval-ms" -> intervalMillis = longValue(args, ++i, argument);
                case "--stable-frames" -> stableFrames = intValue(args, ++i, argument);
                default -> throw new IllegalArgumentException("unknown option " + argument);
            }
        }
        if (help) {
            return new LiveRecognitionOptions(null, Mode.HELP,
                    DEFAULT_INTERVAL_MILLIS, DEFAULT_STABLE_FRAMES, true);
        }
        int modes = (listMonitors ? 1 : 0) + (once ? 1 : 0) + (watch ? 1 : 0);
        if (modes == 0) {
            throw new IllegalArgumentException(
                    "nothing to do: choose --list-monitors, --once or --watch");
        }
        if (modes > 1) {
            throw new IllegalArgumentException(
                    "choose exactly one of --list-monitors, --once and --watch");
        }
        Mode mode = listMonitors ? Mode.LIST_MONITORS : (once ? Mode.ONCE : Mode.WATCH);
        if (mode != Mode.WATCH
                && (intervalMillis != DEFAULT_INTERVAL_MILLIS
                        || stableFrames != DEFAULT_STABLE_FRAMES)) {
            throw new IllegalArgumentException(
                    "--interval-ms and --stable-frames are only valid with --watch");
        }
        return new LiveRecognitionOptions(monitorIndex, mode,
                intervalMillis, stableFrames, false);
    }

    /** Command-line help text, also printed for usage errors. */
    public static String usage() {
        String separator = System.lineSeparator();
        return "Recognition-only live runtime: it captures the screen, recognizes the GTA casino"
                + separator
                + "fingerprint puzzle and prints the result. It never sends keyboard or mouse input."
                + separator + separator
                + "Usage: --list-monitors" + separator
                + "       [--monitor <index>] --once" + separator
                + "       [--monitor <index>] --watch [--interval-ms <ms>] [--stable-frames <n>]"
                + separator + separator
                + "  --list-monitors       list monitors with logical bounds and physical display mode"
                + separator
                + "  --monitor <index>     capture this monitor; required when more than one monitor"
                + separator
                + "                        reports a physical display mode matching the layout"
                + separator
                + "  --once                capture exactly one frame and print its decision"
                + separator
                + "  --watch               capture repeatedly; a result counts as stable after"
                + separator
                + "                        --stable-frames consecutive identical answers (default "
                + DEFAULT_STABLE_FRAMES + ")" + separator
                + "  --interval-ms <ms>    watch capture interval, default " + DEFAULT_INTERVAL_MILLIS
                + separator
                + "  --stable-frames <n>   required consecutive identical answers, default "
                + DEFAULT_STABLE_FRAMES + separator
                + "  --help                print this help" + separator;
    }

    private static int intValue(String[] args, int index, String option) {
        long value = longValue(args, index, option);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(option + " value is out of range");
        }
        return (int) value;
    }

    private static long longValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        try {
            return Long.parseLong(args[index]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    option + " needs a numeric value, got " + args[index]);
        }
    }
}
