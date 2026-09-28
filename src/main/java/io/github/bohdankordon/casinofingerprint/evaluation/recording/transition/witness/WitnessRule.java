package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import java.util.Locale;
import java.util.Objects;

/**
 * One candidate transition rule over {@link PuzzleContentFeatures}, with its analysis threshold.
 *
 * <p>Evaluation only. A rule reads the features of a frame and nothing else: it has no access to
 * the answer identity, the fingerprint, the selected candidate set, the consensus state or the
 * evaluation scope of a row. Two frames with identical features therefore produce identical rule
 * outcomes no matter what identity metadata is attached to them, which is exactly the property the
 * counterfactual same-identity replay relies on.
 *
 * <p>No rule in this class is a production threshold. The whole sweep is measured and reported;
 * this stage promotes none of it.
 *
 * @param id stable identifier used in artifacts, for example {@code W3_REGIONS_LT_0.800_K6}
 * @param form predicate shape
 * @param threshold similarity cut (lower means more change) or delta cut (higher means more
 *        change), depending on the form
 * @param parameter count parameter {@code K} of the {@code *_AT_LEAST} forms, otherwise zero
 * @param description human-readable one-liner
 */
public record WitnessRule(String id, Form form, double threshold, int parameter,
        String description) {

    /** Predicate shapes evaluated by this stage. */
    public enum Form {
        /** W1 only: the target region alone changed. Expected to miss same-target rounds. */
        TARGET_SIMILARITY_BELOW,
        /** W2 only: the weakest same-position candidate changed. */
        MIN_CANDIDATE_SIMILARITY_BELOW,
        /** W2: at least {@code K} of eight same-position candidates changed. */
        CHANGED_CANDIDATE_COUNT_AT_LEAST,
        /** W3: the weakest of the nine regions changed. */
        MIN_REGION_SIMILARITY_BELOW,
        /** W3: the mean of the nine regions changed. */
        MEAN_REGION_SIMILARITY_BELOW,
        /** W3: at least {@code K} of the nine regions changed. */
        CHANGED_REGION_COUNT_AT_LEAST,
        /** W3: the target changed OR at least {@code K} candidates changed. */
        TARGET_OR_CHANGED_CANDIDATES,
        /** W0 control: raw panel grayscale difference above the cut. */
        RAW_PANEL_DELTA_ABOVE,
        /** W4: the target score vector moved. */
        TARGET_VECTOR_MAX_DELTA_ABOVE,
        /** W4: the fixed-reference fragment grid moved on average. */
        GRID_MEAN_DELTA_ABOVE
    }

    public WitnessRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(description, "description");
        if (!Double.isFinite(threshold) || threshold < 0.0) {
            throw new IllegalArgumentException(
                    "threshold must be finite and non-negative, got " + threshold);
        }
        if (parameter < 0) {
            throw new IllegalArgumentException("parameter must not be negative, got " + parameter);
        }
        switch (form) {
            case TARGET_OR_CHANGED_CANDIDATES -> {
                if (threshold > 1.0) {
                    throw new IllegalArgumentException("a similarity threshold cannot exceed 1.0");
                }
                if (parameter < 1 || parameter > RegionContentSimilarity.CANDIDATE_COUNT) {
                    throw new IllegalArgumentException(
                            "parameter must be a candidate count 1.."
                                    + RegionContentSimilarity.CANDIDATE_COUNT + ", got " + parameter);
                }
            }
            case CHANGED_CANDIDATE_COUNT_AT_LEAST -> {
                if (threshold > 1.0) {
                    throw new IllegalArgumentException("a similarity threshold cannot exceed 1.0");
                }
                if (parameter < 1 || parameter > RegionContentSimilarity.CANDIDATE_COUNT) {
                    throw new IllegalArgumentException(
                            "parameter must be a candidate count 1.."
                                    + RegionContentSimilarity.CANDIDATE_COUNT + ", got " + parameter);
                }
            }
            case CHANGED_REGION_COUNT_AT_LEAST -> {
                if (threshold > 1.0) {
                    throw new IllegalArgumentException("a similarity threshold cannot exceed 1.0");
                }
                if (parameter < 1 || parameter > RegionContentSimilarity.CANDIDATE_COUNT + 1) {
                    throw new IllegalArgumentException("parameter must be a region count 1.."
                            + (RegionContentSimilarity.CANDIDATE_COUNT + 1) + ", got " + parameter);
                }
            }
            case TARGET_SIMILARITY_BELOW, MIN_CANDIDATE_SIMILARITY_BELOW,
                    MIN_REGION_SIMILARITY_BELOW, MEAN_REGION_SIMILARITY_BELOW -> {
                if (threshold > 1.0) {
                    throw new IllegalArgumentException("a similarity threshold cannot exceed 1.0");
                }
            }
            default -> {
                // delta forms: any non-negative cut is meaningful
            }
        }
    }

    /** True when this rule fires on the features of {@code row}. */
    public boolean fires(WitnessFrameRow row) {
        Objects.requireNonNull(row, "row");
        return fires(row.features());
    }

    /** True when this rule fires on {@code features}; the only input shape the rule has. */
    public boolean fires(PuzzleContentFeatures features) {
        Objects.requireNonNull(features, "features");
        RegionContentSimilarity regions = features.regions();
        return switch (form) {
            case TARGET_SIMILARITY_BELOW -> regions.targetSimilarity() < threshold;
            case MIN_CANDIDATE_SIMILARITY_BELOW ->
                    regions.minimumCandidateSimilarity() < threshold;
            case CHANGED_CANDIDATE_COUNT_AT_LEAST ->
                    regions.changedCandidateCount(threshold) >= parameter;
            case MIN_REGION_SIMILARITY_BELOW -> regions.minimumRegionSimilarity() < threshold;
            case MEAN_REGION_SIMILARITY_BELOW -> regions.meanRegionSimilarity() < threshold;
            case CHANGED_REGION_COUNT_AT_LEAST -> regions.changedRegionCount(threshold) >= parameter;
            case TARGET_OR_CHANGED_CANDIDATES -> regions.targetChanged(threshold)
                    || regions.changedCandidateCount(threshold) >= parameter;
            case RAW_PANEL_DELTA_ABOVE ->
                    features.rawPanelMeanAbsoluteDelta() > threshold;
            case TARGET_VECTOR_MAX_DELTA_ABOVE -> features.targetVectorMaxDelta() > threshold;
            case GRID_MEAN_DELTA_ABOVE -> features.gridMeanAbsoluteDelta() > threshold;
        };
    }

    /** One CSV-safe identifier of the parameter set, for example {@code 0.800_K6}. */
    public String parameterText() {
        String thresholdText = String.format(Locale.ROOT, "%.3f", threshold);
        return parameter == 0 ? thresholdText : thresholdText + "_K" + parameter;
    }

    /**
     * The single quantity this rule thresholds on: a similarity (fires below the cut), a count of
     * changed regions (fires at or above {@code K}) or a delta (fires above the cut).
     *
     * @return the quantity, or {@link Double#NaN} for the OR form, which has no single quantity
     */
    public double firingQuantity(PuzzleContentFeatures features) {
        Objects.requireNonNull(features, "features");
        RegionContentSimilarity regions = features.regions();
        return switch (form) {
            case TARGET_SIMILARITY_BELOW -> regions.targetSimilarity();
            case MIN_CANDIDATE_SIMILARITY_BELOW -> regions.minimumCandidateSimilarity();
            case CHANGED_CANDIDATE_COUNT_AT_LEAST -> regions.changedCandidateCount(threshold);
            case MIN_REGION_SIMILARITY_BELOW -> regions.minimumRegionSimilarity();
            case MEAN_REGION_SIMILARITY_BELOW -> regions.meanRegionSimilarity();
            case CHANGED_REGION_COUNT_AT_LEAST -> regions.changedRegionCount(threshold);
            case RAW_PANEL_DELTA_ABOVE -> features.rawPanelMeanAbsoluteDelta();
            case TARGET_VECTOR_MAX_DELTA_ABOVE -> features.targetVectorMaxDelta();
            case GRID_MEAN_DELTA_ABOVE -> features.gridMeanAbsoluteDelta();
            case TARGET_OR_CHANGED_CANDIDATES -> Double.NaN;
        };
    }

    /** True when the rule fires for a LARGER firing quantity ({@code >=} the cut). */
    public boolean firesWhenAbove() {
        return switch (form) {
            case CHANGED_CANDIDATE_COUNT_AT_LEAST, CHANGED_REGION_COUNT_AT_LEAST,
                    RAW_PANEL_DELTA_ABOVE, TARGET_VECTOR_MAX_DELTA_ABOVE,
                    GRID_MEAN_DELTA_ABOVE -> true;
            default -> false;
        };
    }

    /** The cut the firing quantity is compared against: {@code K} for count forms. */
    public double firingCut() {
        return switch (form) {
            case CHANGED_CANDIDATE_COUNT_AT_LEAST, CHANGED_REGION_COUNT_AT_LEAST -> parameter;
            default -> threshold;
        };
    }
}
