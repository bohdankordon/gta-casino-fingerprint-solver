package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 8C.3 dedicated proceed delivery: exactly one PROCEED (Tab) tap as a SCANCODE_BATCH,
 * truthful failure semantics and key-up safety. Pure by construction: every native effect
 * goes through the injected {@link DiagnosticKeyStrokeSubmitter}, so the whole sequencing is
 * testable without a native call.
 *
 * <p>One {@link #tap} call submits AT MOST ONE Tab key-down, and it never retries it:
 *
 * <ul>
 *   <li>only {@link GameControl#PROCEED} is accepted; any other control is refused before
 *       any native submission, so this delivery can never emit an arrow or Enter;</li>
 *   <li>the Tab down plus Tab up submit in ONE native batch with no hold and no sleep, using
 *       the fixed {@link ProceedDiagnosticPlan#proceedProbe()} strokes (Set-1
 *       {@code 0x0F}, non-extended, {@code wVk} zero);</li>
 *   <li>after a batch that did not fully deliver, the Tab key-up is ALWAYS attempted once
 *       more as best-effort cleanup; the key-down is never retried and no second logical
 *       tap is ever sent;</li>
 *   <li>the last {@link DiagnosticDeliveryOutcome} is recorded after every attempt, failed or
 *       not, so the operator never has to guess what actually happened.</li>
 * </ul>
 *
 * <p>Single-threaded by design: one diagnostic invocation uses one instance on one thread.
 */
public final class ProceedDiagnosticDelivery
        implements GameInputSink, DiagnosticDeliveryOutcomeSource {
    /** Key-up cleanup attempts after a failed batch: one additional release. */
    static final int MAX_RELEASE_ATTEMPTS = 1;

    private final DiagnosticKeyStrokeSubmitter submitter;
    private DiagnosticDeliveryOutcome lastOutcome;

    /**
     * @param submitter the native submission seam; required
     */
    public ProceedDiagnosticDelivery(DiagnosticKeyStrokeSubmitter submitter) {
        this.submitter = Objects.requireNonNull(submitter, "submitter");
    }

    @Override
    public void tap(GameControl control) {
        Objects.requireNonNull(control, "control");
        if (control != GameControl.PROCEED) {
            throw new IllegalArgumentException("the proceed probe sends PROCEED only, refused "
                    + control + ": arrows and SELECT are never sent by this delivery");
        }
        ProceedDiagnosticPlan plan = ProceedDiagnosticPlan.proceedProbe();
        try {
            submitExpected("Tab key-down/key-up batch", 2, plan.down(), plan.up());
            publish(new DiagnosticDeliveryOutcome(
                    DiagnosticInputDeliveryMode.SCANCODE_BATCH, 0, 0, true, true, false, false));
        } catch (GameInputException e) {
            boolean released = releaseBestEffort(plan.up());
            publish(new DiagnosticDeliveryOutcome(
                    DiagnosticInputDeliveryMode.SCANCODE_BATCH, 0, 0, false, released, true,
                    false));
            throw new GameInputException(e.getMessage() + "; the Tab key-up was still attempted ("
                    + releaseWording(released) + "), nothing is retried and no second tap is"
                    + " attempted", e);
        } catch (RuntimeException e) {
            boolean released = releaseBestEffort(plan.up());
            publish(new DiagnosticDeliveryOutcome(
                    DiagnosticInputDeliveryMode.SCANCODE_BATCH, 0, 0, false, released, true,
                    false));
            throw new GameInputException("the Tab batch delivery failed unexpectedly; the Tab"
                    + " key-up was still attempted (" + releaseWording(released) + ")", e);
        }
    }

    @Override
    public Optional<DiagnosticDeliveryOutcome> lastOutcome() {
        return Optional.ofNullable(lastOutcome);
    }

    /**
     * Submits the strokes as one native batch and requires the expected number of inserted
     * events.
     *
     * @throws GameInputException when the native call failed or Windows reported fewer events
     */
    private boolean submitExpected(String what, int expected, DiagnosticKeyStroke... strokes) {
        int delivered;
        try {
            delivered = submitter.submit(strokes);
        } catch (GameInputException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GameInputException("the native " + what + " submission failed", e);
        }
        if (delivered != expected) {
            throw new GameInputException("the native " + what + " submission reported " + delivered
                    + " of " + expected + " inserted events: no complete " + what
                    + " was delivered");
        }
        return true;
    }

    /**
     * Best-effort Tab key-up cleanup after a failed batch: exactly one additional release
     * attempt, never a key-down, never a retry of the tap.
     *
     * @return true when a key-up submission was confirmed delivered
     */
    private boolean releaseBestEffort(DiagnosticKeyStroke up) {
        for (int attempt = 0; attempt < MAX_RELEASE_ATTEMPTS; attempt++) {
            try {
                if (submitter.submit(up) == 1) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // The original failure is already being reported; cleanup is best-effort.
            }
        }
        return false;
    }

    private static String releaseWording(boolean released) {
        return released ? "key-up confirmed by cleanup"
                : "cleanup could not confirm the key-up, so the key may still be held down";
    }

    private void publish(DiagnosticDeliveryOutcome outcome) {
        this.lastOutcome = outcome;
    }
}
