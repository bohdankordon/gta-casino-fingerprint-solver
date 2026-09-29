package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

/**
 * Evaluation-only puzzle-panel presence state: whether the fingerprint panel is genuinely
 * present in the captured frame, independent of solver recognition.
 *
 * <p>Present means static panel chrome is confidently visible; absent means the panel has
 * clearly left (HACK SUCCESS overlay, ordinary gameplay, walls, character, etc.); ambiguous
 * means the frame cannot prove either way. Ambiguous and absent both preserve tracker state:
 * no control reading while not present may mutate the human selection attempt.
 *
 * <p>Presence never uses the predicted fingerprint id, the correct candidate set, solver
 * confidence, or the selected-set result itself: that would risk circular benchmark
 * validation. Recognition status may be logged diagnostically elsewhere but never defines
 * presence.
 */
public enum ExternalPanelPresence {
    /** Static panel chrome confidently visible; control readings may mutate attempts. */
    PRESENT,
    /** Panel clearly absent or obscured by HACK SUCCESS/gameplay; freeze, ignore control. */
    ABSENT,
    /** Cannot prove present or absent; preserve prior state, invent nothing. */
    AMBIGUOUS
}
