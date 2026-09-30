package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.execution.FakeAbortSignal;
import io.github.bohdankordon.casinofingerprint.execution.FakeForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.execution.FakeGameInputSink;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticInputDeliveryMode;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticKeyStroke;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticKeyStrokeSubmitter;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.ProceedDiagnosticDelivery;
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
 * Stage 8C.3 dedicated proceed CLI against fake backends: the non-Windows refusal happens
 * before any native object exists, the armed banner pins the fixed PROCEED SCANCODE_BATCH
 * Tab contract, every pre-tap refusal guarantees zero input, exactly one Tab tap is sent on
 * success, and an INPUT ERROR never claims zero input. No test here constructs a Windows
 * backend or can emit OS input.
 */
class ProceedInputProbeMainTest {
    private static final String TARGET = "GTA5_Enhanced.exe";

    @Test
    void nonWindowsRefusesBeforeConstructingNativeBackends() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = ProceedInputProbeMain.run(options(), streams.out, streams.err, () -> false,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        assertEquals(0, backends.calls, "No native backend is ever constructed off Windows");
        assertTrue(streams.outText().isEmpty(), "Nothing is armed off Windows");
        assertTrue(streams.errText().contains("NON-WINDOWS"), streams.errText());
        assertTrue(streams.errText().contains("no native input backend was constructed"),
                streams.errText());
        assertTrue(backends.fake.attempts().isEmpty(), "Zero taps");
    }

    @Test
    void successSendsExactlyOneProceedTapWithFixedContract() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = ProceedInputProbeMain.run(options(), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        String out = streams.outText();
        assertTrue(out.contains("PROCEED PROBE INPUT ARMED"), "Armed banner: " + out);
        assertTrue(out.contains("PROCEED PROBE requested control: PROCEED (Tab)"),
                "Control: " + out);
        assertTrue(out.contains("PROCEED PROBE delivery mode: SCANCODE_BATCH"), "Mode: " + out);
        assertTrue(out.contains("PROCEED PROBE hold: none; Tab key-down and key-up are submitted"
                + " in one batch"), "No hold: " + out);
        assertTrue(out.contains("Set-1 0x0F"), "Tab make code: " + out);
        assertTrue(out.contains("PROCEED PROBE target executable: " + TARGET), "Target: " + out);
        assertTrue(out.contains("PROCEED PROBE exactly ONE Tab tap maximum"), "One tap: " + out);
        assertTrue(out.contains("PROCEED PROBE emergency abort key: SCROLL_LOCK"),
                "Abort key: " + out);
        assertTrue(out.contains("PROCEED PROBE switch to GTA now; countdown: 1 s"),
                "Countdown: " + out);
        assertTrue(out.contains("PROCEED PROBE SENT exactly one PROCEED tap using delivery mode"
                + " SCANCODE_BATCH (no hold, one batch) to pinned " + TARGET + " target"),
                "Sent line: " + out);
        assertTrue(out.contains("only the human operator can determine whether GTA visibly"
                + " reacted"), "Never claims a GTA reaction: " + out);
        assertTrue(out.contains("PROCEED PROBE no further input will be sent"),
                "No more input: " + out);
        assertTrue(out.contains("PROCEED PROBE probe complete"), "Completion: " + out);
        assertTrue(streams.errText().isEmpty(), "No refusal: " + streams.errText());
        assertEquals(1, backends.calls, "One backend set");
        assertEquals(List.of(GameControl.PROCEED), backends.fake.attempts(),
                "Exactly one PROCEED attempt");
        assertEquals(List.of(GameControl.PROCEED), backends.fake.taps(), "Exactly one tap");
    }

    @Test
    void documentedAbortKeyConflictsAreWarnedAbout() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = ProceedInputProbeMain.run(optionsWithAbort("F12"), streams.out, streams.err,
                () -> true, new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        String out = streams.outText();
        assertTrue(out.contains("PROCEED PROBE emergency abort key: F12"), "Chosen key: " + out);
        assertTrue(out.contains("PROCEED PROBE WARNING: F12 has a documented shortcut conflict"
                + " (Steam screenshot shortcut"), "Conflict warning: " + out);
    }

    @Test
    void abortKeyLabelIsUsedInAbortRefusals() {
        Backends backends = new Backends();
        backends.abort = FakeAbortSignal.immediate();
        Streams streams = new Streams();
        int exit = ProceedInputProbeMain.run(options(), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        String err = streams.errText();
        assertTrue(err.contains("PROCEED PROBE REFUSED: ABORTED"), "Refusal label: " + err);
        assertTrue(err.contains("SCROLL_LOCK is active"), "Configured key named: " + err);
        assertFalse(err.contains("F12"), "F12 never silently assumed: " + err);
        assertTrue(err.contains("no input was sent"), err);
        assertTrue(backends.fake.attempts().isEmpty(), "Zero taps");
    }

    @Test
    void successPrintsTheTruthfulTabOutcome() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        ProceedDiagnosticDelivery sink = new ProceedDiagnosticDelivery(submitter);
        Backends backends = new Backends(sink);
        Streams streams = new Streams();
        int exit = ProceedInputProbeMain.run(options(), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        String out = streams.outText();
        assertTrue(out.contains("PROCEED PROBE delivery outcome: mode SCANCODE_BATCH, hold 0 ms"
                + " requested / 0 ms actual, key-down confirmed: yes, key-up confirmed: yes,"
                + " key-up cleanup attempted: no, abort during hold: no"), "Outcome: " + out);
        assertEquals(1, submitter.batches.size(), "One Tab batch");
        assertEquals(0x0F, submitter.batches.get(0).get(0).wScan(), "Tab down");
        assertEquals(0x0F, submitter.batches.get(0).get(1).wScan(), "Tab up");
        assertEquals(0, submitter.batches.get(0).get(0).wVk(), "wVk zero");
    }

    @Test
    void inputErrorNeverClaimsZeroInputAndPrintsTheOutcome() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        submitter.failAt(0, 1);
        submitter.failAt(1, 0);
        ProceedDiagnosticDelivery sink = new ProceedDiagnosticDelivery(submitter);
        Backends backends = new Backends(sink);
        Streams streams = new Streams();
        int exit = ProceedInputProbeMain.run(options(), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        String err = streams.errText();
        assertTrue(err.contains("PROCEED PROBE INPUT ERROR"), "Input-error label: " + err);
        assertFalse(err.contains("no input was sent"), "Never claims zero input: " + err);
        assertTrue(err.contains("partial native input may have been delivered"), err);
        assertTrue(err.contains("no retry"), err);
        assertTrue(err.contains("no second tap"), err);
        assertTrue(err.contains("PROCEED PROBE delivery outcome: mode SCANCODE_BATCH"),
                "Outcome: " + err);
        assertTrue(err.contains("key-down confirmed: no"), "Partial batch: " + err);
        assertTrue(err.contains("key-up confirmed: no"), "Unconfirmed cleanup: " + err);
        assertTrue(err.contains("key-up cleanup attempted: yes"), "Cleanup: " + err);
        assertTrue(err.contains("PROCEED PROBE warning: the Tab key-up could not be confirmed"),
                err);
        assertTrue(err.contains("press and release Tab once manually"), err);
        assertTrue(streams.outText().contains("PROCEED PROBE INPUT ARMED"),
                "Armed before the tap");
        assertEquals(1, submitter.downStrokes(), "Exactly one Tab key-down, no retry");
        assertEquals(2, submitter.batches.size(), "Batch plus one cleanup");
    }

    @Test
    void everyPreTapRefusalGuaranteesZeroInput() {
        Outcome aborted = runOnWindows(backends -> backends.abort = FakeAbortSignal.immediate());
        assertEquals(3, aborted.exit(), "Exit code");
        assertTrue(aborted.err().contains("PROCEED PROBE REFUSED: ABORTED"), aborted.err());
        assertTrue(aborted.err().contains("no input was sent"), aborted.err());
        assertTrue(aborted.backends().fake.attempts().isEmpty(), "ABORTED: zero taps");

        Outcome notForeground = runOnWindows(
                backends -> backends.guard = FakeForegroundTargetGuard.missing());
        assertEquals(3, notForeground.exit(), "Exit code");
        assertTrue(notForeground.err().contains("PROCEED PROBE REFUSED: TARGET NOT FOREGROUND"),
                notForeground.err());
        assertTrue(notForeground.err().contains("no input was sent"), notForeground.err());
        assertTrue(notForeground.backends().fake.attempts().isEmpty(),
                "TARGET NOT FOREGROUND: zero taps");

        Outcome focusLost = runOnWindows(backends -> backends.guard.scriptPinned(false));
        assertEquals(3, focusLost.exit(), "Exit code");
        assertTrue(focusLost.err().contains("PROCEED PROBE REFUSED: FOCUS LOST"),
                focusLost.err());
        assertTrue(focusLost.err().contains("no input was sent"), focusLost.err());
        assertTrue(focusLost.backends().fake.attempts().isEmpty(), "FOCUS LOST: zero taps");
    }

    @Test
    void finalAbortBeforeTapSendsNothing() {
        Backends backends = new Backends();
        // Checks: pre-countdown, countdown polls, post-countdown, after-pin, final pre-tap.
        // Firing on the last check lands after the pin was still valid.
        FakeAbortSignal abort = FakeAbortSignal.calm();
        int checksThroughCountdown = 1 + 10 + 1;
        for (int check = 1; check < checksThroughCountdown + 2; check++) {
            abort.script(false);
        }
        abort.fire();
        backends.abort = abort;
        Streams streams = new Streams();
        int exit = ProceedInputProbeMain.run(options(), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(3, exit, "Exit code");
        assertTrue(streams.errText().contains("PROCEED PROBE REFUSED: ABORTED"),
                streams.errText());
        assertTrue(streams.errText().contains("no input was sent"), streams.errText());
        assertTrue(backends.fake.attempts().isEmpty(), "Zero taps");
        assertEquals(1, backends.guard.pinCalls(), "Target was pinned");
        assertEquals(1, backends.guard.pinChecks().size(), "Pin was still valid");
    }

    @Test
    void oneInvocationTapsAtMostOnce() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        int exit = ProceedInputProbeMain.run(options(), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        assertEquals(0, exit, "Exit code");
        assertEquals(List.of(GameControl.PROCEED), backends.fake.taps(), "Exactly one tap");
        assertEquals(List.of(GameControl.PROCEED), backends.fake.attempts(),
                "Exactly one invocation, no retry, no second tap");
    }

    @Test
    void dedicatedCoreRefusesNonProceedSoArrowsNeverLeave() {
        Backends backends = new Backends();
        Streams streams = new Streams();
        // The dedicated Main always requests PROCEED; the core itself must refuse anything else.
        ProceedTapDiagnostic core = new ProceedTapDiagnostic(backends.fake, backends.guard,
                backends.abort, new FakeInputDiagnosticSleeper(), () -> true, "SCROLL_LOCK");
        InputDiagnosticResult refused = core.run(
                new InputDiagnosticRequest(TARGET, GameControl.UP, true, 1));
        assertEquals(InputDiagnosticStatus.UNSUPPORTED_CONTROL, refused.status(), "Status");
        assertTrue(backends.fake.attempts().isEmpty(), "Zero taps for UP");
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
        int exit = ProceedInputProbeMain.run(options(), streams.out, streams.err, () -> true,
                new FakeInputDiagnosticSleeper(), backends);
        return new Outcome(exit, streams, backends);
    }

    private static ProceedInputProbeOptions options() {
        return ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                "--enable-proceed-test", "--target-exe", TARGET, "--abort-key", "SCROLL_LOCK",
                "--countdown-seconds", "1"});
    }

    private static ProceedInputProbeOptions optionsWithAbort(String abortKey) {
        return ProceedInputProbeOptions.parse(new String[] {"--enable-input",
                "--enable-proceed-test", "--target-exe", TARGET, "--abort-key", abortKey,
                "--countdown-seconds", "1"});
    }

    /** Fake backend set recording construction: zero calls off Windows. */
    private static final class Backends implements Supplier<ProceedInputProbeMain.Backends> {
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
        public ProceedInputProbeMain.Backends get() {
            calls++;
            return new ProceedInputProbeMain.Backends(sink, guard, abort);
        }
    }

    /** Recording submission seam for the pure Tab delivery: no native input exists. */
    private static final class RecordingSubmitter implements DiagnosticKeyStrokeSubmitter {
        private final List<List<DiagnosticKeyStroke>> batches = new ArrayList<>();
        private final Map<Integer, Integer> failDelivered = new HashMap<>();

        void failAt(int index, int delivered) {
            failDelivered.put(index, delivered);
        }

        long downStrokes() {
            return batches.stream().flatMap(List::stream)
                    .filter(stroke -> !stroke.keyUp()).count();
        }

        @Override
        public int submit(DiagnosticKeyStroke... strokes) {
            int index = batches.size();
            batches.add(List.of(strokes));
            return failDelivered.getOrDefault(index, strokes.length);
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
