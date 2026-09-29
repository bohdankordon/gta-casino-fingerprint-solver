package io.github.bohdankordon.casinofingerprint.input.win32;

import java.util.Locale;

/**
 * Narrow Windows-only gate for the native input layer.
 *
 * <p>Every Windows implementation in this package refuses to construct on a non-Windows OS,
 * so importing these classes on Linux CI is harmless and only an explicit live run on
 * Windows can reach the native calls. Windows APIs stay inside this package; the rest of
 * the solver talks only to the {@code input} interfaces.
 */
public final class Win32Support {
    private Win32Support() {
    }

    /** True only on Windows. */
    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * @throws IllegalStateException on any non-Windows OS
     */
    public static void requireWindows(String what) {
        if (!isWindows()) {
            throw new IllegalStateException(what + " requires Windows, but os.name is \""
                    + System.getProperty("os.name", "") + "\": refusing to emit input");
        }
    }
}
