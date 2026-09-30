package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.execution.FakeAbortSignal;
import io.github.bohdankordon.casinofingerprint.execution.FakeForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.execution.FakeGameInputSink;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import org.junit.jupiter.api.Test;

/**
 * The additive Stage 8C.2 abort-key label: the Stage 8C.1 five-argument constructor keeps the
 * exact F12 wording, while the characterization probe labels its explicitly configured key in
 * every ABORTED refusal. The Stage 8C.1 gate behavior itself is unchanged and pinned by its own
 * untouched test suite.
 */
class SingleTapInputDiagnosticAbortKeyLabelTest {
    private static final String TARGET = "GTA5.exe";

    @Test
    void theStage8c1ConstructorStillLabelsF12() {
        FakeGameInputSink sink = new FakeGameInputSink();
        InputDiagnosticResult result = new SingleTapInputDiagnostic(sink,
                FakeForegroundTargetGuard.pinned(TARGET), FakeAbortSignal.immediate(),
                new FakeInputDiagnosticSleeper(), () -> true)
                .run(new InputDiagnosticRequest(TARGET, GameControl.UP, true, 1));
        assertEquals(InputDiagnosticStatus.ABORTED, result.status(), "Status");
        assertEquals("F12", SingleTapInputDiagnostic.DEFAULT_ABORT_KEY_LABEL, "Default label");
        assertTrue(result.message().contains("F12 is active"), result.message());
        assertTrue(sink.attempts().isEmpty(), "Zero taps");
    }

    @Test
    void theConfiguredLabelIsUsedBeforeTheCountdown() {
        InputDiagnosticResult result = run(FakeAbortSignal.immediate(), "PAUSE");
        assertEquals(InputDiagnosticStatus.ABORTED, result.status(), "Status");
        assertTrue(result.message().contains("PAUSE is active"), result.message());
        assertFalse(result.message().contains("F12"), result.message());
    }

    @Test
    void theConfiguredLabelIsUsedDuringTheCountdown() {
        FakeAbortSignal abort = FakeAbortSignal.calm();
        abort.script(false, true);
        InputDiagnosticResult result = run(abort, "SCROLL_LOCK");
        assertEquals(InputDiagnosticStatus.ABORTED, result.status(), "Status");
        assertTrue(result.message().contains("SCROLL_LOCK became active during the countdown"),
                result.message());
        assertFalse(result.message().contains("F12"), result.message());
    }

    @Test
    void theConfiguredLabelIsUsedAtTheFinalGateBeforeTheTap() {
        // Checks: pre-countdown, one per countdown poll (10 for one second), post-countdown,
        // after-pin, then the final pre-tap gate.
        FakeAbortSignal abort = FakeAbortSignal.calm();
        for (int check = 0; check < 13; check++) {
            abort.script(false);
        }
        abort.fire();
        InputDiagnosticResult result = run(abort, "F24");
        assertEquals(InputDiagnosticStatus.ABORTED, result.status(), "Status");
        assertTrue(result.message().contains("F24 is active"), result.message());
        assertFalse(result.message().contains("F12"), result.message());
    }

    private static InputDiagnosticResult run(FakeAbortSignal abort, String label) {
        FakeGameInputSink sink = new FakeGameInputSink();
        InputDiagnosticResult result = new SingleTapInputDiagnostic(sink,
                FakeForegroundTargetGuard.pinned(TARGET), abort, new FakeInputDiagnosticSleeper(),
                () -> true, label)
                .run(new InputDiagnosticRequest(TARGET, GameControl.UP, true, 1));
        assertTrue(sink.attempts().isEmpty(), "An abort never sends input");
        return result;
    }
}
