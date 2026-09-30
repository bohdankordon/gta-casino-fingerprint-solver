package io.github.bohdankordon.casinofingerprint.input.win32;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryOutcome;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryOutcomeSource;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.ProceedDiagnosticDelivery;
import java.util.Optional;

/**
 * Stage 8C.3 dedicated proceed backend: ordinary user-mode Windows keyboard input through
 * {@code SendInput} for exactly one PROCEED (Tab) tap using the fixed SCANCODE_BATCH
 * delivery, used only by the {@code ProceedInputProbeMain} diagnostic.
 *
 * <p>The sequencing and key-up safety live in the pure {@link ProceedDiagnosticDelivery};
 * this class is the single native site: it owns the OS gate and reports the
 * {@code SendInput} return value through the already validated diagnostic submission seam
 * ({@link WindowsDiagnosticInputSink#submitStrokes}, the same single native input site
 * Stage 8C.2 characterized). No new native input code is introduced beyond this wiring.
 *
 * <p>No window messages, no hooks, no drivers, no process injection, no memory access and no
 * anti-cheat interaction: only the Tab event a physical keyboard would also produce.
 * Windows-only: construction refuses on any other OS, and no code path here runs during
 * Linux CI.
 */
public final class WindowsProceedInputSink
        implements GameInputSink, DiagnosticDeliveryOutcomeSource {
    private final ProceedDiagnosticDelivery delivery;

    /** Creates the backend; refuses immediately on a non-Windows OS. */
    public WindowsProceedInputSink() {
        Win32Support.requireWindows("Stage 8C.3 proceed probe input");
        this.delivery = new ProceedDiagnosticDelivery(WindowsDiagnosticInputSink::submitStrokes);
    }

    @Override
    public void tap(GameControl control) {
        delivery.tap(control);
    }

    @Override
    public Optional<DiagnosticDeliveryOutcome> lastOutcome() {
        return delivery.lastOutcome();
    }
}
