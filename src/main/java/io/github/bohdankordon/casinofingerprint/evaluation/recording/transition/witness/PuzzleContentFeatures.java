package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

/**
 * Every witness candidate family measured for ONE frame against ONE frozen
 * {@link PuzzleContentBaseline}, as plain numbers.
 *
 * <p>Evaluation only. Nothing here reads a recognition identity: the numbers are pixel structure
 * ({@code W0}..{@code W3}) plus matcher evidence signatures ({@code W4}) that do not depend on
 * which answer the policy confirmed. The two identity-linked W4 diagnostics
 * ({@code assignmentMappingChanged}, {@code assignmentMeanScore}) are recorded for analysis only
 * and are documented as unusable on their own, because two rounds can reuse the same target, the
 * same selected set and the same fragment mapping while their distractor tiles differ.
 *
 * <p>Families:
 *
 * <ul>
 *   <li>{@code W0 rawPanelMeanAbsoluteDelta}: mean absolute grayscale difference over the whole
 *       puzzle panel; a CONTROL that is expected to react to selection brightness, the selector and
 *       overlays;</li>
 *   <li>{@code W1 regions.targetSimilarity}: normalized target structure vs the consumed baseline;</li>
 *   <li>{@code W2 regions.candidateSimilarities}: eight same-position normalized candidate
 *       structures vs the consumed baseline;</li>
 *   <li>{@code W3}: the aggregate view over target plus eight candidates;</li>
 *   <li>{@code W4}: matcher evidence signatures - the target score vector across
 *       {@code FP_1..FP_4}, the eight by four fragment grid against the baseline's fixed reference
 *       fingerprint, and the assignment diagnostics.</li>
 * </ul>
 *
 * @param rawPanelMeanAbsoluteDelta W0, in {@code [0, 255]}
 * @param regions W1 target plus W2 candidate structural similarities, in {@code [0, 1]}
 * @param targetVectorMaxDelta W4, largest absolute change of the four target scores
 * @param gridMeanAbsoluteDelta W4, mean absolute change over the frozen eight by four grid
 * @param gridMaxAbsoluteDelta W4, largest absolute change over the frozen eight by four grid
 * @param assignmentMappingChanged W4 diagnostic, true when the current best assignment maps the
 *        fragments to a different candidate sequence than the baseline; identity-linked
 * @param assignmentMeanScore W4 diagnostic, mean pair score of the current best assignment
 * @param selectionMargin W4 diagnostic, best minus best-different-selection assignment mean
 * @param targetMargin W4 diagnostic, best minus runner-up target score
 * @param uncertaintyReasonCount W4 diagnostic, failing policy gates of this frame
 */
public record PuzzleContentFeatures(
        double rawPanelMeanAbsoluteDelta,
        RegionContentSimilarity regions,
        double targetVectorMaxDelta,
        double gridMeanAbsoluteDelta,
        double gridMaxAbsoluteDelta,
        boolean assignmentMappingChanged,
        double assignmentMeanScore,
        double selectionMargin,
        double targetMargin,
        int uncertaintyReasonCount) {

    public PuzzleContentFeatures {
        requireFinite("rawPanelMeanAbsoluteDelta", rawPanelMeanAbsoluteDelta);
        if (rawPanelMeanAbsoluteDelta < 0.0 || rawPanelMeanAbsoluteDelta > 255.0) {
            throw new IllegalArgumentException(
                    "rawPanelMeanAbsoluteDelta must be within [0, 255], got "
                            + rawPanelMeanAbsoluteDelta);
        }
        if (regions == null) {
            throw new IllegalArgumentException("regions must not be null");
        }
        requireFinite("targetVectorMaxDelta", targetVectorMaxDelta);
        requireFinite("gridMeanAbsoluteDelta", gridMeanAbsoluteDelta);
        requireFinite("gridMaxAbsoluteDelta", gridMaxAbsoluteDelta);
        requireFinite("assignmentMeanScore", assignmentMeanScore);
        requireFinite("selectionMargin", selectionMargin);
        requireFinite("targetMargin", targetMargin);
        if (uncertaintyReasonCount < 0) {
            throw new IllegalArgumentException(
                    "uncertaintyReasonCount must not be negative, got " + uncertaintyReasonCount);
        }
    }

    /** W1: target structural similarity to the consumed baseline. */
    public double targetSimilarity() {
        return regions.targetSimilarity();
    }

    /** W2: weakest same-position candidate similarity. */
    public double minimumCandidateSimilarity() {
        return regions.minimumCandidateSimilarity();
    }

    /** W2: mean same-position candidate similarity. */
    public double meanCandidateSimilarity() {
        return regions.meanCandidateSimilarity();
    }

    /** W3: weakest of the nine region similarities. */
    public double minimumRegionSimilarity() {
        return regions.minimumRegionSimilarity();
    }

    /** W3: mean of the nine region similarities. */
    public double meanRegionSimilarity() {
        return regions.meanRegionSimilarity();
    }

    /** W3: number of the nine regions below the similarity threshold. */
    public int changedRegionCount(double threshold) {
        return regions.changedRegionCount(threshold);
    }

    /** W3: number of the eight candidates below the similarity threshold. */
    public int changedCandidateCount(double threshold) {
        return regions.changedCandidateCount(threshold);
    }

    /** W3: true when the target similarity is below the threshold. */
    public boolean targetChanged(double threshold) {
        return regions.targetChanged(threshold);
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }
}
