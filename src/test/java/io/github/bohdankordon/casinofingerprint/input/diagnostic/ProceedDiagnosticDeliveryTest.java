package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.3 dedicated Tab delivery against fakes only: exactly one SCANCODE_BATCH Tab
 * batch per invocation, only PROCEED accepted, best-effort Tab key-up cleanup after every
 * failure, no retry and truthful outcomes. No test here can emit native input.
 */
class ProceedDiagnosticDeliveryTest {
    @Test
    void proceedSendsOneOrderedTabBatch() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        ProceedDiagnosticDelivery delivery = new ProceedDiagnosticDelivery(submitter);
        delivery.tap(GameControl.PROCEED);
        assertEquals(1, submitter.batches().size(), "exactly one native batch");
        List<DiagnosticKeyStroke> batch = submitter.batches().get(0);
        assertEquals(2, batch.size(), "Tab down plus Tab up");
        assertFalse(batch.get(0).keyUp(), "key-down first");
        assertTrue(batch.get(1).keyUp(), "key-up second");
        assertEquals(0, batch.get(0).wVk(), "wVk zero");
        assertEquals(0, batch.get(1).wVk(), "wVk zero");
        assertEquals(0x0F, batch.get(0).wScan(), "Tab make code");
        assertEquals(0x0F, batch.get(1).wScan(), "same Tab make code");
        assertEquals(DiagnosticKeyStroke.KEYEVENTF_SCANCODE, batch.get(0).flags(),
                "down is SCANCODE only");
        assertEquals(DiagnosticKeyStroke.KEYEVENTF_SCANCODE | DiagnosticKeyStroke.KEYEVENTF_KEYUP,
                batch.get(1).flags(), "up is SCANCODE|KEYUP");
        assertFalse(batch.get(0).extended(), "Tab is not extended");
        assertFalse(batch.get(1).extended(), "Tab release is not extended");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertEquals(DiagnosticInputDeliveryMode.SCANCODE_BATCH, outcome.mode());
        assertEquals(0, outcome.requestedHoldMillis(), "no hold requested");
        assertEquals(0, outcome.actualHoldMillis(), "no hold elapsed");
        assertTrue(outcome.keyDownConfirmed(), "down confirmed");
        assertTrue(outcome.keyUpConfirmed(), "up confirmed");
        assertFalse(outcome.keyUpCleanupAttempted(), "no cleanup needed");
        assertFalse(outcome.abortedDuringHold(), "BATCH never holds");
        assertTrue(outcome.complete(), "complete");
    }

    @Test
    void onlyProceedIsAccepted() {
        for (GameControl other : List.of(GameControl.UP, GameControl.DOWN, GameControl.LEFT,
                GameControl.RIGHT, GameControl.SELECT)) {
            RecordingSubmitter submitter = new RecordingSubmitter();
            ProceedDiagnosticDelivery delivery = new ProceedDiagnosticDelivery(submitter);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> delivery.tap(other), other + " must be refused");
            assertTrue(error.getMessage().contains("PROCEED"), other + ": " + error.getMessage());
            assertTrue(submitter.batches().isEmpty(), other + ": zero submissions");
            assertTrue(delivery.lastOutcome().isEmpty(), other + ": no outcome");
        }
    }

    @Test
    void oneTapSendsExactlyOneDownAndOneUp() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        ProceedDiagnosticDelivery delivery = new ProceedDiagnosticDelivery(submitter);
        delivery.tap(GameControl.PROCEED);
        assertEquals(1, submitter.downStrokes(), "one key-down");
        assertEquals(1, submitter.upStrokes(), "one key-up");
    }

    @Test
    void partialBatchAttemptsTabKeyUpCleanupAndNeverRetries() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        submitter.failAt(0, 1);
        ProceedDiagnosticDelivery delivery = new ProceedDiagnosticDelivery(submitter);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.PROCEED));
        assertEquals(2, submitter.batches().size(), "batch plus batch up plus one cleanup release");
        assertEquals(1, submitter.downStrokes(), "no second key-down");
        assertEquals(2, submitter.upStrokes(), "batch key-up plus cleanup release");
        assertTrue(submitter.batches().get(1).get(0).keyUp(), "cleanup is a release");
        assertEquals(0x0F, submitter.batches().get(1).get(0).wScan(), "cleanup is still Tab");
        assertEquals(0, submitter.batches().get(1).get(0).wVk(), "wVk still zero");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertFalse(outcome.keyDownConfirmed(), "partial batch never confirmed");
        assertTrue(outcome.keyUpCleanupAttempted(), "cleanup recorded");
        assertTrue(outcome.keyUpConfirmed(), "cleanup release confirmed");
        assertFalse(outcome.complete() && outcome.keyDownConfirmed(), "down never confirmed");
    }

    @Test
    void unconfirmedCleanupIsReportedTruthfully() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        submitter.failAt(0, 1);
        submitter.failAt(1, 0);
        ProceedDiagnosticDelivery delivery = new ProceedDiagnosticDelivery(submitter);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.PROCEED));
        assertEquals(1, submitter.downStrokes(), "no second down");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertTrue(outcome.keyUpCleanupAttempted(), "cleanup attempted");
        assertFalse(outcome.keyUpConfirmed(), "unconfirmed cleanup never reported as release");
        assertFalse(outcome.complete(), "not complete");
    }

    @Test
    void nativeThrowStillTakesCleanupWithoutSecondDown() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        submitter.throwAt(0);
        ProceedDiagnosticDelivery delivery = new ProceedDiagnosticDelivery(submitter);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.PROCEED));
        assertEquals(1, submitter.downStrokes(), "one down attempt");
        assertEquals(2, submitter.upStrokes(), "batch up plus one cleanup release");
    }

    @Test
    void unexpectedRuntimeIsWrappedAndStillAttemptsKeyUp() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        submitter.throwRawAt(0);
        ProceedDiagnosticDelivery delivery = new ProceedDiagnosticDelivery(submitter);
        GameInputException error = assertThrows(GameInputException.class,
                () -> delivery.tap(GameControl.PROCEED));
        assertEquals(1, submitter.downStrokes(), "one down attempt");
        assertEquals(2, submitter.upStrokes(), "batch up plus one cleanup release");
        boolean hasCause = false;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof IllegalStateException) {
                hasCause = true;
            }
        }
        assertTrue(hasCause, "unexpected cause preserved: " + error);
    }

    @Test
    void nullControlRefusesWithoutSubmission() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        ProceedDiagnosticDelivery delivery = new ProceedDiagnosticDelivery(submitter);
        assertThrows(NullPointerException.class, () -> delivery.tap(null));
        assertTrue(submitter.batches().isEmpty(), "zero submissions");
    }

    /** Records every submission as an ordered batch; failures scripted per batch index. */
    private static final class RecordingSubmitter implements DiagnosticKeyStrokeSubmitter {
        private final List<List<DiagnosticKeyStroke>> batches = new ArrayList<>();
        private final Map<Integer, Integer> failDelivered = new HashMap<>();
        private final Set<Integer> throwInstead = new HashSet<>();
        private final Set<Integer> throwRawInstead = new HashSet<>();

        void failAt(int index, int delivered) {
            failDelivered.put(index, delivered);
        }

        void throwAt(int index) {
            throwInstead.add(index);
        }

        void throwRawAt(int index) {
            throwRawInstead.add(index);
        }

        List<List<DiagnosticKeyStroke>> batches() {
            return List.copyOf(batches);
        }

        long downStrokes() {
            return batches.stream().flatMap(List::stream)
                    .filter(stroke -> !stroke.keyUp()).count();
        }

        long upStrokes() {
            return batches.stream().flatMap(List::stream)
                    .filter(DiagnosticKeyStroke::keyUp).count();
        }

        @Override
        public int submit(DiagnosticKeyStroke... strokes) {
            int index = batches.size();
            batches.add(List.of(strokes));
            if (throwInstead.contains(index)) {
                throw new GameInputException("fake Tab submission failure at " + index);
            }
            if (throwRawInstead.contains(index)) {
                throw new IllegalStateException("fake unexpected Tab error at " + index);
            }
            return failDelivered.getOrDefault(index, strokes.length);
        }
    }
}
