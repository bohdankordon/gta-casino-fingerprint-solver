package io.github.bohdankordon.casinofingerprint.input;

/**
 * One gameplay input intention Stage 7B may send to GTA through {@link GameInputSink}.
 *
 * <p>These are DOMAIN intentions, the execution-side mirror of the Stage 7A abstract actions:
 * arrow moves for navigation, Enter to confirm the focused candidate, Tab to advance the
 * round after all four selections. The project game-control contract fixes the mapping:
 * UP/DOWN/LEFT/RIGHT to the arrow keys, SELECT to Enter, PROCEED to Tab. The Tab mapping is
 * part of that contract, not a fact inferred from any recording.
 */
public enum GameControl {
    /** One step up: the arrow-up key. */
    UP,
    /** One step down: the arrow-down key. */
    DOWN,
    /** One step left: the arrow-left key. */
    LEFT,
    /** One step right: the arrow-right key. */
    RIGHT,
    /** Confirm the focused candidate: the Enter key. */
    SELECT,
    /** Advance the round after all four selections: the Tab key. */
    PROCEED
}
