package io.github.bohdankordon.casinofingerprint.app;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * One armed live-solver execution behind the operator UI.
 *
 * <p>The runner blocks until the session ends (orderly stop, latched fault or
 * emergency abort) and never touches Swing: the operator controller runs it on a
 * dedicated worker thread and marshals state changes back to the event dispatch
 * thread. Test fakes implement this interface; no test ever sends real input.
 */
public interface LiveSessionRunner {
    /** How one armed session ended. */
    record SessionOutcome(int exitCode, boolean aborted) {
    }

    /**
     * Runs one armed session with the validated backend.
     *
     * @param config immutable armed configuration from the explicit ARM flow
     * @param appRoot packaged runtime-data root resolving dataset and fixtures
     * @param out session activity stream (disk and UI tee, never System.out)
     * @param err session error stream (disk and UI tee, never System.err)
     * @return how the session ended; aborted is true when the emergency abort path
     *         latched, so the controller can report ABORTED instead of FAULTED
     */
    SessionOutcome run(ArmedSessionConfig config, Path appRoot, PrintStream out,
            PrintStream err);
}
