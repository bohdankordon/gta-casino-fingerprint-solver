package io.github.bohdankordon.casinofingerprint.recognition;

/**
 * Why a completed recognition pipeline stayed {@code UNCERTAIN} instead of recognizing the
 * puzzle. Multiple reasons may apply simultaneously; callers must not reverse-engineer the
 * cause from a single confidence number.
 */
public enum UncertaintyReason {
    /**
     * The best target score is below the policy minimum: the observed target does not look
     * enough like any known fingerprint.
     */
    TARGET_SCORE_TOO_LOW,
    /**
     * The top-two target separation is below the policy minimum: two known fingerprints explain
     * the observed target almost equally well.
     */
    TARGET_MARGIN_TOO_LOW,
    /**
     * The best assignment mean is below the policy minimum: the four selected pairs are jointly
     * too weak.
     */
    ASSIGNMENT_SCORE_TOO_LOW,
    /**
     * The weakest assigned pair is below the policy minimum: at least one of the four selected
     * pairs is too weak on its own.
     */
    ASSIGNED_PAIR_TOO_WEAK,
    /**
     * The best assignment and the best assignment with a DIFFERENT selected candidate set score
     * almost equally: the choice of which four tiles to click is ambiguous.
     */
    SELECTION_MARGIN_TOO_LOW,
    /**
     * At least one assigned fragment separates too weakly from its strongest other candidate:
     * the local pair evidence is ambiguous even though the global assignment is legal.
     */
    FRAGMENT_MARGIN_TOO_LOW
}

