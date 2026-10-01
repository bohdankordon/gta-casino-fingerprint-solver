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
 */
public final class BoundedLogModel {
    /** Newest lines retained for the activity view. */
    public static final int MAX_LINES = 2000;
    /** Single lines longer than this are truncated with a marker. */
    public static final int MAX_LINE_CHARS = 4000;

    private final Deque<String> lines = new ArrayDeque<>();

    /** Appends one line, dropping the oldest lines past the cap. */
    public synchronized void append(String line) {
        String kept = line == null ? "" : line;
        if (kept.length() > MAX_LINE_CHARS) {
            kept = kept.substring(0, MAX_LINE_CHARS) + " [truncated]";
        }
        lines.addLast(kept);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
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
