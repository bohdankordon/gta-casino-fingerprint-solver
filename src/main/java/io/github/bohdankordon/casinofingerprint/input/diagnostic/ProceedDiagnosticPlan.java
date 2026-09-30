package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import java.util.Objects;

/**
 * One complete, pure delivery plan for the dedicated Stage 8C.3 proceed probe: exactly one
 * PROCEED (Tab) tap using SCANCODE_BATCH, and nothing else.
 *
 * <p>This plan is deliberately separate from {@link DiagnosticDeliveryPlan#forTap} so the
 * general plan factory keeps refusing PROCEED. There is no control to choose, no mode to
 * choose and no hold to choose: the CLI is permanently {@code control = PROCEED},
 * {@code delivery = SCANCODE_BATCH}, {@code hold = 0}. The Tab mapping comes only from the
 * dedicated {@link ScanCodeSpec#tabForProceedProbe()} (Set-1 {@code 0x0F}, non-extended),
 * never from the general {@link ScanCodeSpec#forControl} mapping, which still throws for
 * PROCEED.
 *
 * <p>Nothing here sleeps, submits or touches Windows; the plan is the testable description
 * the dedicated delivery executes. BATCH semantics: both strokes submit in ONE native call
 * with no hold and no sleep.
 *
 * @param mode the delivery shape; always SCANCODE_BATCH for this probe
 * @param down the Tab key-down stroke; required, not a release
 * @param up the Tab key-up stroke; required, a release
 * @param holdMillis the hold between key-down and key-up; always zero for this probe
 */
public record ProceedDiagnosticPlan(DiagnosticInputDeliveryMode mode, DiagnosticKeyStroke down,
        DiagnosticKeyStroke up, long holdMillis) {
    public ProceedDiagnosticPlan {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(down, "down");
        Objects.requireNonNull(up, "up");
        if (mode != DiagnosticInputDeliveryMode.SCANCODE_BATCH) {
            throw new IllegalArgumentException(
                    "the proceed probe uses SCANCODE_BATCH only, got " + mode);
        }
        if (down.keyUp()) {
            throw new IllegalArgumentException("the down stroke must be a key-down");
        }
        if (!up.keyUp()) {
            throw new IllegalArgumentException("the up stroke must be a key-up");
        }
        if (holdMillis != 0) {
            throw new IllegalArgumentException("the proceed probe submits one no-sleep batch:"
                    + " a hold of " + holdMillis + " ms is contradictory");
        }
    }

    /** True: the proceed tap is always ONE native batch of both strokes with no hold. */
    public boolean batched() {
        return true;
    }

    /**
     * Builds the single fixed plan of the dedicated proceed probe: Tab down plus Tab up as a
     * SCANCODE_BATCH with no hold.
     *
     * @return the only plan this probe may execute
     */
    public static ProceedDiagnosticPlan proceedProbe() {
        ScanCodeSpec tab = ScanCodeSpec.tabForProceedProbe();
        return new ProceedDiagnosticPlan(DiagnosticInputDeliveryMode.SCANCODE_BATCH,
                DiagnosticKeyStroke.scanCode(tab, false),
                DiagnosticKeyStroke.scanCode(tab, true), 0);
    }
}
