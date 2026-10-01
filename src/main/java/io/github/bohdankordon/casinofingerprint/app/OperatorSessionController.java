package io.github.bohdankordon.casinofingerprint.app;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Explicit operator session lifetime over the validated live backend.
 *
 * <p>The controller owns the DISARMED to terminal-state machine and the dedicated
 * worker thread running the live watch loop. The event dispatch thread is never
 * blocked and never runs solver work: state notifications are marshalled through
 * the injected executor (SwingUtilities.invokeLater in production, a direct
 * executor in tests).
 *
 * <p>Safety model, mirroring the backend explicit-restart rule: once an armed
 * session has ended for any reason (STOPPED, FAULTED or ABORTED) this controller
 * never arms again. A new process is the only reset boundary, so there is no
 * reset, re-arm or retry path here.
 */
public final class OperatorSessionController {
    /** Receives state changes on the injected executor. */
    public interface Listener {
        /** Called once per transition, in transition order. */
        void onStateChanged(OperatorSessionState state);
    }

    private final LiveSessionRunner runner;
    private final Executor eventThread;
    private final Listener listener;
    private final Object lock = new Object();

    private OperatorSessionState state = OperatorSessionState.DISARMED;
    private boolean sessionTerminated;
    private boolean stopRequested;
    private boolean closeRequested;
    private Thread worker;

    /**
     * @param runner live backend execution, real or fake; never sends input unless
     *        an armed session actually runs it
     * @param eventThread marshals listener notifications (SwingUtilities.invokeLater
     *        in production)
     * @param listener UI observer, always notified on the eventThread executor
     */
    public OperatorSessionController(LiveSessionRunner runner, Executor eventThread,
            Listener listener) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.eventThread = Objects.requireNonNull(eventThread, "eventThread");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Current high-level state. */
    public OperatorSessionState state() {
        synchronized (lock) {
            return state;
        }
    }

    /** False once any armed session ended in this process: re-arm is then refused. */
    public boolean isReArmAllowed() {
        synchronized (lock) {
            return !sessionTerminated;
        }
    }

    /**
     * Arms one live session on a dedicated daemon worker thread. Requires the
     * DISARMED state and no previously terminated session.
     */
    public void startSession(ArmedSessionConfig config, Path appRoot, PrintStream out,
            PrintStream err) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(appRoot, "appRoot");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Thread started;
        synchronized (lock) {
            if (sessionTerminated) {
                throw new IllegalStateException(
                        "A live session already ended in this process;"
                                + " restart the application to arm again");
            }
            if (state != OperatorSessionState.DISARMED) {
                throw new IllegalStateException(
                        "startSession requires the DISARMED state, got " + state);
            }
            setStateLocked(OperatorSessionState.ARMED_WATCHING);
            started = new Thread(() -> runSession(config, appRoot, out, err),
                    "operator-live-session");
            started.setDaemon(true);
            worker = started;
            started.start();
        }
    }

    /**
     * Requests orderly shutdown: transitions ARMED_WATCHING to STOPPING and
     * interrupts the worker. Never blocks waiting for the worker. The physical
     * emergency abort key stays the immediate input-stop path; this button only
     * requests orderly solver shutdown.
     */
    public void requestStop() {
        Thread current;
        synchronized (lock) {
            if (state != OperatorSessionState.ARMED_WATCHING) {
                return;
            }
            setStateLocked(OperatorSessionState.STOPPING);
            stopRequested = true;
            current = worker;
        }
        if (current != null) {
            current.interrupt();
        }
    }

    /**
     * Window-close policy while a session is active.
     *
     * @return true when the window may dispose immediately (DISARMED or a terminal
     *         state); false while the worker still owns shutdown (ARMED_WATCHING
     *         or STOPPING). A close during STOPPING records the deferred close
     *         request without triggering another stop transition or interrupt, so
     *         disposal becomes safe as soon as the worker reaches a terminal state.
     */
    public boolean onWindowClosing() {
        synchronized (lock) {
            if (state == OperatorSessionState.STOPPING) {
                closeRequested = true;
                return false;
            }
            if (state != OperatorSessionState.ARMED_WATCHING) {
                return true;
            }
            closeRequested = true;
        }
        requestStop();
        return false;
    }

    /**
     * True when a close was requested while the session was active (ARMED_WATCHING
     * or STOPPING) and the worker has since ended.
     */
    public boolean isCloseReady() {
        synchronized (lock) {
            return closeRequested && state.isTerminal();
        }
    }

    private void runSession(ArmedSessionConfig config, Path appRoot, PrintStream out,
            PrintStream err) {
        LiveSessionRunner.SessionOutcome outcome;
        try {
            outcome = runner.run(config, appRoot, out, err);
        } catch (Exception failed) {
            err.println("LIVE: SESSION_ERROR: " + failed);
            outcome = new LiveSessionRunner.SessionOutcome(3, false);
        }
        finish(outcome);
    }

    private void finish(LiveSessionRunner.SessionOutcome outcome) {
        synchronized (lock) {
            OperatorSessionState end;
            if (stopRequested && outcome.exitCode() == 0 && !outcome.aborted()) {
                end = OperatorSessionState.STOPPED;
            } else if (outcome.aborted()) {
                end = OperatorSessionState.ABORTED;
            } else {
                end = OperatorSessionState.FAULTED;
            }
            sessionTerminated = true;
            stopRequested = false;
            setStateLocked(end);
        }
    }

    private void setStateLocked(OperatorSessionState next) {
        state = next;
        eventThread.execute(() -> listener.onStateChanged(next));
    }
}
