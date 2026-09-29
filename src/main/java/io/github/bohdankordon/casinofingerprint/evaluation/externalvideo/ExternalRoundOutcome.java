package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

public enum ExternalRoundOutcome {
    MATCH,
    MISMATCH,
    NO_PREDICTION,
    LATE_PREDICTION,
    NEEDS_REVIEW_FINAL_EXIT,
    NEEDS_REVIEW_AMBIGUOUS,
    INCOMPLETE,
    ORPHAN_PREDICTION
}
