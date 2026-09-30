package io.github.bohdankordon.casinofingerprint.input.win32;

import com.sun.jna.platform.win32.User32;
import io.github.bohdankordon.casinofingerprint.input.AbortSignal;

/**
 * Production emergency abort: the high bit of {@code GetAsyncKeyState} for one explicitly
 * configured virtual key, polled (never hooked). There is no default: the live CLI
 * requires an explicit {@code --abort-key} (see {@code EmergencyAbortKey}) because the old
 * F12 default proved to be a Steam screenshot shortcut in the real Stage 8C run.
 * Read-only key-state polling only.
 *
 * <p>Windows-only: construction refuses on any other OS, and no code path here runs during
 * Linux CI (tests use a fake signal).
 */
public final class WindowsEmergencyAbort implements AbortSignal {
    private final int virtualKey;

    /**
     * @param virtualKey virtual-key code to poll, from {@code EmergencyAbortKey#virtualKeyCode()}; required, no default
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
