package io.github.bohdankordon.casinofingerprint.execution;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;

/**
 * Fresh visual control-state readings for the guarded executor.
 *
 * <p>Every {@link #poll} must observe a newly captured frame: re-reading one stale frame
 * would turn polling into a delay loop and let timeouts masquerade as truth. Production
 * captures and detects per call; tests script deterministic sequences.
 */
public interface ControlStateSource {
    /** Reads the current visual control state from a freshly captured frame. */
    PuzzleControlState poll();
}
