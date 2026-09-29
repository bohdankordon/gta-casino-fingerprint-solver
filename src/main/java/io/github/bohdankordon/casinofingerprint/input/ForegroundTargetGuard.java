package io.github.bohdankordon.casinofingerprint.input;

import java.util.Optional;

/**
 * Guards OS keyboard input against sending to the wrong window.
 *
 * <p>Input is never sent merely because GTA was the foreground window a moment ago: the
 * execution pins the current foreground window plus its process at preflight and re-verifies
 * the pin before every gameplay input and during every visual-verification poll. Any focus
 * change latches {@code FOCUS_LOST} with no automatic retry.
 */
public interface ForegroundTargetGuard {
    /**
     * Pins the current foreground window when it belongs to the required executable.
     *
     * @param requiredExecutable exact executable file name, for example {@code GTA5.exe};
     *        compared case-insensitively; required
     * @return the pinned target, or empty when no foreground window belongs to it right now
     */
    Optional<ForegroundTarget> pin(String requiredExecutable);

    /**
     * True only when the given pin still owns the OS foreground window right now: same
     * window handle, same process id and same executable name.
     *
     * @param pinned pin from {@link #pin}; required
     */
    boolean isPinned(ForegroundTarget pinned);
}
