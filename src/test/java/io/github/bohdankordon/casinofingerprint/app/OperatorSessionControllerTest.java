package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Session lifetime without windows or input: orderly stop, fault and abort
 * mapping, the permanent single-session lockout and the close handshake.
 * No fake here ever touches real input backends.
 */
class OperatorSessionControllerTest {
    private static final ArmedSessionConfig CONFIG =
            new ArmedSessionConfig(0, "GTA5_Enhanced.exe", EmergencyAbortKey.F4);

    @Test
    void quickSuccessWithoutStopRequestEndsFaulted() {
        RecordingListener listener = new RecordingListener();
        OperatorSessionController controller = new OperatorSessionController(
                quick(new LiveSessionRunner.SessionOutcome(0, false)),
                Runnable::run, listener);
        controller.startSession(CONFIG, Path.of("approot"), silent(), silent());
        assertTerminal(OperatorSessionState.FAULTED, controller, listener);
        assertFalse(controller.isReArmAllowed());
    }

    @Test
    void orderlyStopEndsStoppedAndLocksReArm() throws Exception {
        BlockingRunner runner = new BlockingRunner(
                new LiveSessionRunner.SessionOutcome(0, false));
        RecordingListener listener = new RecordingListener();
        OperatorSessionController controller = new OperatorSessionController(
                runner, Runnable::run, listener);
        controller.startSession(CONFIG, Path.of("approot"), silent(), silent());
        assertTrue(runner.started.await(5, TimeUnit.SECONDS), "Worker starts");
        assertEquals(OperatorSessionState.ARMED_WATCHING, controller.state());
        controller.requestStop();
        assertEquals(OperatorSessionState.STOPPING, controller.state());
        runner.release.countDown();
        assertTerminal(OperatorSessionState.STOPPED, controller, listener);
        assertEquals(List.of(OperatorSessionState.ARMED_WATCHING,
                OperatorSessionState.STOPPING, OperatorSessionState.STOPPED),
                listener.states);
        assertFalse(controller.isReArmAllowed());
        assertThrows(IllegalStateException.class, () -> controller.startSession(
                CONFIG, Path.of("approot"), silent(), silent()));
    }

    @Test
    void backendFailureEndsFaulted() {
        RecordingListener listener = new RecordingListener();
        OperatorSessionController controller = new OperatorSessionController(
                quick(new LiveSessionRunner.SessionOutcome(3, false)),
                Runnable::run, listener);
        controller.startSession(CONFIG, Path.of("approot"), silent(), silent());
        assertTerminal(OperatorSessionState.FAULTED, controller, listener);
    }

    @Test
    void runnerExceptionEndsFaulted() {
        LiveSessionRunner failing = (config, appRoot, out, err) -> {
            throw new IllegalStateException("simulated backend failure");
        };
        RecordingListener listener = new RecordingListener();
        OperatorSessionController controller =
                new OperatorSessionController(failing, Runnable::run, listener);
        controller.startSession(CONFIG, Path.of("approot"), silent(), silent());
        assertTerminal(OperatorSessionState.FAULTED, controller, listener);
    }

    @Test
    void latchedAbortEndsAborted() {
        RecordingListener listener = new RecordingListener();
        OperatorSessionController controller = new OperatorSessionController(
                quick(new LiveSessionRunner.SessionOutcome(3, true)),
                Runnable::run, listener);
        controller.startSession(CONFIG, Path.of("approot"), silent(), silent());
        assertTerminal(OperatorSessionState.ABORTED, controller, listener);
        assertFalse(controller.isReArmAllowed());
    }

    @Test
    void stopRequestWhileDisarmedIsANoOperation() {
        RecordingListener listener = new RecordingListener();
        OperatorSessionController controller = new OperatorSessionController(
                quick(new LiveSessionRunner.SessionOutcome(0, false)),
                Runnable::run, listener);
        controller.requestStop();
        assertEquals(OperatorSessionState.DISARMED, controller.state());
        assertTrue(listener.states.isEmpty());
    }

    @Test
    void doubleArmIsRefused() {
        RecordingListener listener = new RecordingListener();
        OperatorSessionController controller = new OperatorSessionController(
                new BlockingRunner(new LiveSessionRunner.SessionOutcome(0, false)),
                Runnable::run, listener);
        controller.startSession(CONFIG, Path.of("approot"), silent(), silent());
        assertThrows(IllegalStateException.class, () -> controller.startSession(
                CONFIG, Path.of("approot"), silent(), silent()));
        controller.requestStop();
    }

    @Test
    void windowCloseHandshake() throws Exception {
        BlockingRunner runner = new BlockingRunner(
                new LiveSessionRunner.SessionOutcome(0, false));
        RecordingListener listener = new RecordingListener();
        OperatorSessionController controller = new OperatorSessionController(
                runner, Runnable::run, listener);
        assertTrue(controller.onWindowClosing(), "Disarmed close disposes immediately");
        controller.startSession(CONFIG, Path.of("approot"), silent(), silent());
        assertTrue(runner.started.await(5, TimeUnit.SECONDS), "Worker starts");
        assertFalse(controller.onWindowClosing(), "Armed close waits for shutdown");
        assertEquals(OperatorSessionState.STOPPING, controller.state());
        runner.release.countDown();
        assertTerminal(OperatorSessionState.STOPPED, controller, listener);
        assertTrue(controller.isCloseReady(), "Disposal is safe after the worker ends");
    }

    private static void assertTerminal(OperatorSessionState expected,
            OperatorSessionController controller, RecordingListener listener) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (controller.state().isTerminal()) {
                break;
            }
            Thread.yield();
        }
        assertEquals(expected, controller.state());
        assertTrue(listener.states.contains(expected), listener.states.toString());
    }

    private static LiveSessionRunner quick(LiveSessionRunner.SessionOutcome outcome) {
        return (config, appRoot, out, err) -> outcome;
    }

    private static PrintStream silent() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    /** Fake runner blocking until the test releases it; never touches input. */
    private static final class BlockingRunner implements LiveSessionRunner {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final SessionOutcome outcome;

        BlockingRunner(SessionOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public SessionOutcome run(ArmedSessionConfig config, Path appRoot,
                PrintStream out, PrintStream err) {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    return new SessionOutcome(3, false);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return outcome;
        }
    }

    private static final class RecordingListener
            implements OperatorSessionController.Listener {
        final List<OperatorSessionState> states = new ArrayList<>();

        @Override
        public synchronized void onStateChanged(OperatorSessionState state) {
            states.add(state);
        }
    }
}
