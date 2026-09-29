package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

/**
 * When the solver prediction arrived relative to the human player's first observed selection.
 *
 * <p>The intended solver works before the first Enter: only {@link #ON_TIME} counts as the
 * intended production success. A late prediction may still be diagnostically correct, but it
 * is never reported as equivalent.
 */
public enum ExternalPredictionTiming {
    /** The prediction arrived strictly before the first observed selection. */
    ON_TIME,
    /** The prediction arrived after the human selection had already begun. */
    LATE,
    /** No prediction exists, so timing does not apply. */
    NONE
}
