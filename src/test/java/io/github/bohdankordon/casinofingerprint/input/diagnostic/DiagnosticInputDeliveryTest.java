package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.execution.FakeAbortSignal;
import io.github.bohdankordon.casinofingerprint.execution.FakeGameInputSink;
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
 * Stage 8C.2 delivery sequencing against fakes only: exact stroke order and representation per
 * mode, exactly one key-down per invocation, key-up safety after every failure, early release
 * on abort, and truthful outcomes. No test here can emit native input.
 */
class DiagnosticInputDeliveryTest {
    private static final int HOLD = DiagnosticDeliveryPlan.DEFAULT_HOLD_MILLIS;

    private final RecordingSubmitter submitter = new RecordingSubmitter();
    private final RecordingSleeper sleeper = new RecordingSleeper();
    private final FakeAbortSignal abort = FakeAbortSignal.calm();
    private final FakeGameInputSink baseline = new FakeGameInputSink();

    private DiagnosticInputDelivery delivery(DiagnosticInputDeliveryMode mode) {
        return new DiagnosticInputDelivery(mode, mode.holds() ? HOLD : 0, baseline, submitter,
                abort, sleeper);
    }

    @Test
    void vkBatchDelegatesToTheProductionBaselineExactlyOnce() {
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_BATCH);
        delivery.tap(GameControl.UP);
        assertEquals(List.of(GameControl.UP), baseline.taps(),
                "VK_BATCH is the production batch path itself");
        assertEquals(List.of(GameControl.UP), baseline.attempts(), "one baseline invocation");
        assertTrue(submitter.batches().isEmpty(), "no direct native submission in VK_BATCH");
        assertTrue(sleeper.sleeps().isEmpty(), "no sleep in VK_BATCH");
        assertEquals(0, abort.checks(), "no abort poll inside a batch tap");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertEquals(DiagnosticInputDeliveryMode.VK_BATCH, outcome.mode());
        assertTrue(outcome.complete(), "the baseline reported a complete batch");
        assertEquals(0, outcome.requestedHoldMillis(), "no hold was requested");
    }

    @Test
    void vkBatchBaselineFailureIsPropagatedWithoutRetryOrDirectSubmission() {
        baseline.refuseAtIndex(0);
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_BATCH);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.UP));
        assertEquals(1, baseline.attempts().size(), "one baseline invocation, no retry");
        assertTrue(submitter.batches().isEmpty(), "the baseline owns its own cleanup");
        assertFalse(delivery.lastOutcome().orElseThrow().complete(),
                "a failed batch is never reported as complete");
    }

    @Test
    void vkHoldSubmitsDownHoldUpExactlyOnceEach() {
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_HOLD);
        delivery.tap(GameControl.UP);
        DiagnosticKeyStroke down = DiagnosticKeyStroke.virtualKey(0x26, false);
        DiagnosticKeyStroke up = DiagnosticKeyStroke.virtualKey(0x26, true);
        assertEquals(List.of(List.of(down), List.of(up)), submitter.batches(),
                "the key-down and key-up are separate native submissions, in order");
        assertEquals(HOLD, sleeper.totalMillis(), "the explicit hold elapsed");
        assertTrue(sleeper.sleeps().stream()
                        .allMatch(step -> step <= DiagnosticInputDelivery.HOLD_POLL_MILLIS),
                "the hold polls in short steps: " + sleeper.sleeps());
        assertEquals(HOLD / DiagnosticInputDelivery.HOLD_POLL_MILLIS, abort.checks(),
                "the abort key is polled after every hold step");
        assertTrue(baseline.taps().isEmpty(), "hold modes never delegate to the batch baseline");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertEquals(HOLD, outcome.requestedHoldMillis());
        assertEquals(HOLD, outcome.actualHoldMillis());
        assertFalse(outcome.abortedDuringHold(), "the abort never fired");
        assertFalse(outcome.keyUpCleanupAttempted(), "no cleanup was needed");
        assertTrue(outcome.complete(), "down and up were confirmed");
    }

    @Test
    void scancodeHoldSubmitsTheMappedScanCodeStrokes() {
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.SCANCODE_HOLD);
        delivery.tap(GameControl.SELECT);
        ScanCodeSpec spec = ScanCodeSpec.forControl(GameControl.SELECT);
        assertEquals(List.of(List.of(DiagnosticKeyStroke.scanCode(spec, false)),
                        List.of(DiagnosticKeyStroke.scanCode(spec, true))), submitter.batches(),
                "SELECT uses the main Enter scan code for both strokes");
        assertTrue(submitter.allStrokes().stream()
                        .allMatch(stroke -> stroke.wVk() == 0 && stroke.usesScanCode()),
                "every scan-code stroke keeps wVk zero: " + submitter.allStrokes());
        assertTrue(delivery.lastOutcome().orElseThrow().complete());
    }

    @Test
    void scancodeBatchSubmitsOneOrderedBatchAndNeverSleeps() {
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.SCANCODE_BATCH);
        delivery.tap(GameControl.RIGHT);
        assertEquals(1, submitter.batches().size(), "exactly one native batch");
        List<DiagnosticKeyStroke> batch = submitter.batches().get(0);
        assertEquals(2, batch.size(), "key-down plus key-up");
        assertFalse(batch.get(0).keyUp(), "the key-down comes first");
        assertTrue(batch.get(1).keyUp(), "the key-up comes second");
        assertTrue(batch.get(0).extended(), "arrow-right is an extended E0 key");
        assertEquals(0, batch.get(0).wVk(), "scan-code strokes ignore wVk");
        assertTrue(sleeper.sleeps().isEmpty(), "BATCH modes never sleep");
        assertEquals(0, abort.checks(), "BATCH modes never poll mid-batch");
        assertTrue(delivery.lastOutcome().orElseThrow().complete());
        assertTrue(baseline.taps().isEmpty(),
                "only VK_BATCH delegates to the production baseline");
    }

    @Test
    void aPartiallyDeliveredBatchAttemptsAKeyUpCleanupAndNeverRetries() {
        submitter.failAt(0, 1);
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.SCANCODE_BATCH);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.UP));
        assertEquals(2, submitter.batches().size(), "the batch plus one cleanup release");
        assertEquals(1, submitter.downStrokes(), "no second key-down");
        assertEquals(2, submitter.upStrokes(), "the batch key-up plus the cleanup release");
        assertTrue(submitter.batches().get(1).get(0).keyUp(), "the cleanup is a release");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertFalse(outcome.keyDownConfirmed(), "a partial batch is never confirmed");
        assertTrue(outcome.keyUpCleanupAttempted(), "the cleanup is recorded");
        assertTrue(outcome.keyUpConfirmed(), "the cleanup release was confirmed");
    }

    @Test
    void keyDownFailureNeverRetriesAndNeverSendsASecondDown() {
        submitter.failAt(0, 0);
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_HOLD);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.UP));
        assertEquals(1, submitter.downStrokes(), "exactly one key-down attempt, never retried");
        assertEquals(1, submitter.upStrokes(), "one conservative key-up cleanup followed");
        assertEquals(2, submitter.batches().size());
        assertTrue(submitter.batches().get(1).get(0).keyUp(), "the second submission is a release");
    }

    @Test
    void aDownThatThrowsStillTakesTheCleanupPathWithoutASecondDown() {
        submitter.throwAt(0);
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_HOLD);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.UP));
        assertEquals(1, submitter.downStrokes(), "one key-down attempt");
        assertEquals(1, submitter.upStrokes(), "the unknown key state still triggers one release");
    }

    @Test
    void anUnexpectedRuntimeErrorIsWrappedAndStillAttemptsTheKeyUp() {
        submitter.throwRawAt(0);
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_HOLD);
        GameInputException error = assertThrows(GameInputException.class,
                () -> delivery.tap(GameControl.UP));
        assertEquals(1, submitter.downStrokes(), "one key-down attempt");
        assertEquals(1, submitter.upStrokes(), "the key-up cleanup still ran");
        assertTrue(hasCause(error, IllegalStateException.class),
                "the unexpected native cause is preserved in the chain: " + error);
    }

    @Test
    void anInterruptedHoldStillAttemptsTheKeyUp() {
        sleeper.interruptAll();
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_HOLD);
        GameInputException error = assertThrows(GameInputException.class,
                () -> delivery.tap(GameControl.DOWN));
        assertEquals(1, submitter.downStrokes(), "one key-down attempt");
        assertEquals(List.of(List.of(DiagnosticKeyStroke.virtualKey(0x28, false)),
                        List.of(DiagnosticKeyStroke.virtualKey(0x28, true))), submitter.batches(),
                "the key-up is still submitted after the interruption");
        assertTrue(error.getMessage().contains("key-up"), error.getMessage());
        assertTrue(Thread.interrupted(), "the interrupt is preserved on the thread");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertTrue(outcome.keyDownConfirmed(), "the key-down was confirmed");
        assertTrue(outcome.keyUpConfirmed(), "the cleanup release was confirmed");
        assertTrue(outcome.keyUpCleanupAttempted(), "the cleanup path ran");
        assertTrue(outcome.complete(), "recorded truthfully");
    }

    @Test
    void aFailedKeyUpAttemptsAnAdditionalCleanupAndNeverASecondDown() {
        submitter.failAt(1, 0);
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_HOLD);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.LEFT));
        assertEquals(3, submitter.batches().size(), "down, failed up, cleanup up");
        assertEquals(1, submitter.downStrokes(), "no second key-down");
        assertEquals(2, submitter.upStrokes(), "the failed release plus one cleanup release");
        assertTrue(submitter.batches().get(2).get(0).keyUp(), "the cleanup is a release");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertTrue(outcome.keyDownConfirmed(), "the key-down was confirmed");
        assertTrue(outcome.keyUpCleanupAttempted(), "the cleanup is recorded");
        assertTrue(outcome.keyUpConfirmed(), "the extra cleanup release was confirmed");
    }

    @Test
    void anUnconfirmedKeyUpCleanupIsReportedTruthfully() {
        submitter.failAt(1, 0);
        submitter.failAt(2, 0);
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.SCANCODE_HOLD);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.UP));
        assertEquals(3, submitter.batches().size());
        assertEquals(1, submitter.downStrokes(), "no second key-down");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertTrue(outcome.keyUpCleanupAttempted(), "the cleanup was attempted");
        assertFalse(outcome.keyUpConfirmed(),
                "an unconfirmed cleanup must never be reported as a release");
        assertFalse(outcome.complete(), "the tap is not complete");
    }

    @Test
    void aNativeFailureDuringTheKeyUpStillTakesTheCleanupPath() {
        submitter.throwAt(1);
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_HOLD);
        assertThrows(GameInputException.class, () -> delivery.tap(GameControl.RIGHT));
        assertEquals(3, submitter.batches().size(), "down, failed up, cleanup up");
        assertEquals(1, submitter.downStrokes(), "no second key-down");
        assertTrue(delivery.lastOutcome().orElseThrow().keyUpCleanupAttempted());
    }

    @Test
    void anAbortDuringTheHoldReleasesTheKeyEarlyAndStillSendsTheKeyUp() {
        submitter.afterSubmit(0, abort::fire);
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.VK_HOLD);
        delivery.tap(GameControl.UP);
        assertEquals(List.of(DiagnosticInputDelivery.HOLD_POLL_MILLIS), sleeper.sleeps(),
                "the abort cut the hold short after the first poll step");
        assertEquals(List.of(List.of(DiagnosticKeyStroke.virtualKey(0x26, false)),
                        List.of(DiagnosticKeyStroke.virtualKey(0x26, true))), submitter.batches(),
                "the key-up was still submitted after the abort");
        DiagnosticDeliveryOutcome outcome = delivery.lastOutcome().orElseThrow();
        assertTrue(outcome.abortedDuringHold(), "the early release is recorded");
        assertEquals(DiagnosticInputDelivery.HOLD_POLL_MILLIS, outcome.actualHoldMillis(),
                "the recorded hold is what actually happened");
        assertTrue(outcome.complete(), "the tap was still fully submitted");
    }

    @Test
    void oneTapCallSendsExactlyOneLogicalTap() {
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.SCANCODE_HOLD);
        delivery.tap(GameControl.LEFT);
        assertEquals(1, submitter.downStrokes(), "one key-down");
        assertEquals(1, submitter.upStrokes(), "one key-up");
        assertEquals(2, submitter.batches().size(), "exactly two native submissions");
    }

    @Test
    void proceedIsRefusedBeforeAnyNativeSubmission() {
        DiagnosticInputDelivery delivery = delivery(DiagnosticInputDeliveryMode.SCANCODE_HOLD);
        assertThrows(IllegalArgumentException.class, () -> delivery.tap(GameControl.PROCEED));
        assertTrue(submitter.batches().isEmpty(), "zero native submissions");
        assertTrue(baseline.taps().isEmpty(), "zero baseline taps");
        assertTrue(delivery.lastOutcome().isEmpty(), "no delivery outcome exists");
    }

    @Test
    void theConstructorRejectsAHoldThatContradictsTheMode() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticInputDelivery(
                DiagnosticInputDeliveryMode.VK_BATCH, 50, baseline, submitter, abort, sleeper));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticInputDelivery(
                DiagnosticInputDeliveryMode.VK_HOLD, 5, baseline, submitter, abort, sleeper));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticInputDelivery(
                DiagnosticInputDeliveryMode.SCANCODE_HOLD, 201, baseline, submitter, abort,
                sleeper));
        assertFalse(new DiagnosticInputDelivery(DiagnosticInputDeliveryMode.SCANCODE_HOLD, 200,
                baseline, submitter, abort, sleeper).lastOutcome().isPresent(),
                "a valid HOLD construction delivers nothing by itself");
    }

    /** True when the given throwable or one of its causes is an instance of the given type. */
    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    /** Records every submission as an ordered batch; failures are scripted per batch index. */
    private static final class RecordingSubmitter implements DiagnosticKeyStrokeSubmitter {
        private final List<List<DiagnosticKeyStroke>> batches = new ArrayList<>();
        private final Map<Integer, Integer> failDelivered = new HashMap<>();
        private final Set<Integer> throwInstead = new HashSet<>();
        private final Set<Integer> throwRawInstead = new HashSet<>();
        private final Map<Integer, Runnable> hooks = new HashMap<>();

        void failAt(int index, int delivered) {
            failDelivered.put(index, delivered);
        }

        void throwAt(int index) {
            throwInstead.add(index);
        }

        void throwRawAt(int index) {
            throwRawInstead.add(index);
        }

        void afterSubmit(int index, Runnable hook) {
            hooks.put(index, hook);
        }

        List<List<DiagnosticKeyStroke>> batches() {
            return List.copyOf(batches);
        }

        List<DiagnosticKeyStroke> allStrokes() {
            return batches.stream().flatMap(List::stream).toList();
        }

        long downStrokes() {
            return allStrokes().stream().filter(stroke -> !stroke.keyUp()).count();
        }

        long upStrokes() {
            return allStrokes().stream().filter(DiagnosticKeyStroke::keyUp).count();
        }

        @Override
        public int submit(DiagnosticKeyStroke... strokes) {
            int index = batches.size();
            batches.add(List.of(strokes));
            Runnable hook = hooks.get(index);
            if (hook != null) {
                hook.run();
            }
            if (throwInstead.contains(index)) {
                throw new GameInputException("fake native submission failure at " + index);
            }
            if (throwRawInstead.contains(index)) {
                throw new IllegalStateException("fake unexpected native error at " + index);
            }
            return failDelivered.getOrDefault(index, strokes.length);
        }
    }

    /** Records hold sleeps and can make every sleep throw an interrupt. */
    private static final class RecordingSleeper implements DiagnosticHoldSleeper {
        private final List<Long> sleeps = new ArrayList<>();
        private boolean interrupt;

        void interruptAll() {
            interrupt = true;
        }

        List<Long> sleeps() {
            return List.copyOf(sleeps);
        }

        long totalMillis() {
            return sleeps.stream().mapToLong(Long::longValue).sum();
        }

        @Override
        public void sleepMillis(long millis) throws InterruptedException {
            if (interrupt) {
                throw new InterruptedException("fake hold sleeper interrupted");
            }
            sleeps.add(millis);
        }
    }
}
