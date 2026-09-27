package io.github.bohdankordon.casinofingerprint.runtime;

/**
 * Runtime state of the recognition-only live loop.
 *
 * <p>These states describe what the runtime knows about the desktop. They are never gameplay
 * commands: no state means "press a key", "click a tile" or "solve the puzzle", and no state
 * carries an input action.
 */
public enum LiveRecognitionState {
    /** Nothing has been captured yet. */
    WAITING,
    /** The captured frame is not the supported physical layout size; no decision was taken. */
    UNSUPPORTED_FRAME,
    /** The capture backend failed for this frame; no frame and no decision were produced. */
    CAPTURE_ERROR,
    /** A frame was recognized but stayed uncertain. */
    UNCERTAIN,
    /** A single frame was recognized; not a consensus-confirmed live result (--once mode only). */
    RECOGNIZED,
    /** Consecutive identical recognized answers, below the required stability count. */
    CANDIDATE_RECOGNITION,
    /** The required number of consecutive identical recognized answers was reached. */
    STABLE_RECOGNIZED
}
