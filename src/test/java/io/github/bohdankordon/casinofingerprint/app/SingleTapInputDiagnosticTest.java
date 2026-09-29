package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.execution.FakeAbortSignal;
import io.github.bohdankordon.casinofingerprint.execution.FakeForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.execution.FakeGameInputSink;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.1 one-tap invariant against fakes only: every gate either submits exactly the one
 * requested tap or sends nothing at all, the F12 abort is polled at every stage, and a failing
 * backend is never retried. No test here can reach a native input backend.
 */
class SingleTapInputDiagnosticTest {
    private static final String TARGET = "GTA5.exe";
    private static final int SECONDS = 1;

    @Test
    void nonWindowsHostRefusesWithoutTouchingAnyBackend() {
        Harness harness = new Harness();
        harness.windows = () -> false;
        InputDiagnosticResult result = harness.run(GameControl.UP);
        assertEquals(InputDiagnosticStatus.NON_WINDOWS, result.status(), "Status");
        assertFalse(result.sent(), "Nothing was sent");
        assertTrue(harness.sink.taps().isEmpty(), "Zero taps");
        assertEquals(0, harness.guard.pinCalls(), "No pin");
        assertEquals(0, harness.abort.checks(), "No abort poll");
        assertTrue(harness.sleeper.sleeps().isEmpty(), "No countdown");
    }

    @Test
    void missingOptInRefusesWithoutTouchingAnyBackend() {
        Harness harness = new Harness();
        harness.optIn = false;
        InputDiagnosticResult result = harness.run(GameControl.UP);
        assertEquals(InputDiagnosticStatus.INPUT_DISABLED, result.status(), "Status");
        assertFalse(result.sent(), "Nothing was sent");
        assertTrue(harness.sink.taps().isEmpty(), "Zero taps");
        assertEquals(0, harness.guard.pinCalls(), "No pin");
        assertEquals(0, harness.abort.checks(), "No abort poll");
        assertTrue(harness.sleeper.sleeps().isEmpty(), "No countdown");
    }

    @Test
    void proceedIsRefusedByTheCoreSoTabIsNeverSent() {
        Harness harness = new Harness();
        InputDiagnosticResult result = harness.run(GameControl.PROCEED);
        assertEquals(InputDiagnosticStatus.UNSUPPORTED_CONTROL, result.status(), "Status");
        assertTrue(result.message().contains("PROCEED"), "Names the control: " + result.message());
        assertTrue(harness.sink.taps().isEmpty(), "Zero taps: Tab was never sent");
        assertEquals(0, harness.abort.checks(), "Refused before the countdown");
        assertTrue(harness.sleeper.sleeps().isEmpty(), "No countdown");
    }

    @Test
    void abortAlreadyActiveSendsNothing() {
        Harness harness = new Harness();
        harness.abort = FakeAbortSignal.immediate();
        InputDiagnosticResult result = harness.run(GameControl.UP);
        assertEquals(InputDiagnosticStatus.ABORTED, result.status(), "Status");
        assertTrue(harness.sink.taps().isEmpty(), "Zero taps");
        assertEquals(1, harness.abort.checks(), "Aborted on the first poll");
        assertEquals(0, harness.guard.pinCalls(), "No pin");
        assertTrue(harness.sleeper.sleeps().isEmpty(), "The countdown never started");
    }

    @Test
    void abortBecomingActiveDuringTheCountdownSendsNothing() {
        Harness harness = new Harness();
        harness.abort.script(false, true);
        InputDiagnosticResult result = harness.run(GameControl.UP);
        assertEquals(InputDiagnosticStatus.ABORTED, result.status(), "Status");
        assertTrue(harness.sink.taps().isEmpty(), "Zero taps");
        assertEquals(List.of(SingleTapInputDiagnostic.ABORT_POLL_MILLIS),
                harness.sleeper.sleeps(), "One poll step, then abort");
        assertEquals(0, harness.guard.pinCalls(), "No pin");
    }

    @Test
    void interruptedCountdownSendsNothing() {
        Harness harness = new Harness();
        harness.sleeper.interrupt();
        InputDiagnosticResult result = harness.run(GameControl.UP);
        assertEquals(InputDiagnosticStatus.ABORTED, result.status(), "Status");
        assertTrue(harness.sink.taps().isEmpty(), "Zero taps");
        assertEquals(0, harness.guard.pinCalls(), "No pin");
    }

    @Test
    void targetNotForegroundAfterTheCountdownSendsNothing() {
        Harness harness = new Harness();
        harness.guard = FakeForegroundTargetGuard.missing();
        InputDiagnosticResult result = harness.run(GameControl.UP);
        assertEquals(InputDiagnosticStatus.TARGET_NOT_FOREGROUND, result.status(), "Status");
        assertTrue(harness.sink.taps().isEmpty(), "Zero taps");
        assertEquals(1000, harness.sleeper.totalMillis(), "The full countdown ran first");
        assertEquals(1, harness.guard.pinCalls(), "One pin attempt");
        assertTrue(harness.guard.pinChecks().isEmpty(), "A failed pin is never re-checked");
    }

    @Test
    void focusLostAfterThePinSendsNothing() {
        Harness harness = new Harness();
        harness.guard.scriptPinned(false);
        InputDiagnosticResult result = harness.run(GameControl.RIGHT);
        assertEquals(InputDiagnosticStatus.FOCUS_LOST, result.status(), "Status");
        assertTrue(harness.sink.taps().isEmpty(), "Zero taps after the foreground changed");
        assertEquals(1, harness.guard.pinCalls(), "One pin");
        assertEquals(1, harness.guard.pinChecks().size(), "The pin was re-checked once");
    }

    @Test
    void abortAfterThePinButBeforeTheTapSendsNothing() {
        Harness harness = new Harness();
        // Checks: pre-countdown, one per countdown poll, post-countdown gate, after-pin gate,
        // then the final pre-tap gate. Firing on the last one lands after the guard checks.
        harness.abort = abortFiringOnCheck(checksThroughCountdown(SECONDS) + 2);
        InputDiagnosticResult result = harness.run(GameControl.UP);
        assertEquals(InputDiagnosticStatus.ABORTED, result.status(), "Status");
        assertTrue(harness.sink.taps().isEmpty(), "Zero taps");
        assertEquals(1000, harness.sleeper.totalMillis(), "The countdown finished before the pin");
        assertEquals(1, harness.guard.pinCalls(), "The target was pinned");
        assertEquals(1, harness.guard.pinChecks().size(), "The pin was still valid");
    }

    @Test
    void sendsExactlyOneUpTap() {
        assertOneTap(GameControl.UP);
    }

    @Test
    void sendsExactlyOneDownTap() {
        assertOneTap(GameControl.DOWN);
    }

    @Test
    void sendsExactlyOneLeftTap() {
        assertOneTap(GameControl.LEFT);
    }

    @Test
    void sendsExactlyOneRightTap() {
        assertOneTap(GameControl.RIGHT);
    }

    @Test
    void sendsExactlyOneSelectTap() {
        assertOneTap(GameControl.SELECT);
    }

    @Test
    void refusingSinkReportsInputErrorWithOneAttemptAndNoRetry() {
        Harness harness = new Harness();
        List<GameControl> attempts = new ArrayList<>();
        GameInputSink refusing = control -> {
            attempts.add(control);
            throw new GameInputException("fake backend refused " + control);
        };
        InputDiagnosticResult result = new SingleTapInputDiagnostic(refusing, harness.guard,
                harness.abort, harness.sleeper, () -> true)
                .run(new InputDiagnosticRequest(TARGET, GameControl.UP, true, SECONDS));
        assertEquals(InputDiagnosticStatus.INPUT_ERROR, result.status(), "Status");
        assertFalse(result.sent(), "A failed tap is not a sent tap");
        assertEquals(List.of(GameControl.UP), attempts, "One attempted tap, no retry");
        assertEquals(1, harness.guard.pinCalls(), "One pin");
    }

    @Test
    void successTapsOnlyAfterTheCountdownAndStopsImmediately() {
        Harness harness = new Harness();
        ObservingSink sink = new ObservingSink(harness.sleeper, harness.abort);
        InputDiagnosticResult result = new SingleTapInputDiagnostic(sink, harness.guard,
                harness.abort, harness.sleeper, () -> true)
                .run(new InputDiagnosticRequest(TARGET, GameControl.UP, true, SECONDS));
        assertTrue(result.sent(), "Sent: " + result.status());
        assertEquals(List.of(GameControl.UP), sink.taps, "Exactly one tap");
        assertEquals(1000, harness.sleeper.totalMillis(), "The full countdown ran before the tap");
        assertTrue(harness.sleeper.sleeps().stream()
                        .allMatch(step -> step <= SingleTapInputDiagnostic.ABORT_POLL_MILLIS),
                "Modest poll steps: " + harness.sleeper.sleeps());
        assertEquals(1000, sink.millisSleptAtTap, "No input before the countdown ended");
        assertEquals(sink.abortChecksAtTap, harness.abort.checks(),
                "The invocation ended at the tap: no poll and no second tap can follow");
        assertEquals(1, harness.guard.pinChecks().size(), "One pin re-check");
    }

    private void assertOneTap(GameControl control) {
        Harness harness = new Harness();
        InputDiagnosticResult result = harness.run(control);
        assertTrue(result.sent(), control + " sent: " + result.status());
        assertEquals(List.of(control), harness.sink.taps(), control + " taps");
    }

    /** Abort checks from the pre-countdown gate through the post-countdown gate. */
    private static int checksThroughCountdown(int countdownSeconds) {
        long step = SingleTapInputDiagnostic.ABORT_POLL_MILLIS;
        long polls = (countdownSeconds * 1000L + step - 1) / step;
        return (int) (1 + polls + 1);
    }

    /**
     * An abort fake that stays idle for the first {@code checkNumber - 1} checks and fires on
     * the given (one-based) check, so tests pin exactly where in the ordered gates it fired.
     */
    private static FakeAbortSignal abortFiringOnCheck(int checkNumber) {
        FakeAbortSignal signal = FakeAbortSignal.calm();
        for (int check = 1; check < checkNumber; check++) {
            signal.script(false);
        }
        signal.fire();
        return signal;
    }

    /** Fakes for one run: the sink records taps, the guard pins and the abort stays calm. */
    private static final class Harness {
       final FakeGameInputSink sink = new FakeGameInputSink();
        FakeForegroundTargetGuard guard = FakeForegroundTargetGuard.pinned(TARGET);
        FakeAbortSignal abort = FakeAbortSignal.calm();
       final FakeInputDiagnosticSleeper sleeper = new FakeInputDiagnosticSleeper();
        BooleanSupplier windows = () -> true;
        boolean optIn = true;

        InputDiagnosticResult run(GameControl control) {
            return new SingleTapInputDiagnostic(sink, guard, abort, sleeper, windows)
                    .run(new InputDiagnosticRequest(TARGET, control, optIn, SECONDS));
        }
    }

    /** Records the diagnostic state at tap time: no sleep and no poll may follow the tap. */
    private static final class ObservingSink implements GameInputSink {
        private final FakeInputDiagnosticSleeper sleeper;
        private final FakeAbortSignal abort;
        private final List<GameControl> taps = new ArrayList<>();
        private long millisSleptAtTap;
        private int abortChecksAtTap;

        ObservingSink(FakeInputDiagnosticSleeper sleeper, FakeAbortSignal abort) {
            this.sleeper = sleeper;
            this.abort = abort;
        }

        @Override
        public void tap(GameControl control) {
            taps.add(control);
            millisSleptAtTap = sleeper.totalMillis();
            abortChecksAtTap = abort.checks();
        }
    }
}
