package io.github.bohdankordon.casinofingerprint.input.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTarget;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTargetGuard;
import java.util.Objects;
import java.util.Optional;

/**
 * Production foreground-target guard on the Windows desktop.
 *
 * <p>Reads the current foreground window ({@code GetForegroundWindow}), its process id
 * ({@code GetWindowThreadProcessId}) and the owning executable file name
 * ({@code QueryFullProcessImageName} with {@code PROCESS_QUERY_LIMITED_INFORMATION}: read-only
 * process metadata, no injection and no memory access). {@link #pin} succeeds only when the
 * foreground executable equals the explicitly configured target (for example
 * {@code GTA5.exe}); {@link #isPinned} re-checks handle, process id and executable name, so a
 * focus change of any kind stops input immediately.
 *
 * <p>Windows-only: construction refuses on any other OS, and no code path here runs during
 * Linux CI (tests use a fake guard).
 */
public final class WindowsForegroundTargetGuard implements ForegroundTargetGuard {
    /** Process access for read-only executable-name queries. */
    static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    /** Creates the guard; refuses immediately on a non-Windows OS. */
    public WindowsForegroundTargetGuard() {
        Win32Support.requireWindows("Foreground target guard");
    }

    @Override
    public Optional<ForegroundTarget> pin(String requiredExecutable) {
        Objects.requireNonNull(requiredExecutable, "requiredExecutable");
        HWND window = User32.INSTANCE.GetForegroundWindow();
        if (window == null) {
            return Optional.empty();
        }
        int processId = processIdOf(window);
        String executable = executableNameOf(processId);
        if (executable == null || !executable.equalsIgnoreCase(requiredExecutable)) {
            return Optional.empty();
        }
        return Optional.of(new ForegroundTarget(Pointer.nativeValue(window.getPointer()),
                processId, executable));
    }

    @Override
    public boolean isPinned(ForegroundTarget pinned) {
        Objects.requireNonNull(pinned, "pinned");
        HWND window = User32.INSTANCE.GetForegroundWindow();
        if (window == null
                || Pointer.nativeValue(window.getPointer()) != pinned.windowHandle()) {
            return false;
        }
        int processId = processIdOf(window);
        if (processId != pinned.processId()) {
            return false;
        }
        String executable = executableNameOf(processId);
        return executable != null && executable.equalsIgnoreCase(pinned.executableName());
    }

    private static int processIdOf(HWND window) {
        IntByReference processId = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(window, processId);
        return processId.getValue();
    }

    private static String executableNameOf(int processId) {
        WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION, false, processId);
        if (process == null || WinBase.INVALID_HANDLE_VALUE.equals(process)) {
            return null;
        }
        try {
            char[] buffer = new char[1024];
            IntByReference size = new IntByReference(buffer.length);
            boolean ok = Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, buffer, size);
            if (!ok) {
                return null;
            }
            String fullPath = new String(buffer, 0, size.getValue());
            int slash = Math.max(fullPath.lastIndexOf((char) 92), fullPath.lastIndexOf('/'));
            return slash >= 0 ? fullPath.substring(slash + 1) : fullPath;
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }
}
