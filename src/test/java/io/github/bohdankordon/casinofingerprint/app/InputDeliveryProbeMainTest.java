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
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticHoldSleeper;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticInputDelivery;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticInputDeliveryMode;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticKeyStroke;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticKeyStrokeSubmitter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.2 probe CLI against fake backends: the non-Windows refusal happens before any native
 * object exists, the armed banner names the mode, hold and configured abort key, the truthful
 * delivery outcome is printed, and no outcome ever claims zero input after an attempt. No test
 * here constructs a Windows backend or can emit OS input.
 */
class InputDeliveryProbeMainTest {
    private static final String TARGET = "GTA5_Enhanced.exe";

    @Test
    void nonWindowsRefusesBeforeConstructingNativeBackends() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("UP", "VK_BATCH"), streams.out, streams.err,
                () -> false, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        assertEquals(0, backends.calls, "No native backend is ever constructed off Windows");
        assertTrue(streams.outText().isEmpty(), "Nothing is armed off Windows");
        assertTrue(streams.errText().contains("NON-WINDOWS"), streams.errText());
        assertTrue(streams.errText().contains("no native input backend was constructed"),
                streams.errText());
        assertTrue(backends.fake.attempts().isEmpty(), "Zero taps");
    }

    @Test
    void successPrintsTheArmedBannerWithModeHoldAndAbortKey() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("UP", "SCANCODE_HOLD"), streams.out,
                streams.err, () -> true, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        String out = streams.outText();
        assertTrue(out.contains("PROBE INPUT ARMED"), "Armed banner: " + out);
        assertTrue(out.contains("PROBE requested control: UP"), "Control: " + out);
        assertTrue(out.contains("PROBE delivery mode: SCANCODE_HOLD (scan-code key-down, hold,"
                + " scan-code key-up as separate submissions)"), "Mode: " + out);
        assertTrue(out.contains("PROBE hold: 50 ms between the key-down and key-up submissions"),
                "Hold: " + out);
        assertTrue(out.contains("PROBE target executable: " + TARGET), "Target: " + out);
        assertTrue(out.contains("PROBE exactly ONE tap maximum"), "One tap: " + out);
        assertTrue(out.contains("PROBE emergency abort key: F8"), "Abort key: " + out);
        assertTrue(out.contains("PROBE switch to GTA now; countdown: 1 s"), "Countdown: " + out);
        assertTrue(out.contains("PROBE SENT exactly one UP tap using delivery mode SCANCODE_HOLD"
                + " (hold 50 ms) to pinned " + TARGET + " target"), "Sent line: " + out);
        assertTrue(out.contains("only the human operator can determine whether GTA visibly"
                + " reacted"), "Never claims a GTA reaction: " + out);
        assertTrue(out.contains("PROBE no further input will be sent"), "No more input: " + out);
        assertTrue(out.contains("PROBE probe complete"), "Completion: " + out);
        assertTrue(streams.errText().isEmpty(), "No refusal: " + streams.errText());
        assertEquals(1, backends.calls, "One backend set");
        assertEquals(List.of(GameControl.UP), backends.fake.attempts(),
                "Exactly one tap attempt");
    }

    @Test
    void batchModesAnnounceThatThereIsNoHold() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("UP", "VK_BATCH"), streams.out, streams.err,
                () -> true, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        String out = streams.outText();
        assertTrue(out.contains("PROBE delivery mode: VK_BATCH (virtual-key key-down + key-up in"
                + " ONE batch, no hold (the exact production batch))"), "Mode: " + out);
        assertTrue(out.contains("PROBE hold: none; key-down and key-up are submitted in one"
                + " batch"), "No hold: " + out);
        assertTrue(out.contains("(no hold, one batch)"), "Sent phrase: " + out);
    }

    @Test
    void documentedAbortKeyConflictsAreWarnedAbout() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("UP", "VK_BATCH", "F12"), streams.out,
                streams.err, () -> true, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        String out = streams.outText();
        assertTrue(out.contains("PROBE emergency abort key: F12"), "Chosen key: " + out);
        assertTrue(out.contains("PROBE WARNING: F12 has a documented shortcut conflict (Steam"
                + " screenshot shortcut"), "Conflict warning: " + out);
        assertTrue(out.contains("it was chosen explicitly, so check your own bindings"),
                "Explicit override wording: " + out);
    }

    @Test
    void abortKeyLabelIsUsedInAbortRefusals() {
        Backends backends = new Backends();
        backends.abort = FakeAbortSignal.immediate();
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("DOWN", "VK_HOLD"), streams.out, streams.err,
                () -> true, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        String err = streams.errText();
        assertTrue(err.contains("PROBE REFUSED: ABORTED"), "Refusal label: " + err);
        assertTrue(err.contains("F8 is active"), "The configured key is named: " + err);
        assertFalse(err.contains("F12"), "F12 is never silently assumed: " + err);
        assertTrue(err.contains("no input was sent"), err);
        assertTrue(backends.fake.attempts().isEmpty(), "Zero taps");
    }

    @Test
    void holdModeSuccessPrintsTheTruthfulOutcome() {
        FakeAbortSignal abort = FakeAbortSignal.calm();
        RecordingSubmitter submitter = new RecordingSubmitter();
        RecordingSleeper sleeper = new RecordingSleeper();
        DiagnosticInputDelivery sink = new DiagnosticInputDelivery(
                DiagnosticInputDeliveryMode.VK_HOLD, 50, new FakeGameInputSink(), submitter,
                abort, sleeper);
        Backends backends = new Backends(sink);
        backends.abort = abort;
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("UP", "VK_HOLD"), streams.out, streams.err,
                () -> true, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        String out = streams.outText();
        assertTrue(out.contains("PROBE delivery outcome: mode VK_HOLD, hold 50 ms requested /"
                + " 50 ms actual, key-down confirmed: yes, key-up confirmed: yes, key-up cleanup"
                + " attempted: no, abort during hold: no"), "Outcome: " + out);
        assertTrue(streams.errText().isEmpty(), "No refusal: " + streams.errText());
        assertEquals(List.of(List.of(DiagnosticKeyStroke.virtualKey(0x26, false)),
                        List.of(DiagnosticKeyStroke.virtualKey(0x26, true))), submitter.batches,
                "The probe performed the hold-mode delivery");
    }

    @Test
    void abortDuringHoldIsReportedAndNeverClaimsZeroInput() {
        FakeAbortSignal abort = FakeAbortSignal.calm();
        RecordingSubmitter submitter = new RecordingSubmitter();
        submitter.afterSubmit(0, abort::fire);
        DiagnosticInputDelivery sink = new DiagnosticInputDelivery(
                DiagnosticInputDeliveryMode.VK_HOLD, 50, new FakeGameInputSink(), submitter,
                abort, new RecordingSleeper());
        Backends backends = new Backends(sink);
        backends.abort = abort;
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("UP", "VK_HOLD"), streams.out, streams.err,
                () -> true, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "The one tap was still fully submitted");
        String out = streams.outText();
        assertTrue(out.contains("abort during hold: yes"), "Outcome records the early release");
        assertTrue(out.contains("PROBE note: the emergency abort key (F8) fired during the hold:"
                + " the key was released early and the key-up was still submitted"), "Note: " + out);
        assertFalse(out.contains("no input was sent"), "Never claims zero input after a key-down");
        assertFalse(streams.errText().contains("no input was sent"), streams.errText());
        assertEquals(2, submitter.batches.size(), "Key-down and key-up were both submitted");
    }

    @Test
    void inputErrorNeverClaimsZeroInputAndPrintsTheOutcome() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        submitter.failAt(1, 0);
        submitter.failAt(2, 0);
        DiagnosticInputDelivery sink = new DiagnosticInputDelivery(
                DiagnosticInputDeliveryMode.SCANCODE_HOLD, 50, new FakeGameInputSink(), submitter,
                FakeAbortSignal.calm(), new RecordingSleeper());
        Backends backends = new Backends(sink);
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("SELECT", "SCANCODE_HOLD"), streams.out,
                streams.err, () -> true, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        String err = streams.errText();
        assertTrue(err.contains("PROBE INPUT ERROR"), "Input-error label: " + err);
        assertFalse(err.contains("no input was sent"), "Never claims zero input: " + err);
        assertTrue(err.contains("partial native input may have been delivered"), err);
        assertTrue(err.contains("no retry"), err);
        assertTrue(err.contains("no second tap"), err);
        assertTrue(err.contains("PROBE delivery outcome: mode SCANCODE_HOLD"), "Outcome: " + err);
        assertTrue(err.contains("key-down confirmed: yes"), "The key-down is reported: " + err);
        assertTrue(err.contains("key-up confirmed: no"), "The failed release is reported: " + err);
        assertTrue(err.contains("key-up cleanup attempted: yes"), "The cleanup is reported: " + err);
        assertTrue(err.contains("PROBE warning: the key-up could not be confirmed"), err);
        assertTrue(err.contains("press and release the key once manually"), err);
        assertTrue(streams.outText().contains("PROBE INPUT ARMED"), "Armed before the tap");
        assertEquals(1, submitter.downStrokes(), "Exactly one key-down attempt, no retry");
    }

    @Test
    void everyPreTapRefusalGuaranteesZeroInput() {
        Outcome aborted = runOnWindows(backends -> backends.abort = FakeAbortSignal.immediate());
        assertEquals(3, aborted.exit(), "Exit code");
        assertTrue(aborted.err().contains("PROBE REFUSED: ABORTED"), aborted.err());
        assertTrue(aborted.err().contains("no input was sent"), aborted.err());
        assertTrue(aborted.backends().fake.attempts().isEmpty(), "ABORTED: zero tap attempts");

        Outcome notForeground = runOnWindows(
                backends -> backends.guard = FakeForegroundTargetGuard.missing());
        assertEquals(3, notForeground.exit(), "Exit code");
        assertTrue(notForeground.err().contains("PROBE REFUSED: TARGET NOT FOREGROUND"),
                notForeground.err());
        assertTrue(notForeground.err().contains("no input was sent"), notForeground.err());
        assertTrue(notForeground.backends().fake.attempts().isEmpty(),
                "TARGET NOT FOREGROUND: zero tap attempts");

        Outcome focusLost = runOnWindows(backends -> backends.guard.scriptPinned(false));
        assertEquals(3, focusLost.exit(), "Exit code");
        assertTrue(focusLost.err().contains("PROBE REFUSED: FOCUS LOST"), focusLost.err());
        assertTrue(focusLost.err().contains("no input was sent"), focusLost.err());
        assertTrue(focusLost.backends().fake.attempts().isEmpty(),
                "FOCUS LOST: zero tap attempts");
    }

    @Test
    void oneInvocationTapsAtMostOnce() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("RIGHT", "SCANCODE_BATCH"), streams.out,
                streams.err, () -> true, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        assertEquals(List.of(GameControl.RIGHT), backends.fake.taps(), "Exactly one tap");
        assertEquals(List.of(GameControl.RIGHT), backends.fake.attempts(),
                "Exactly one tap invocation, no retry");
    }

    /** One scenario outcome: exit code, captured streams and the fake backend set. */
    private record Outcome(int exit, Streams streams, Backends backends) {
        String err() {
            return streams.errText();
        }
    }

    private static Outcome runOnWindows(Consumer<Backends> scenario) {
        Backends backends = new Backends();
        scenario.accept(backends);
        Streams streams = new Streams();
        int exit = InputDeliveryProbeMain.run(options("UP", "VK_HOLD"), streams.out, streams.err,
                () -> true, new FakeInputDiagnosticSleeper(), backends);
        return new Outcome(exit, streams, backends);
    }

    private static InputDeliveryProbeOptions options(String control, String mode) {
        return options(control, mode, "F8");
    }

    private static InputDeliveryProbeOptions options(String control, String mode, String abortKey) {
        return InputDeliveryProbeOptions.parse(new String[] {"--enable-input", "--target-exe",
                TARGET, "--control", control, "--delivery-mode", mode, "--abort-key", abortKey,
                "--countdown-seconds", "1"});
    }

    /** Fake backend set recording construction: zero calls off Windows. */
    private static final class Backends implements Supplier<InputDeliveryProbeMain.Backends> {
        private final GameInputSink sink;
        private final FakeGameInputSink fake = new FakeGameInputSink();
        private FakeForegroundTargetGuard guard = FakeForegroundTargetGuard.pinned(TARGET);
        private FakeAbortSignal abort = FakeAbortSignal.calm();
        private int calls;

        Backends() {
            this.sink = fake;
        }

        Backends(GameInputSink sink) {
            this.sink = sink;
        }

        @Override
        public InputDeliveryProbeMain.Backends get() {
            calls++;
            return new InputDeliveryProbeMain.Backends(sink, guard, abort);
        }
    }

    /** Recording submission seam for the pure delivery core: no native input exists. */
    private static final class RecordingSubmitter implements DiagnosticKeyStrokeSubmitter {
        private final List<List<DiagnosticKeyStroke>> batches = new ArrayList<>();
        private final Map<Integer, Integer> failDelivered = new HashMap<>();
        private final Map<Integer, Runnable> hooks = new HashMap<>();

        void failAt(int index, int delivered) {
            failDelivered.put(index, delivered);
        }

        void afterSubmit(int index, Runnable hook) {
            hooks.put(index, hook);
        }

        long downStrokes() {
            return batches.stream().flatMap(List::stream).filter(stroke -> !stroke.keyUp()).count();
        }

        @Override
        public int submit(DiagnosticKeyStroke... strokes) {
            int index = batches.size();
            batches.add(List.of(strokes));
            Runnable hook = hooks.get(index);
            if (hook != null) {
                hook.run();
            }
            return failDelivered.getOrDefault(index, strokes.length);
        }
    }

    /** Recording hold sleeper: no test waits in real time. */
    private static final class RecordingSleeper implements DiagnosticHoldSleeper {
        @Override
        public void sleepMillis(long millis) {
            // Recorded nowhere on purpose: the probe's outcome carries the hold it performed.
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
