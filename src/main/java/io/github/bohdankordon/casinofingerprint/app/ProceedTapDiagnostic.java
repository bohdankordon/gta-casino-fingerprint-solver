package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.AbortSignal;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTarget;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Stage 8C.3 dedicated safety core: the ordered gates of one deliberately tiny PROCEED (Tab)
 * tap. This class mirrors {@link SingleTapInputDiagnostic} gate for gate, except the control
 * check is inverted: only PROCEED is accepted, and any other control is refused before any
 * input, so this core can never emit an arrow or Enter.
 *
 * <p>This class is NOT the solver. It performs no recognition, no capture, no lifecycle work,
 * no navigation planning and no repetition: one {@link #run} call may submit at most ONE
 * complete Tab tap and then returns. It talks only to the Stage 7B input contracts
 * ({@link GameInputSink}, {@link ForegroundTargetGuard}, {@link AbortSignal}) plus a poll
 * sleeper, so every branch is testable with fakes and no test can emit OS input.
 *
 * <p>The final pre-tap order is deliberate and pinned by tests:
 *
 * <ol>
 *   <li>require Windows;</li>
 *   <li>require the explicit input opt-in;</li>
 *   <li>require PROCEED (only Tab is sent by this dedicated probe; arrows and SELECT are
 *       refused here);</li>
 *   <li>count down while polling the configured abort key, never sending input;</li>
 *   <li>abort must be idle;</li>
 *   <li>pin the foreground target of the exact executable;</li>
 *   <li>abort must be idle;</li>
 *   <li>the pin must still own the foreground window;</li>
 *   <li>abort must be idle AGAIN immediately before the tap;</li>
 *   <li>tap exactly once and return; no retry, no second tap.</li>
 * </ol>
 *
 * Nothing sleeps after the final gate checks.
 *
 * <p>A tap that throws {@link GameInputException} is reported as
 * {@link InputDiagnosticStatus#INPUT_ERROR} and is never retried. That status is NOT a claim
 * of zero input: the attempt already reached the backend, so a partially delivered native
 * batch may have delivered a real key event, and the backend best-effort Tab key-up cleanup
 * applies. Only the pre-tap refusals guarantee zero input.
 */
public final class ProceedTapDiagnostic {
    /** Modest abort poll interval used throughout the countdown. */
    public static final long ABORT_POLL_MILLIS = 100;

    private final GameInputSink sink;
    private final ForegroundTargetGuard guard;
    private final AbortSignal abort;
    private final InputDiagnosticSleeper sleeper;
    private final BooleanSupplier windows;
    private final String abortKeyLabel;

    /**
     * @param sink one-tap Tab backend; production is the Windows proceed sink, tests fake it
     * @param guard foreground-target guard; production is the Windows guard, tests fake it
     * @param abort emergency abort signal; production polls the configured abort key, tests
     *        fake it
     * @param sleeper countdown sleeper; production is {@code Thread::sleep}, tests fake it
     * @param windows OS gate; production is {@code Win32Support::isWindows}, tests fake it
     * @param abortKeyLabel symbolic name printed in ABORTED messages; required
     */
    public ProceedTapDiagnostic(GameInputSink sink, ForegroundTargetGuard guard,
            AbortSignal abort, InputDiagnosticSleeper sleeper, BooleanSupplier windows,
            String abortKeyLabel) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.abort = Objects.requireNonNull(abort, "abort");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.windows = Objects.requireNonNull(windows, "windows");
        this.abortKeyLabel = Objects.requireNonNull(abortKeyLabel, "abortKeyLabel");
    }

    /**
     * Runs the ordered gates and at most one Tab tap.
     *
     * @param request the single explicitly requested tap; only PROCEED is accepted
     * @return the terminal result of this invocation; a complete tap was confirmed exactly
     *         when {@link InputDiagnosticResult#sent()} is true, while
     *         {@link InputDiagnosticResult#inputAttempted()} additionally covers the
     *         {@link InputDiagnosticStatus#INPUT_ERROR} case where the one attempt was made
     *         but complete delivery was not confirmed
     */
    public InputDiagnosticResult run(InputDiagnosticRequest request) {
        Objects.requireNonNull(request, "request");
        // 1. require Windows: the native input layer must never be reached elsewhere.
        if (!windows.getAsBoolean()) {
            return refusal(InputDiagnosticStatus.NON_WINDOWS,
                    "gameplay input requires Windows (os.name is \""
                            + System.getProperty("os.name", "") + "\")");
        }
        // 2. require the explicit input opt-in: nothing is ever sent by default.
        if (!request.inputEnabled()) {
            return refusal(InputDiagnosticStatus.INPUT_DISABLED,
                    "--enable-input is required: this probe never sends input by default");
        }
        // 3. only PROCEED: this dedicated probe sends Tab and nothing else.
        if (request.control() != GameControl.PROCEED) {
            return refusal(InputDiagnosticStatus.UNSUPPORTED_CONTROL,
                    "only PROCEED is supported by this dedicated Tab probe: "
                            + request.control() + " is never sent here");
        }
        // 4. the abort key must be idle before the countdown starts.
        if (abort.isActive()) {
            return refusal(InputDiagnosticStatus.ABORTED,
                    abortKeyLabel + " is active: no input was sent and the invocation is over");
        }
        // 5. count down while polling the abort key; never send input during the countdown.
        if (!runCountdown(request.countdownSeconds())) {
            return refusal(InputDiagnosticStatus.ABORTED,
                    abortKeyLabel + " became active during the countdown (or the wait was"
                            + " interrupted): no input was sent");
        }
        // 6. abort idle.
        if (abort.isActive()) {
            return refusal(InputDiagnosticStatus.ABORTED,
                    abortKeyLabel + " is active: no input was sent and the invocation is over");
        }
        // 7. pin the current foreground target of the exact executable.
        Optional<ForegroundTarget> pinned = guard.pin(request.targetExecutable());
        if (pinned.isEmpty()) {
            return refusal(InputDiagnosticStatus.TARGET_NOT_FOREGROUND,
                    "no foreground window belongs to " + request.targetExecutable()
                            + ": switch to GTA before the countdown ends");
        }
        // 8. abort idle.
        if (abort.isActive()) {
            return refusal(InputDiagnosticStatus.ABORTED,
                    abortKeyLabel + " is active: no input was sent and the invocation is over");
        }
        // 9. the pin must still own the foreground window.
        if (!guard.isPinned(pinned.get())) {
            return refusal(InputDiagnosticStatus.FOCUS_LOST,
                    "the pinned " + request.targetExecutable()
                            + " window lost the foreground before the tap");
        }
        // 10. abort idle AGAIN immediately before the tap: no sleep follows these gates.
        if (abort.isActive()) {
            return refusal(InputDiagnosticStatus.ABORTED,
                    abortKeyLabel + " is active: no input was sent and the invocation is over");
        }
        // 11. exactly one Tab tap, no retry, no second tap.
        try {
            sink.tap(GameControl.PROCEED);
        } catch (GameInputException e) {
            return refusal(InputDiagnosticStatus.INPUT_ERROR,
                    "the Tab tap backend failed (" + e.getMessage()
                            + "): one Tab tap was attempted and complete delivery was not"
                            + " confirmed, so partial native input may have been delivered (the"
                            + " backend best-effort Tab key-up cleanup, if any, already ran);"
                            + " the attempt is never retried and no second tap is attempted");
        }
        return new InputDiagnosticResult(InputDiagnosticStatus.SENT,
                "exactly one PROCEED tap was submitted to the pinned "
                        + request.targetExecutable() + " target; no further input will be sent");
    }

    /**
     * Sleeps in fixed poll steps while checking the abort key after every step.
     *
     * @return false when the abort key fired or the wait was interrupted
     */
    private boolean runCountdown(int countdownSeconds) {
        long remaining = countdownSeconds * 1000L;
        while (remaining > 0) {
            long step = Math.min(ABORT_POLL_MILLIS, remaining);
            try {
                sleeper.sleepMillis(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= step;
            if (abort.isActive()) {
                return false;
            }
        }
        return true;
    }

    private static InputDiagnosticResult refusal(InputDiagnosticStatus status, String message) {
        return new InputDiagnosticResult(status, message);
    }
}
