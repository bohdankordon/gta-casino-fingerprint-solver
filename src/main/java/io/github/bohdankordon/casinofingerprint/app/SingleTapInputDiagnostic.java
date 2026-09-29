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
 * Stage 8C.1 testable core: the ordered gates of one deliberately tiny diagnostic tap.
 *
 * <p>This class is NOT the solver. It performs no recognition, no capture, no lifecycle work,
 * no navigation planning and no repetition: one {@link #run} call may submit at most ONE
 * complete key tap and then returns. It talks only to the Stage 7B input contracts
 * ({@link GameInputSink}, {@link ForegroundTargetGuard}, {@link AbortSignal}) plus a poll
 * sleeper, so every branch is testable with fakes and no test can emit OS input.
 *
 * <p>The final pre-tap order is deliberate and pinned by tests:
 * <ol>
 *   <li>require Windows;</li>
 *   <li>require the explicit input opt-in;</li>
 *   <li>refuse PROCEED (Tab is never sent in Stage 8C);</li>
 *   <li>count down while polling the F12 abort, never sending input;</li>
 *   <li>abort must be idle;</li>
 *   <li>pin the foreground target of the exact executable;</li>
 *   <li>abort must be idle;</li>
 *   <li>the pin must still own the foreground window;</li>
 *   <li>abort must be idle AGAIN immediately before the tap;</li>
 *   <li>tap exactly once and return; no retry, no second tap.</li>
 * </ol>
 * Nothing sleeps after the final gate checks.
 */
public final class SingleTapInputDiagnostic {
    /** Modest F12 poll interval used throughout the countdown. */
    public static final long ABORT_POLL_MILLIS = 100;

    private final GameInputSink sink;
    private final ForegroundTargetGuard guard;
    private final AbortSignal abort;
    private final InputDiagnosticSleeper sleeper;
    private final BooleanSupplier windows;

    /**
     * @param sink one-tap input backend; production is the Windows native tap backend, tests fake it
     * @param guard foreground-target guard; production is the Windows guard, tests fake it
     * @param abort emergency abort signal; production polls F12, tests fake it
     * @param sleeper countdown sleeper; production is {@code Thread::sleep}, tests fake it
     * @param windows OS gate; production is {@code Win32Support::isWindows}, tests fake it
     */
    public SingleTapInputDiagnostic(GameInputSink sink, ForegroundTargetGuard guard,
            AbortSignal abort, InputDiagnosticSleeper sleeper, BooleanSupplier windows) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.abort = Objects.requireNonNull(abort, "abort");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.windows = Objects.requireNonNull(windows, "windows");
    }

    /**
     * Runs the ordered gates and at most one tap.
     *
     * @param request the single explicitly requested tap
     * @return the terminal result of this invocation; input was sent exactly when
     *         {@link InputDiagnosticResult#sent()} is true
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
                    "--enable-input is required: this diagnostic never sends input by default");
        }
        // 3. only the five Stage 8C controls; PROCEED would send Tab and is forbidden.
        if (request.control() == GameControl.PROCEED) {
            return refusal(InputDiagnosticStatus.UNSUPPORTED_CONTROL,
                    "PROCEED is forbidden in Stage 8C: Tab is never sent");
        }
        // 4. the abort key must be idle before the countdown starts.
        if (abort.isActive()) {
            return refusal(InputDiagnosticStatus.ABORTED,
                    "F12 is active: no input was sent and the invocation is over");
        }
        // 5. count down while polling the abort key; never send input during the countdown.
        if (!runCountdown(request.countdownSeconds())) {
            return refusal(InputDiagnosticStatus.ABORTED,
                    "F12 became active during the countdown (or the wait was interrupted):"
                            + " no input was sent");
        }
        // 6. abort idle.
        if (abort.isActive()) {
            return refusal(InputDiagnosticStatus.ABORTED,
                    "F12 is active: no input was sent and the invocation is over");
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
                    "F12 is active: no input was sent and the invocation is over");
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
                    "F12 is active: no input was sent and the invocation is over");
        }
        // 11. exactly one tap, no retry, no second tap.
        try {
            sink.tap(request.control());
        } catch (GameInputException e) {
            return refusal(InputDiagnosticStatus.INPUT_ERROR,
                    "the tap backend failed (" + e.getMessage()
                            + "): the tap was not retried and no second tap was attempted");
        }
        return new InputDiagnosticResult(InputDiagnosticStatus.SENT,
                "exactly one " + request.control() + " tap was submitted to the pinned "
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
