package io.github.bohdankordon.casinofingerprint.capture;

import java.util.List;

/**
 * Discovers the attached monitors.
 *
 * <p>Indexes are a stable runtime concept: they number the monitors in the order the backend
 * reports them and are what {@code --monitor <index>} selects. The AWT backend is the production
 * implementation; tests supply an in-memory enumeration so monitor selection needs no display.
 */
@FunctionalInterface
public interface MonitorEnumerator {
    /** Attached monitors in a stable index order; empty when no monitor was found. */
    List<MonitorInfo> enumerate();
}
