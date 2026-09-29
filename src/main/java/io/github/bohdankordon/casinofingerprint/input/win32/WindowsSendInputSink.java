package io.github.bohdankordon.casinofingerprint.input.win32;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinUser.INPUT;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import java.util.Objects;

/**
 * Production gameplay input backend: one complete key tap per {@link GameControl} through the
 * Windows {@code SendInput} API (ordinary user-style keyboard input).
 *
 * <p>Mapping (the project game-control contract): arrows for UP/DOWN/LEFT/RIGHT, Enter for
 * SELECT, Tab for PROCEED. One {@link #tap} submits key-down plus key-up as a single
 * {@code SendInput} batch with no hold duration to tune; when the batch does not report both
 * events delivered, the backend makes a best-effort key-up cleanup before reporting the
 * failure, so no error path leaves a key held down. No injection, no hooks, no memory
 * access, no drivers: keyboard events only.
 *
 * <p>Windows-only: construction refuses on any other OS, and no code path here runs during
 * Linux CI (tests cover only the static mapping below, which touches no native library).
 */
public final class WindowsSendInputSink implements GameInputSink {
    // Stable Win32 virtual-key codes (winuser.h), hardcoded so the mapping contract stays
    // pinned without depending on where JNA nests its key-code tables.
    static final int VK_LEFT = 0x25;
    static final int VK_UP = 0x26;
    static final int VK_RIGHT = 0x27;
    static final int VK_DOWN = 0x28;
    static final int VK_TAB = 0x09;
    static final int VK_RETURN = 0x0D;
    static final int VK_F12 = 0x7B;
    static final int KEYEVENTF_KEYUP = 0x0002;

    /** Creates the backend; refuses immediately on a non-Windows OS. */
    public WindowsSendInputSink() {
        Win32Support.requireWindows("Gameplay input");
    }

    /**
     * Virtual-key code of one gameplay intention. Pure data, no native call, so the input
     * mapping contract stays pinned by CI on every OS.
     */
    public static int virtualKey(GameControl control) {
        Objects.requireNonNull(control, "control");
        return switch (control) {
            case UP -> VK_UP;
            case DOWN -> VK_DOWN;
            case LEFT -> VK_LEFT;
            case RIGHT -> VK_RIGHT;
            case SELECT -> VK_RETURN;
            case PROCEED -> VK_TAB;
        };
    }

    @Override
    public void tap(GameControl control) {
        Objects.requireNonNull(control, "control");
        int code = virtualKey(control);
        INPUT[] batch = (INPUT[]) new INPUT().toArray(2);
        fill(batch[0], code, 0);
        fill(batch[1], code, KEYEVENTF_KEYUP);
        int delivered;
        try {
            delivered = User32.INSTANCE.SendInput(new DWORD(batch.length), batch,
                    batch[0].size()).intValue();
        } catch (RuntimeException e) {
            throw new GameInputException("SendInput call failed for " + control, e);
        }
        if (delivered != batch.length) {
            releaseKey(code);
            throw new GameInputException("SendInput delivered " + delivered + " of "
                    + batch.length + " events for " + control + ": no complete tap was sent");
        }
    }

    private static void fill(INPUT slot, int code, int flags) {
        slot.type = new DWORD(INPUT.INPUT_KEYBOARD);
        slot.input.setType("ki");
        slot.input.ki.wVk = new WORD(code);
        slot.input.ki.wScan = new WORD(0);
        slot.input.ki.dwFlags = new DWORD(flags);
        slot.input.ki.time = new DWORD(0);
    }

    /** Best-effort key-up when the down+up batch did not fully deliver. */
    private static void releaseKey(int code) {
        try {
            INPUT[] single = (INPUT[]) new INPUT().toArray(1);
            fill(single[0], code, KEYEVENTF_KEYUP);
            User32.INSTANCE.SendInput(new DWORD(single.length), single, single[0].size());
        } catch (RuntimeException ignored) {
            // The original delivery failure is already being reported; cleanup is best-effort.
        }
    }
}
