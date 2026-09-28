package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

/**
 * The three lifecycle transitions of one hack.
 *
 * <p>Evaluation only. A hack has exactly two annotated rounds, so every hack contributes
 * {@code HACK_ENTRY -> ROUND_1}, {@code ROUND_1 -> ROUND_2} and {@code ROUND_2 -> HACK_EXIT}.
 * Two recordings, four hacks, twelve transitions.
 */
public enum TransitionKind {
    /** Ordinary gameplay to the first round of a hack. */
    HACK_ENTRY,
    /** The first round of a hack to its second round. */
    INTER_ROUND,
    /** The second round of a hack back to ordinary gameplay. */
    HACK_EXIT
}
