package io.github.bohdankordon.casinofingerprint.input.win32;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinUser.INPUT;
import io.github.bohdankordon.casinofingerprint.input.AbortSignal;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryOutcome;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryOutcomeSource;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticHoldSleeper;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticInputDelivery;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticInputDeliveryMode;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticKeyStroke;
import java.util.Optional;

/**
 * Stage 8C.2 characterization backend: ordinary user-mode Windows keyboard input through
 * {@code SendInput}, with one explicitly selected delivery mode, used only by the
 * {@code InputDeliveryProbeMain} diagnostic.
 *
 * <p>The sequencing, hold and key-up safety live in the pure
 * {@link DiagnosticInputDelivery}; this class is the single native site: it owns the OS gate,
 * translates the pure strokes into {@code KEYBDINPUT} events and reports the {@code SendInput}
 * return value. {@link DiagnosticInputDeliveryMode#VK_BATCH} never touches the translation: it
 * delegates to the unchanged production {@link WindowsSendInputSink}, so the baseline mode is
 * the production path itself and not a copy of it.
 *
 * <p>No window messages, no hooks, no drivers, no process injection, no memory access and no
 * anti-cheat interaction: only events a physical keyboard would also produce. Windows-only:
 * construction refuses on any other OS, and no code path here runs during Linux CI.
 */
public final class WindowsDiagnosticInputSink
        implements GameInputSink, DiagnosticDeliveryOutcomeSource {
    private final DiagnosticInputDelivery delivery;

    /**
     * @param mode the explicit delivery mode; required
     * @param holdMillis the explicit hold of the HOLD modes; zero for the BATCH modes
     * @param abort the emergency abort signal polled during a hold; required
     */
    public WindowsDiagnosticInputSink(DiagnosticInputDeliveryMode mode, int holdMillis,
            AbortSignal abort) {
        this(mode, holdMillis, abort, Thread::sleep);
    }

    /**
     * @param sleeper the hold's poll sleeper; production is {@code Thread::sleep}
     */
    WindowsDiagnosticInputSink(DiagnosticInputDeliveryMode mode, int holdMillis, AbortSignal abort,
            DiagnosticHoldSleeper sleeper) {
        Win32Support.requireWindows("Stage 8C.2 diagnostic input characterization");
        this.delivery = new DiagnosticInputDelivery(mode, holdMillis, new WindowsSendInputSink(),
                WindowsDiagnosticInputSink::submitStrokes, abort, sleeper);
    }

    @Override
    public void tap(GameControl control) {
        delivery.tap(control);
    }

    @Override
    public Optional<DiagnosticDeliveryOutcome> lastOutcome() {
        return delivery.lastOutcome();
    }

    /**
     * The single native input site of the characterization diagnostic: one {@code SendInput}
     * call per submission, exactly like the production batch submission.
     *
     * @return the number of events Windows reported as inserted
     */
    static int submitStrokes(DiagnosticKeyStroke... strokes) {
        INPUT[] batch = (INPUT[]) new INPUT().toArray(strokes.length);
        for (int index = 0; index < strokes.length; index++) {
            DiagnosticKeyStroke stroke = strokes[index];
            batch[index].type = new DWORD(INPUT.INPUT_KEYBOARD);
            batch[index].input.setType("ki");
            batch[index].input.ki.wVk = new WORD(stroke.wVk());
            batch[index].input.ki.wScan = new WORD(stroke.wScan());
            batch[index].input.ki.dwFlags = new DWORD(stroke.flags());
            batch[index].input.ki.time = new DWORD(0);
        }
        try {
            return User32.INSTANCE.SendInput(new DWORD(batch.length), batch, batch[0].size())
                    .intValue();
        } catch (RuntimeException e) {
            throw new GameInputException("SendInput call failed for the diagnostic keystrokes", e);
        }
    }
}
