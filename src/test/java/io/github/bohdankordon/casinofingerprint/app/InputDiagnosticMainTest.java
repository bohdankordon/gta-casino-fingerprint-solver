package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.execution.FakeAbortSignal;
import io.github.bohdankordon.casinofingerprint.execution.FakeForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.execution.FakeGameInputSink;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.1 CLI against fake backends: the non-Windows refusal happens before any native
 * object exists, the armed banner is explicit, and every outcome is labelled. No test here
 * constructs a Windows backend or emits OS input.
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
        assertTrue(out.contains("DIAGNOSTIC F12 aborts"), "Abort key: " + out);
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

    private static InputDiagnosticOptions options(String control) {
        return InputDiagnosticOptions.parse(new String[] {"--enable-input", "--target-exe",
                "GTA5.exe", "--control", control, "--countdown-seconds", "1"});
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
