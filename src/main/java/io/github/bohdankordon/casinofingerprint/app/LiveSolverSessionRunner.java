package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.capture.AwtMonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.AwtScreenCapture;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production LiveSessionRunner: the exact validated LiveSolverMain backend with
 * the GUI-collected ArmedSessionConfig.
 *
 * <p>No second solver implementation lives here. The runner converts the armed
 * configuration through LiveSolverOptions validation and invokes
 * LiveSolverMain.run with the production capture backend, the foreground guard,
 * the emergency abort signal, SCANCODE_BATCH delivery and every existing safety
 * gate. Stopping works through worker-thread interruption, which the backend
 * watch loop already honors.
 */
public final class LiveSolverSessionRunner implements LiveSessionRunner {
    @Override
    public SessionOutcome run(ArmedSessionConfig config, Path appRoot, PrintStream out,
            PrintStream err) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(appRoot, "appRoot");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        AtomicBoolean aborted = new AtomicBoolean(false);
        PrintStream sniffing =
                new PrintStream(new AbortSniffer(out, aborted), true, StandardCharsets.UTF_8);
        int exit = LiveSolverMain.run(config.toLiveSolverOptions(), sniffing, err,
                AwtMonitorEnumerator.create(), AwtScreenCapture::forMonitor, appRoot);
        sniffing.flush();
        return new SessionOutcome(exit, aborted.get());
    }

    /**
     * Forwards every byte to the session stream while watching line content for the
     * backend ABORTED latch, so the controller can distinguish the emergency abort
     * outcome from a plain fault without parsing log files afterwards.
     */
    private static final class AbortSniffer extends OutputStream {
        private final PrintStream delegate;
        private final AtomicBoolean aborted;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        AbortSniffer(PrintStream delegate, AtomicBoolean aborted) {
            this.delegate = delegate;
            this.aborted = aborted;
        }

        @Override
        public synchronized void write(int oneByte) {
            delegate.write(oneByte);
            if (oneByte == 10) {
                checkLine();
            } else if (oneByte != 13) {
                buffer.write(oneByte);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            for (int index = 0; index < length; index++) {
                write(bytes[offset + index] & 0xFF);
            }
        }

        @Override
        public synchronized void flush() {
            checkLine();
            delegate.flush();
        }

        private void checkLine() {
            if (buffer.size() > 0) {
                String line = buffer.toString(StandardCharsets.UTF_8);
                buffer.reset();
                if (line.contains("ABORTED")) {
                    aborted.set(true);
                }
            }
        }
    }
}
