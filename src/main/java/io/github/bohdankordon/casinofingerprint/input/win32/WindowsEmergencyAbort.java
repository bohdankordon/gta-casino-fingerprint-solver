package io.github.bohdankordon.casinofingerprint.input.win32;

import com.sun.jna.platform.win32.User32;
import io.github.bohdankordon.casinofingerprint.input.AbortSignal;

/**
 * Production emergency abort: the high bit of {@code GetAsyncKeyState} for one configured
 * virtual key, polled (never hooked). The default is F12; the key stays configurable because
 * F12 may overlap platform screenshot bindings. Read-only key-state polling only.
 *
 * <p>Windows-only: construction refuses on any other OS, and no code path here runs during
 * Linux CI (tests use a fake signal).
 */
public final class WindowsEmergencyAbort implements AbortSignal {
    /** Default abort key: F12 (0x7B). */
    public static final int DEFAULT_ABORT_KEY = 0x7B;

    private final int virtualKey;

    /** Abort on F12. */
    public WindowsEmergencyAbort() {
        this(DEFAULT_ABORT_KEY);
    }

    /**
     * @param virtualKey virtual-key code to poll, for example {@link #DEFAULT_ABORT_KEY}
     */
    public WindowsEmergencyAbort(int virtualKey) {
        Win32Support.requireWindows("Emergency abort");
        this.virtualKey = virtualKey;
    }

    /** Polled virtual-key code. */
    public int virtualKey() {
        return virtualKey;
    }

    @Override
    public boolean isActive() {
        return (User32.INSTANCE.GetAsyncKeyState(virtualKey) & 0x8000) != 0;
    }
}
