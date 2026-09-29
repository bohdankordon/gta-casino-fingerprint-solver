package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.evaluation.externalvideo.ExternalSessionWriter;

/**
 * Parsed command-line options of the external-video validation workflow: passive,
 * input-free observation of one source video per program run.
 *
 * <p>Parsing is strict and pure: unknown options, missing values and contradictory modes fail
 * with an {@link IllegalArgumentException} before any AWT or OpenCV object is touched.
 * Gameplay input can never be enabled from this CLI: {@code --enable-input} is explicitly
 * rejected, and no input class exists behind it.
 */
public record ExternalVideoValidationOptions(Integer monitorIndex, Mode mode, String session,
        String sourceLabel, long intervalMillis, boolean help) {

    /** What the external-video validation should do with the desktop. */
    public enum Mode {
        /** Enumerate monitors and exit. */
        LIST_MONITORS,
        /** Passively capture one source video until interrupted; nothing is ever pressed. */
        WATCH,
        /** Print help and exit. */
        HELP
    }

    /** Passive watch interval: 10 captures per second follow human selections closely. */
    public static final long DEFAULT_INTERVAL_MILLIS = 100;
    /** Upper bound keeps a mis-typed interval from looking like a hang. */
    public static final long MAX_INTERVAL_MILLIS = 60_000;

    public ExternalVideoValidationOptions {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (!help) {
            if (intervalMillis < 1 || intervalMillis > MAX_INTERVAL_MILLIS) {
                throw new IllegalArgumentException("--interval-ms must be between 1 and "
                        + MAX_INTERVAL_MILLIS + ", got " + intervalMillis);
            }
            if (monitorIndex != null && monitorIndex < 0) {
                throw new IllegalArgumentException("--monitor must be a non-negative index, got "
                        + monitorIndex);
            }
            if (mode == Mode.WATCH && session == null) {
                throw new IllegalArgumentException("--watch requires --session <name>");
            }
            if (mode != Mode.WATCH && session != null) {
                throw new IllegalArgumentException("--session is only valid with --watch");
            }
            if (sourceLabel != null && mode != Mode.WATCH) {
                throw new IllegalArgumentException("--source-label is only valid with --watch");
            }
        }
    }

    /**
     * Parses {@code args}.
     *
     * @throws IllegalArgumentException for unknown options, missing values, malformed numbers,
     *         contradictory modes and out-of-range values
     */
    public static ExternalVideoValidationOptions parse(String[] args) {
        boolean listMonitors = false;
        boolean watch = false;
        boolean help = false;
        Integer monitorIndex = null;
        String session = null;
        String sourceLabel = null;
        long intervalMillis = DEFAULT_INTERVAL_MILLIS;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--list-monitors" -> listMonitors = true;
                case "--watch" -> watch = true;
                case "--help", "-h" -> help = true;
                case "--monitor" -> monitorIndex = intValue(args, ++index, argument);
                case "--session" -> {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--session needs a value");
                    }
                    session = ExternalSessionWriter.validateSessionName(args[index]);
                }
                case "--source-label" -> {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--source-label needs a value");
                    }
                    sourceLabel = args[index];
                    if (sourceLabel.isBlank()) {
                        throw new IllegalArgumentException("--source-label must not be blank");
                    }
                }
                case "--interval-ms" -> intervalMillis = longValue(args, ++index, argument);
                case "--enable-input" -> throw new IllegalArgumentException(
                        "--enable-input is not supported by the external-video validation:"
                                + " this workflow is passive and never sends input");
                default -> throw new IllegalArgumentException("unknown option " + argument);
            }
        }
        if (help) {
            return new ExternalVideoValidationOptions(null, Mode.HELP, null, null,
                    DEFAULT_INTERVAL_MILLIS, true);
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
                || sourceLabel != null)) {
            throw new IllegalArgumentException(
                    "--interval-ms and --source-label are only valid with --watch");
        }
        return new ExternalVideoValidationOptions(monitorIndex, mode, session, sourceLabel,
                intervalMillis, false);
    }

    /** Command-line help text, also printed for usage errors. */
    public static String usage() {
        String separator = System.lineSeparator();
        return "External-video casino fingerprint validation: it watches one source video"
                + separator
                + "passively and records solver predictions against the human player's"
                + separator
                + "selections. PASSIVE: it never sends keyboard or mouse input of any kind,"
                + separator
                + "and it never controls the browser or the video player."
                + separator + separator
                + "One run observes exactly one source video: start a NEW session per video."
                + separator + separator
                + "Usage: --list-monitors"
                + separator
                + "       [--monitor <index>] --watch --session <name>"
                + separator
                + "           [--source-label <text>] [--interval-ms <ms>]"
                + separator + separator
                + "  --list-monitors       list monitors with logical bounds and physical display mode"
                + separator
                + "  --monitor <index>     capture this monitor; required when more than one monitor"
                + separator
                + "                        reports a physical display mode matching the layout"
                + separator
                + "  --watch               capture repeatedly and record one validation session"
                + separator
                + "  --session <name>      session name; letters, digits, dash, underscore;"
                + separator
                + "                        a new directory target/stage8a/<name>/ is created and"
                + separator
                + "                        an existing one is never overwritten"
                + separator
                + "  --source-label <text> optional source description stored as metadata only;"
                + separator
                + "                        never fetched, never downloaded"
                + separator
                + "  --interval-ms <ms>    watch capture interval, default " + DEFAULT_INTERVAL_MILLIS
                + separator
                + "  --help                print this help"
                + separator;
    }

    private static int intValue(String[] args, int index, String option) {
        long value = longValue(args, index, option);
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
}

