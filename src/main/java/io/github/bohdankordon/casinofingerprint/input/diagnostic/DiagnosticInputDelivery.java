package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import io.github.bohdankordon.casinofingerprint.input.AbortSignal;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 8C.2 characterization delivery: one explicitly requested tap, one explicitly selected
 * delivery mode, truthful failure semantics and key-up safety. Pure by construction: every
 * native effect goes through the injected {@link DiagnosticKeyStrokeSubmitter} and every timing
 * effect through the injected {@link DiagnosticHoldSleeper}, so the whole sequencing is
 * testable without a native call.
 *
 * <p>One {@link #tap} call submits AT MOST ONE key-down, and it never retries it:
 *
 * <ul>
 *   <li>{@link DiagnosticInputDeliveryMode#VK_BATCH} delegates to the production batch backend
 *       exactly as it is: the baseline is reused and never re-implemented;</li>
 *   <li>the three other modes submit the planned strokes -- either the key-down plus key-up in
 *       ONE native batch, or the key-down, an explicit hold and the key-up as separate
 *       submissions;</li>
 *   <li>after a key-down has been submitted, the key-up is ALWAYS attempted: a failed key-up, an
 *       interrupted hold sleep, an abort during the hold and an unexpected exception all take
 *       the best-effort key-up cleanup path, and none of them retries the key-down or sends a
 *       second logical tap;</li>
 *   <li>the hold polls the abort signal in short steps and releases the key early when the
 *       emergency key fires, because key-up cleanup has priority over the full hold;</li>
 *   <li>the last {@link DiagnosticDeliveryOutcome} is recorded after every attempt, failed or
 *       not, so the operator never has to guess what actually happened.</li>
 * </ul>
 *
 * <p>Single-threaded by design: one diagnostic invocation uses one instance on one thread.
 */
public final class DiagnosticInputDelivery implements GameInputSink, DiagnosticDeliveryOutcomeSource {
    /** Abort poll step during a HOLD-mode hold: short enough to release an aborted key early. */
    public static final long HOLD_POLL_MILLIS = 10;
    /** Key-up cleanup attempts after a failed hold-mode delivery: one additional release. */
    static final int MAX_RELEASE_ATTEMPTS = 1;

    private final DiagnosticInputDeliveryMode mode;
    private final int holdMillis;
    private final GameInputSink batchBaseline;
    private final DiagnosticKeyStrokeSubmitter submitter;
    private final AbortSignal abort;
    private final DiagnosticHoldSleeper sleeper;
    private DiagnosticDeliveryOutcome lastOutcome;

    /**
     * @param mode the delivery shape; required
     * @param holdMillis the explicit hold of the HOLD modes; zero for the BATCH modes
     * @param batchBaseline the production batch backend, used by VK_BATCH only; required
     * @param submitter the native submission seam; required
     * @param abort the emergency abort signal polled during a hold; required
     * @param sleeper the hold's poll sleeper; required
     */
    public DiagnosticInputDelivery(DiagnosticInputDeliveryMode mode, int holdMillis,
            GameInputSink batchBaseline, DiagnosticKeyStrokeSubmitter submitter, AbortSignal abort,
            DiagnosticHoldSleeper sleeper) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.batchBaseline = Objects.requireNonNull(batchBaseline, "batchBaseline");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
        this.abort = Objects.requireNonNull(abort, "abort");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        DiagnosticDeliveryPlan.validateHold(mode, holdMillis);
        this.holdMillis = holdMillis;
    }

    @Override
    public void tap(GameControl control) {
        Objects.requireNonNull(control, "control");
        DiagnosticDeliveryPlan plan = DiagnosticDeliveryPlan.forTap(control, mode, holdMillis);
        if (mode == DiagnosticInputDeliveryMode.VK_BATCH) {
            deliverBaseline(control);
        } else if (plan.batched()) {
            deliverBatch(plan);
        } else {
            deliverHold(plan);
        }
    }

    @Override
    public Optional<DiagnosticDeliveryOutcome> lastOutcome() {
        return Optional.ofNullable(lastOutcome);
    }

    /**
     * VK_BATCH delegates to the exact production batch backend: the baseline is never
     * re-implemented, so this mode stays the untouched control of the characterization.
     */
    private void deliverBaseline(GameControl control) {
        try {
            batchBaseline.tap(control);
        } catch (GameInputException e) {
            publish(new DiagnosticDeliveryOutcome(mode, 0, 0, false, false, false, false));
            throw e;
        }
        publish(new DiagnosticDeliveryOutcome(mode, 0, 0, true, true, false, false));
    }

    /**
     * The scan-code batch: one native submission of the key-down plus key-up, no hold and no
     * sleep. A batch that did not fully deliver takes the best-effort key-up cleanup path -- the
     * same truthful INPUT_ERROR semantics the production batch backend has.
     */
    private void deliverBatch(DiagnosticDeliveryPlan plan) {
        try {
            submitExpected("key-down/key-up batch", 2, plan.down(), plan.up());
            publish(new DiagnosticDeliveryOutcome(mode, 0, 0, true, true, false, false));
        } catch (GameInputException e) {
            boolean released = releaseBestEffort(plan.up());
            publish(new DiagnosticDeliveryOutcome(mode, 0, 0, false, released, true, false));
            throw new GameInputException(e.getMessage() + "; the key-up was still attempted ("
                    + releaseWording(released) + "), nothing is retried and no second tap is"
                    + " attempted", e);
        } catch (RuntimeException e) {
            boolean released = releaseBestEffort(plan.up());
            publish(new DiagnosticDeliveryOutcome(mode, 0, 0, false, released, true, false));
            throw new GameInputException("the batch delivery failed unexpectedly; the key-up was"
                    + " still attempted (" + releaseWording(released) + ")", e);
        }
    }

    private void deliverHold(DiagnosticDeliveryPlan plan) {
        boolean downConfirmed = false;
        long actualHoldMillis = 0;
        boolean abortedDuringHold = false;
        try {
            downConfirmed = submitExpected("key-down", 1, plan.down());
            HoldOutcome held = hold(plan.holdMillis());
            actualHoldMillis = held.actualMillis();
            abortedDuringHold = held.aborted();
            submitExpected("key-up", 1, plan.up());
            publish(new DiagnosticDeliveryOutcome(mode, plan.holdMillis(), actualHoldMillis,
                    downConfirmed, true, false, abortedDuringHold));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            boolean released = releaseBestEffort(plan.up());
            publish(new DiagnosticDeliveryOutcome(mode, plan.holdMillis(), actualHoldMillis,
                    downConfirmed, released, true, abortedDuringHold));
            throw new GameInputException("the hold was interrupted after the key-down was"
                    + " submitted; the key-up was still attempted (" + releaseWording(released)
                    + "), nothing is retried and no second tap is attempted", e);
        } catch (GameInputException e) {
            boolean released = releaseBestEffort(plan.up());
            publish(new DiagnosticDeliveryOutcome(mode, plan.holdMillis(), actualHoldMillis,
                    downConfirmed, released, true, abortedDuringHold));
            throw new GameInputException(e.getMessage() + "; the key-up was still attempted ("
                    + releaseWording(released) + "), nothing is retried and no second tap is"
                    + " attempted", e);
        } catch (RuntimeException e) {
            boolean released = releaseBestEffort(plan.up());
            publish(new DiagnosticDeliveryOutcome(mode, plan.holdMillis(), actualHoldMillis,
                    downConfirmed, released, true, abortedDuringHold));
            throw new GameInputException("the hold-mode delivery failed unexpectedly after a"
                    + " key-down submission; the key-up was still attempted ("
                    + releaseWording(released) + ")", e);
        }
    }

    /**
     * Waits for the explicit hold in poll steps, checking the abort signal after every step.
     *
     * @return the hold actually elapsed and whether the abort key cut it short
     * @throws InterruptedException when the waiting thread is interrupted
     */
    private HoldOutcome hold(long holdMillis) throws InterruptedException {
        long remaining = holdMillis;
        while (remaining > 0) {
            long step = Math.min(HOLD_POLL_MILLIS, remaining);
            sleeper.sleepMillis(step);
            remaining -= step;
            if (abort.isActive()) {
                return new HoldOutcome(holdMillis - remaining, true);
            }
        }
        return new HoldOutcome(holdMillis, false);
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
     * Best-effort key-up cleanup after a failed hold-mode delivery: exactly one additional
     * release attempt, mirroring the production backend's single best-effort release. Never a
     * key-down, never a retry of the tap.
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

    /** The hold actually performed: elapsed milliseconds and whether the abort cut it short. */
    private record HoldOutcome(long actualMillis, boolean aborted) {
    }
}
