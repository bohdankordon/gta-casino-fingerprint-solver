package io.github.bohdankordon.casinofingerprint.input;

import java.util.Objects;

/**
 * The pinned OS foreground target of one live execution: window handle plus process identity.
 *
 * <p>Both the window handle and the process id are pinned at execution preflight and
 * re-checked before every gameplay input and during every verification poll: the same
 * executable reopened in a new window (different handle) or a handle recycled for another
 * process (different pid or executable name) both stop input immediately.
 *
 * @param windowHandle native window-handle value of the pinned foreground window
 * @param processId OS process id owning the pinned window
 * @param executableName file name of the owning executable, for example {@code GTA5.exe}
 */
public record ForegroundTarget(long windowHandle, int processId, String executableName) {
    public ForegroundTarget {
        Objects.requireNonNull(executableName, "executableName");
        if (executableName.isBlank()) {
            throw new IllegalArgumentException("executableName must not be blank");
        }
    }
}
