package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.execution.FakeAbortSignal;
import io.github.bohdankordon.casinofingerprint.execution.FakeForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.execution.FakeGameInputSink;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C CLI against fake backends: the non-Windows refusal happens before any native
 * object exists, the armed banner names the explicitly configured abort key, and every
 * outcome is labelled. No test here constructs a Windows backend or emits OS input.
 */
class InputDiagnosticMainTest {
    @Test
    void nonWindowsRefusesBeforeConstructingNativeBackends() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = InputDiagnosticMain.run(options("UP"), streams.out, streams.err, () -> false,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        assertEquals(0, backends.calls, "No native backend is ever constructed off Windows");
        assertTrue(streams.outText().isEmpty(), "Nothing is armed off Windows");
        assertTrue(streams.errText().contains("NON-WINDOWS"), streams.errText());
        assertTrue(backends.sink.taps().isEmpty(), "Zero taps");
    }

    @Test
    void successPrintsTheArmedBannerAndTheSentLines() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = InputDiagnosticMain.run(options("UP"), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        String out = streams.outText();
        assertTrue(out.contains("DIAGNOSTIC INPUT ARMED"), "Armed banner: " + out);
        assertTrue(out.contains("DIAGNOSTIC requested control: UP"), "Control: " + out);
        assertTrue(out.contains("DIAGNOSTIC target executable: GTA5.exe"), "Target: " + out);
        assertTrue(out.contains("DIAGNOSTIC exactly ONE tap maximum"), "One tap: " + out);
        assertTrue(out.contains("DIAGNOSTIC SCROLL_LOCK aborts"), "Abort key: " + out);
        assertTrue(out.contains("DIAGNOSTIC switch to GTA now"), "Countdown hint: " + out);
        assertTrue(out.contains("DIAGNOSTIC SENT exactly one UP tap to pinned GTA5.exe target"),
                "Sent line: " + out);
        assertTrue(out.contains("DIAGNOSTIC no further input will be sent"), "No more input: " + out);
        assertTrue(out.contains("DIAGNOSTIC diagnostic complete"), "Completion: " + out);
        assertTrue(streams.errText().isEmpty(), "No refusal: " + streams.errText());
        assertEquals(1, backends.calls, "One backend set");
        assertEquals(java.util.List.of(io.github.bohdankordon.casinofingerprint.input.GameControl.UP),
                backends.sink.taps(), "Exactly one tap");
    }

    @Test
    void abortedRunIsLabelledAborted() {
        Backends backends = new Backends();
        backends.abort = FakeAbortSignal.immediate();
        Streams streams = new Streams();
        int exit = InputDiagnosticMain.run(options("DOWN"), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        assertTrue(streams.errText().contains("DIAGNOSTIC REFUSED: ABORTED"),
                "Refusal label: " + streams.errText());
        assertTrue(streams.errText().contains("no input was sent"), streams.errText());
        assertTrue(backends.sink.taps().isEmpty(), "Zero taps");
    }
    @Test
    void abortedMessageNamesTheExplicitlyConfiguredAbortKey() {
        Backends backends = new Backends();
        backends.abort = FakeAbortSignal.immediate();
        Streams streams = new Streams();
        InputDiagnosticOptions scrollLock = InputDiagnosticOptions.parse(new String[] {"--enable-input",
                "--target-exe", "GTA5.exe", "--control", "UP", "--abort-key", "SCROLL_LOCK",
                "--countdown-seconds", "1"});
        int exit = InputDiagnosticMain.run(scrollLock, streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        assertTrue(streams.errText().contains("SCROLL_LOCK is active"), streams.errText());
        assertTrue(!streams.errText().contains("F12 is active"), "F12 never silently assumed: " + streams.errText());
    }

    @Test
    void focusLostRunIsLabelledFocusLost() {
        Backends backends = new Backends();
        backends.guard.scriptPinned(false);
        Streams streams = new Streams();
        int exit = InputDiagnosticMain.run(options("RIGHT"), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        assertTrue(streams.errText().contains("DIAGNOSTIC REFUSED: FOCUS LOST"),
                "Refusal label: " + streams.errText());
        assertTrue(streams.outText().contains("DIAGNOSTIC INPUT ARMED"), "Armed before the pin");
        assertTrue(backends.sink.taps().isEmpty(), "Zero taps");
    }

    @Test
    void targetNotForegroundRunIsLabelledAndKeptSeparateFromFocusLoss() {
        Backends backends = new Backends();
        backends.guard = FakeForegroundTargetGuard.missing();
        Streams streams = new Streams();
        int exit = InputDiagnosticMain.run(options("LEFT"), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        assertTrue(streams.errText().contains("DIAGNOSTIC REFUSED: TARGET NOT FOREGROUND"),
                "Refusal label: " + streams.errText());
        assertTrue(backends.sink.taps().isEmpty(), "Zero taps");
    }

    @Test
    void inputErrorNeverClaimsZeroInputAndStatesTheUncertainDelivery() {
        Backends backends = new Backends();
        backends.sink.refuseAtIndex(0);
        Streams streams = new Streams();
        int exit = InputDiagnosticMain.run(options("UP"), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        String err = streams.errText();
        assertTrue(err.contains("DIAGNOSTIC INPUT ERROR"), "Input-error label: " + err);
        assertFalse(err.contains("no input was sent"), "Never claims zero input: " + err);
        assertTrue(err.contains("attempted"), "States the attempt: " + err);
        assertTrue(err.contains("not confirmed"), "States unconfirmed delivery: " + err);
        assertTrue(err.contains("partial"), "States possible partial delivery: " + err);
        assertTrue(err.contains("no retry"), "States that nothing is retried: " + err);
        assertTrue(err.contains("no second tap"), "States that no second tap follows: " + err);
        assertTrue(streams.outText().contains("DIAGNOSTIC INPUT ARMED"),
                "The armed banner was printed before the tap");
        assertEquals(List.of(GameControl.UP), backends.sink.attempts(),
                "Exactly one sink invocation, no retry");
        assertTrue(backends.sink.taps().isEmpty(), "No complete tap was recorded");
    }

    @Test
    void everyPreTapRefusalGuaranteesZeroInput() {
        Outcome aborted = runOnWindows(backends -> backends.abort = FakeAbortSignal.immediate());
        assertEquals(3, aborted.exit(), "Exit code");
        assertTrue(aborted.err().contains("DIAGNOSTIC REFUSED: ABORTED"), aborted.err());
        assertTrue(aborted.err().contains("no input was sent"), aborted.err());
        assertTrue(aborted.backends().sink.attempts().isEmpty(), "ABORTED: zero tap attempts");

        Outcome notForeground =
                runOnWindows(backends -> backends.guard = FakeForegroundTargetGuard.missing());
        assertEquals(3, notForeground.exit(), "Exit code");
        assertTrue(notForeground.err().contains("DIAGNOSTIC REFUSED: TARGET NOT FOREGROUND"),
                notForeground.err());
        assertTrue(notForeground.err().contains("no input was sent"), notForeground.err());
        assertTrue(notForeground.backends().sink.attempts().isEmpty(),
                "TARGET NOT FOREGROUND: zero tap attempts");

        Outcome focusLost = runOnWindows(backends -> backends.guard.scriptPinned(false));
        assertEquals(3, focusLost.exit(), "Exit code");
        assertTrue(focusLost.err().contains("DIAGNOSTIC REFUSED: FOCUS LOST"), focusLost.err());
        assertTrue(focusLost.err().contains("no input was sent"), focusLost.err());
        assertTrue(focusLost.backends().sink.attempts().isEmpty(),
                "FOCUS LOST: zero tap attempts");

        Outcome nonWindows = run(false, backends -> { });
        assertEquals(3, nonWindows.exit(), "Exit code");
        assertTrue(nonWindows.err().contains("NON-WINDOWS"), nonWindows.err());
        assertTrue(nonWindows.err().contains("no native input backend was constructed"),
                "The strongest zero-input proof: no backend exists at all: " + nonWindows.err());
        assertEquals(0, nonWindows.backends().calls,
                "The Windows gate refuses before any backend is constructed");
        assertTrue(nonWindows.backends().sink.attempts().isEmpty(),
                "NON-WINDOWS: zero tap attempts");
    }

    /** One scenario outcome: exit code, captured streams and the fake backend set. */
    private record Outcome(int exit, Streams streams, Backends backends) {
        String err() {
            return streams.errText();
        }
    }

    private static Outcome runOnWindows(Consumer<Backends> scenario) {
        return run(true, scenario);
    }

    private static Outcome run(boolean windows, Consumer<Backends> scenario) {
        Backends backends = new Backends();
        scenario.accept(backends);
        Streams streams = new Streams();
        int exit = InputDiagnosticMain.run(options("UP"), streams.out, streams.err,
                () -> windows, new FakeInputDiagnosticSleeper(), backends);
        return new Outcome(exit, streams, backends);
    }

    private static InputDiagnosticOptions options(String control) {
        return InputDiagnosticOptions.parse(new String[] {"--enable-input", "--target-exe",
                "GTA5.exe", "--control", control, "--abort-key", "SCROLL_LOCK",
                "--countdown-seconds", "1"});
    }

    /** Fake backend set recording construction: zero calls off Windows. */
    private static final class Backends implements Supplier<InputDiagnosticMain.Backends> {
        private final FakeGameInputSink sink = new FakeGameInputSink();
        private FakeForegroundTargetGuard guard = FakeForegroundTargetGuard.pinned("GTA5.exe");
        private FakeAbortSignal abort = FakeAbortSignal.calm();
        private int calls;

        @Override
        public InputDiagnosticMain.Backends get() {
            calls++;
            return new InputDiagnosticMain.Backends(sink, guard, abort);
        }
    }

    private static final class Streams {
        private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        String outText() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String errText() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
