package io.github.bohdankordon.casinofingerprint.input.win32;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinUser.INPUT;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import java.util.List;
import java.util.Objects;

/**
 * Production gameplay input backend: one complete key tap per {@link GameControl} through
 * the Windows {@code SendInput} API (ordinary user-style keyboard input), using the
 * manually validated SCANCODE_BATCH representation.
 *
 * <p>Mapping (the validated production contract, Stage 8C.2/8C.3): arrows for
 * UP/DOWN/LEFT/RIGHT as extended Set-1 scan codes 0x48/0x50/0x4B/0x4D, main Enter
 * 0x1C non-extended for SELECT, main Tab 0x0F non-extended for PROCEED. One {@link #tap}
 * submits key-down plus key-up as a single {@code SendInput} batch with no hold, no sleep
 * and no timing parameter; when the batch does not report both events delivered, the
 * backend makes one best-effort key-up cleanup with the same scan-code identity before
 * reporting the failure, so no error path deliberately leaves a key held down. No
 * injection, no hooks, no memory access, no drivers: keyboard events only.
 *
 * <p>Windows-only: construction refuses on any other OS, and no code path here runs
 * during Linux CI (tests cover only the pure static mapping and the pure batch
 * sequencing through a fake submission seam, which touch no native library).
 */
public final class WindowsSendInputSink implements GameInputSink {
    /** {@code KEYEVENTF_EXTENDEDKEY}: the key carries the E0 scan-code prefix. */
    public static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    /** {@code KEYEVENTF_KEYUP}: the key is being released. */
    public static final int KEYEVENTF_KEYUP = 0x0002;
    /** {@code KEYEVENTF_SCANCODE}: identify the key by scan code instead of virtual key. */
    public static final int KEYEVENTF_SCANCODE = 0x0008;

    /**
     * One pure production keyboard event: exactly the {@code KEYBDINPUT} members
     * {@code wVk}, {@code wScan} and {@code dwFlags}. Production scan-code taps always
     * carry {@code wVk} zero and {@code KEYEVENTF_SCANCODE}; arrows additionally carry
     * {@code KEYEVENTF_EXTENDEDKEY} and releases additionally carry
     * {@code KEYEVENTF_KEYUP}.
     *
     * @param wVk virtual-key code; always zero for production scan-code taps
     * @param wScan Set-1 scan code without any {@code E0} prefix
     * @param flags the {@code KEYEVENTF_} flags of the event
     */
    public record KeyStroke(int wVk, int wScan, int flags) {
        public KeyStroke {
            if (wVk != 0) {
                throw new IllegalArgumentException("production scan-code taps keep wVk zero, got " + wVk);
            }
            if (wScan < 0 || wScan > 0xFF) {
                throw new IllegalArgumentException("wScan is one byte, got " + wScan);
            }
            int unknown = flags & ~(KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP | KEYEVENTF_SCANCODE);
            if (unknown != 0) {
                throw new IllegalArgumentException("unsupported keyboard flags: 0x" + Integer.toHexString(unknown));
            }
            if ((flags & KEYEVENTF_SCANCODE) == 0) {
                throw new IllegalArgumentException("production taps are scan-code taps: KEYEVENTF_SCANCODE is required");
            }
        }

        /** True for a key release. */
        public boolean keyUp() {
            return (flags & KEYEVENTF_KEYUP) != 0;
        }

        /** True when the key carries the E0 extended prefix. */
        public boolean extended() {
            return (flags & KEYEVENTF_EXTENDEDKEY) != 0;
        }
    }

    /**
     * One native batch submission: the number of events Windows reported as inserted.
     * Production passes a two-event down/up batch, plus at most one single-event key-up
     * cleanup after a partial delivery.
     */
    @FunctionalInterface
    public interface BatchSender {
        /**
         * @param batch the ordered strokes to submit as one native batch; required, non-empty
         * @return the number of events Windows reported as inserted
         */
        int send(List<KeyStroke> batch);
    }

    private final BatchSender sender;

    /** Creates the backend; refuses immediately on a non-Windows OS. */
    public WindowsSendInputSink() {
        Win32Support.requireWindows("Gameplay input");
        this.sender = WindowsSendInputSink::sendNative;
    }

    /**
     * The production scan-code identity of one gameplay intention. Pure data, no native
     * call, so the input mapping contract stays pinned by CI on every OS.
     */
    public static WindowsGameKeySpec spec(GameControl control) {
        Objects.requireNonNull(control, "control");
        return WindowsGameKeySpec.forControl(control);
    }

    /**
     * The production key-down stroke of one gameplay intention. Pure data, no native call.
     */
    public static KeyStroke downStroke(GameControl control) {
        WindowsGameKeySpec identity = spec(control);
        int flags = KEYEVENTF_SCANCODE | (identity.extended() ? KEYEVENTF_EXTENDEDKEY : 0);
        return new KeyStroke(0, identity.scanCode(), flags);
    }

    /**
     * The production key-up stroke of one gameplay intention: the same scan/extended
     * identity plus {@code KEYEVENTF_KEYUP}. Pure data, no native call.
     */
    public static KeyStroke upStroke(GameControl control) {
        WindowsGameKeySpec identity = spec(control);
        int flags = KEYEVENTF_SCANCODE | (identity.extended() ? KEYEVENTF_EXTENDEDKEY : 0) | KEYEVENTF_KEYUP;
        return new KeyStroke(0, identity.scanCode(), flags);
    }

    /**
     * The exact two-event production batch of one tap: key-down followed by key-up.
     * Pure data, no native call.
     */
    public static List<KeyStroke> describeTap(GameControl control) {
        Objects.requireNonNull(control, "control");
        return List.of(downStroke(control), upStroke(control));
    }

    @Override
    public void tap(GameControl control) {
        Objects.requireNonNull(control, "control");
        performTap(control, sender);
    }

    /**
     * Pure tap sequencing over an injected batch sender: exactly one two-event batch,
     * no hold, no sleep, no retry. A batch that does not fully deliver takes one
     * best-effort key-up cleanup with the same scan-code identity; the key-down is never
     * retried and no second logical tap is ever sent. A native submission failure takes
     * the same single cleanup path before the truthful failure is reported.
     *
     * @param control gameplay intention; required
     * @param sender native batch seam; required
     * @throws GameInputException when the tap cannot be delivered as one complete tap
     */
    public static void performTap(GameControl control, BatchSender sender) {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(sender, "sender");
        List<KeyStroke> batch = describeTap(control);
        int delivered;
        try {
            delivered = sender.send(batch);
        } catch (GameInputException e) {
            boolean released = releaseBestEffort(control, sender);
            throw new GameInputException(e.getMessage() + "; the key-up was still attempted ("
                    + releaseWording(released) + "), nothing is retried and no second tap is attempted", e);
        } catch (RuntimeException e) {
            boolean released = releaseBestEffort(control, sender);
            throw new GameInputException("SendInput call failed for " + control + "; the key-up was still attempted ("
                    + releaseWording(released) + "), nothing is retried and no second tap is attempted", e);
        }
        if (delivered != batch.size()) {
            boolean released = releaseBestEffort(control, sender);
            throw new GameInputException("SendInput delivered " + delivered + " of " + batch.size()
                    + " events for " + control + ": complete tap not confirmed; best-effort key-up attempted ("
                    + releaseWording(released) + ")");
        }
    }

    /** Best-effort key-up when the down/up batch did not fully deliver. */
    private static boolean releaseBestEffort(GameControl control, BatchSender sender) {
        try {
            return sender.send(List.of(upStroke(control))) == 1;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String releaseWording(boolean released) {
        return released ? "key-up confirmed by cleanup" : "cleanup could not confirm the key-up, so the key may still be held down";
    }

    /**
     * The single native input site of the production backend: one {@code SendInput} call
     * per submission, exactly the validated batch shape.
     *
     * @return the number of events Windows reported as inserted
     */
    private static int sendNative(List<KeyStroke> batch) {
        INPUT[] inputs = (INPUT[]) new INPUT().toArray(batch.size());
        for (int index = 0; index < batch.size(); index++) {
            KeyStroke stroke = batch.get(index);
            inputs[index].type = new DWORD(INPUT.INPUT_KEYBOARD);
            inputs[index].input.setType("ki");
            inputs[index].input.ki.wVk = new WORD(stroke.wVk());
            inputs[index].input.ki.wScan = new WORD(stroke.wScan());
            inputs[index].input.ki.dwFlags = new DWORD(stroke.flags());
            inputs[index].input.ki.time = new DWORD(0);
        }
        try {
            return User32.INSTANCE.SendInput(new DWORD(inputs.length), inputs, inputs[0].size()).intValue();
        } catch (RuntimeException e) {
            throw new GameInputException("SendInput call failed for the production keystrokes", e);
        }
    }
}
