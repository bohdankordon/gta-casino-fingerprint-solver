package io.github.bohdankordon.casinofingerprint.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Thread-safe tee routing the same live backend lines to the authoritative
 * on-disk session log and to the bounded Swing activity model.
 *
 * <p>The bridge reuses the existing PrintStream-oriented backend output: callers
 * hand the bridge streams to the session runner instead of System.out, so no
 * solver behavior is duplicated to create GUI messages. Every line is flushed
 * to disk immediately (line-level flush), so fault and crash tails are kept.
 * The disk file always receives every line while the UI model keeps only its
 * newest capped window. UTF-8 is used explicitly on both sides.
 *
 * <p>Ownership: the bridge owns its writer; close the bridge, not the streams
 * returned by printStream().
 */
public final class OperatorLogBridge implements AutoCloseable {
    private final java.io.BufferedWriter writer;
    private final BoundedLogModel uiModel;
    private boolean closed;

    /**
     * Opens (creating parent directories) the given session log file for appending.
     */
    public OperatorLogBridge(Path logFile, BoundedLogModel uiModel) throws IOException {
        Objects.requireNonNull(logFile, "logFile");
        this.uiModel = Objects.requireNonNull(uiModel, "uiModel");
        Path parent = logFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Writes one line to the disk log (flushed) and to the bounded UI model.
     */
    public synchronized void emit(String line) {
        if (closed) {
            throw new IllegalStateException("The log bridge is closed");
        }
        String kept = line == null ? "" : line;
        try {
            writer.write(kept);
            writer.newLine();
            writer.flush();
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not write the session log", failed);
        }
        uiModel.append(kept);
    }

    /**
     * A UTF-8 PrintStream splitting on line breaks and routing every complete line
     * through emit(). Do not close the returned stream; close this bridge instead.
     */
    public PrintStream printStream() {
        return new PrintStream(new LineSplitter(), true, StandardCharsets.UTF_8);
    }

    /** Closes the disk log. Idempotent. */
    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            writer.close();
        }
    }

    /** Splits a byte stream into lines without interpreting content. */
    private final class LineSplitter extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int oneByte) {
            if (oneByte == 10) {
                flushLine();
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
            if (buffer.size() > 0) {
                flushLine();
            }
        }

        @Override
        public void close() {
            flush();
        }

        private void flushLine() {
            String line = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            emit(line);
        }
    }
}
