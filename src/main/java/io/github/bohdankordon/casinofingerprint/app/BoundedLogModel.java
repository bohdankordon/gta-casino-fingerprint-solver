package io.github.bohdankordon.casinofingerprint.app;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory line buffer behind the operator activity view.
 *
 * <p>The Swing document must never grow indefinitely, so the visible activity
 * stream keeps only the newest lines. The authoritative on-disk session log is
 * unaffected: truncation here never removes anything from the file.
 *
 * <p>Every append advances a monotonically increasing absolute cursor, so a UI
 * consumer tracks what it has already shown by cursor value instead of by the
 * current retained size. A consumer that fell more than one window behind
 * resynchronizes to the newest retained window instead of replaying a stale
 * prefix or freezing once the cap is reached.
 */
public final class BoundedLogModel {
    /** Newest lines retained for the activity view. */
    public static final int MAX_LINES = 2000;
    /** Single lines longer than this are truncated with a marker. */
    public static final int MAX_LINE_CHARS = 4000;

    /**
     * Lines delivered to a UI consumer since its cursor, with the cursor the
     * consumer must store for the next call.
     */
    public record LogBatch(long nextCursor, List<String> lines, boolean resynchronized) {
    }

    private final Deque<String> lines = new ArrayDeque<>();
    private long totalAppended;

    /** Appends one line, dropping the oldest lines past the cap. */
    public synchronized void append(String line) {
        String kept = line == null ? "" : line;
        if (kept.length() > MAX_LINE_CHARS) {
            kept = kept.substring(0, MAX_LINE_CHARS) + " [truncated]";
        }
        lines.addLast(kept);
        totalAppended++;
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    /**
     * Lines appended after {@code cursor}, oldest first, plus the cursor value
     * for the next call. A cursor at or past the newest line yields an empty
     * batch. A cursor older than the retained window yields the whole newest
     * window with {@code resynchronized} set, so a lagging consumer rebuilds
     * instead of missing lines forever.
     */
    public synchronized LogBatch entriesSince(long cursor) {
        long oldestRetained = totalAppended - lines.size();
        if (cursor >= totalAppended) {
            return new LogBatch(totalAppended, List.of(), false);
        }
        if (cursor < oldestRetained) {
            return new LogBatch(totalAppended, List.copyOf(lines), true);
        }
        int skip = (int) (cursor - oldestRetained);
        return new LogBatch(totalAppended,
                List.copyOf(lines).subList(skip, lines.size()), false);
    }

    /** Total lines ever appended; the absolute cursor for UI consumers. */
    public synchronized long totalAppended() {
        return totalAppended;
    }

    /** Newest-capped snapshot, oldest first. */
    public synchronized List<String> snapshot() {
        return List.copyOf(lines);
    }

    /** Current retained line count. */
    public synchronized int size() {
        return lines.size();
    }

    /** Discards all retained lines. */
    public synchronized void clear() {
        lines.clear();
    }
}