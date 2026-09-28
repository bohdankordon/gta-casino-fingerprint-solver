package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

/**
 * Evaluation scope of one witness frame row. The scope is OFFLINE evaluation metadata derived from
 * the human annotations and the production consensus stream; the witness itself never reads it.
 *
 * <p>Scopes answer the two questions this stage has to keep apart: what the same round looks like
 * while the player interacts with it, and what a real round transition looks like.
 */
public enum WitnessScope {
    /** Frames of the padded hack window before the round-1 consumption point: no panel, partial panel. */
    ENTRY,
    /** The exact frame a future orchestration would consume: the frozen baseline frame itself. */
    BASELINE,
    /** Frames of a round after its consumption point and before its observed end. */
    SAME_ROUND,
    /** Frames from the first new recognized answer through the first new stable answer. */
    TRANSITION,
    /**
     * A bounded window after the first new stable answer, still compared to the OLD consumed
     * baseline, so persistence of the content change can be measured.
     */
    TRANSITION_OBSERVATION,
    /** Frames after the last stable answer of the last round of a hack: panel clearing, overlays. */
    EXIT,
    /** Deterministic negative control: the frozen baseline frame compared to itself. */
    EXACT_REPEAT_CONTROL
}
