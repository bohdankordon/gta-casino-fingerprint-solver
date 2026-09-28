package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Offline rule and threshold exploration over the measured witness frames.
 *
 * <p>Evaluation only. Every rule is scored on two populations: the observed SAME-ROUND frames of
 * every hacked round - including selector movement, 0/1/2/3 selected candidates, the fully selected
 * state, the real wrong selection with the ERROR banner and the recognition interruptions - and the
 * four real round-to-round transitions. A rule is only interesting when the entire same-round
 * population stays quiet while the transitions still fire.
 *
 * <p>Thresholds are measurements of this dataset, never production constants.
 */
public final class WitnessRuleExploration {
    /** Similarity cuts swept by the count rules. */
    static final List<Double> SIMILARITY_CUTS = List.of(0.90, 0.80, 0.70, 0.60, 0.50);
    /** Candidate-count cuts swept by the candidate and region count rules. */
    static final List<Integer> COUNT_CUTS = List.of(2, 4, 6, 7, 8, 9);
    /** Raw panel difference cuts swept by the W0 control. */
    static final List<Double> RAW_PANEL_CUTS = List.of(2.0, 5.0, 10.0, 20.0, 30.0, 60.0, 100.0);
    /** Evidence-signature cuts swept by the W4 rules. */
    static final List<Double> EVIDENCE_CUTS = List.of(0.02, 0.05, 0.10, 0.20, 0.40);

    private WitnessRuleExploration() {
    }

    /** One analyzed hack: its scope, its offline anchors and every row the hack produced. */
    public record HackObservation(
            WitnessAnalyzer.HackScope scope,
            WitnessAnalyzer.HackEvents events,
            List<WitnessFrameRow> rows) {

        public HackObservation {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(events, "events");
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        }

        /** Rows of the transition and its persistence window, in frame order. */
        public List<WitnessFrameRow> transitionRows() {
            return rows.stream()
                    .filter(row -> row.scope() == WitnessScope.TRANSITION
                            || row.scope() == WitnessScope.TRANSITION_OBSERVATION)
                    .toList();
        }
    }

    /** Measured outcome of one rule over the whole dataset. */
    public record RuleEvaluation(
            String ruleId,
            String form,
            double threshold,
            int parameter,
            String description,
            long sameRoundFrames,
            long sameRoundFalseTriggers,
            long sameRoundUncertainFalseTriggers,
            long entryFalseTriggers,
            long exitFalseTriggers,
            int transitionsEvaluated,
            int transitionsDetected,
            String offsetsVersusFirstNewRecognized,
            String offsetsVersusFirstNewStable,
            String perTransition,
            String notes) {

        /** Header of {@code target/stage6c1c-witness-rules.csv}. */
        public static final String HEADER =
                "rule_id,form,threshold,parameter,description,same_round_frames,"
                        + "same_round_false_triggers,same_round_uncertain_false_triggers,"
                        + "entry_false_triggers,exit_false_triggers,transitions_evaluated,"
                        + "transitions_detected,offsets_vs_first_new_recognized,"
                        + "offsets_vs_first_new_stable,per_transition,notes";

        /** True when the rule never fired in a same-round frame and detected every transition. */
        public boolean isCleanOnThisDataset() {
            return sameRoundFalseTriggers == 0 && transitionsEvaluated > 0
                    && transitionsDetected == transitionsEvaluated;
        }

        /** One CSV line. */
        public String csv() {
            return ruleId + ',' + form + ',' + String.format(Locale.ROOT, "%.3f", threshold) + ','
                    + parameter + ',' + description + ',' + sameRoundFrames + ','
                    + sameRoundFalseTriggers + ',' + sameRoundUncertainFalseTriggers + ','
                    + entryFalseTriggers + ',' + exitFalseTriggers + ',' + transitionsEvaluated + ','
                    + transitionsDetected + ',' + offsetsVersusFirstNewRecognized + ','
                    + offsetsVersusFirstNewStable + ',' + perTransition + ',' + notes;
        }

        /** Renders the rule CSV, header included. */
        public static String csv(List<RuleEvaluation> rows) {
            StringBuilder text = new StringBuilder(HEADER).append('\n');
            for (RuleEvaluation row : rows) {
                text.append(row.csv()).append('\n');
            }
            return text.toString();
        }
    }

    /** The complete deterministic sweep of candidate rules. */
    public static List<WitnessRule> sweep() {
        List<WitnessRule> rules = new ArrayList<>();
        for (double cut : SIMILARITY_CUTS) {
            rules.add(targetOnly(cut));
        }
        for (double cut : SIMILARITY_CUTS) {
            rules.add(rule("W2_MIN_CANDIDATE_LT_" + cut, WitnessRule.Form.MIN_CANDIDATE_SIMILARITY_BELOW,
                    cut, 0, "weakest same-position candidate similarity < " + cut));
        }
        for (double cut : SIMILARITY_CUTS) {
            for (int k : COUNT_CUTS) {
                if (k > RegionContentSimilarity.CANDIDATE_COUNT) {
                    continue;
                }
                rules.add(rule("W2_CANDIDATES_LT_" + cut + "_K" + k,
                        WitnessRule.Form.CHANGED_CANDIDATE_COUNT_AT_LEAST, cut, k,
                        "at least " + k + " of 8 candidates below " + cut));
            }
        }
        for (double cut : SIMILARITY_CUTS) {
            rules.add(rule("W3_MIN_REGION_LT_" + cut,
                    WitnessRule.Form.MIN_REGION_SIMILARITY_BELOW, cut, 0,
                    "weakest of the 9 regions below " + cut));
            rules.add(rule("W3_MEAN_REGION_LT_" + cut,
                    WitnessRule.Form.MEAN_REGION_SIMILARITY_BELOW, cut, 0,
                    "mean of the 9 regions below " + cut));
        }
        for (double cut : SIMILARITY_CUTS) {
            for (int k : COUNT_CUTS) {
                rules.add(rule("W3_REGIONS_LT_" + cut + "_K" + k,
                        WitnessRule.Form.CHANGED_REGION_COUNT_AT_LEAST, cut, k,
                        "at least " + k + " of 9 regions below " + cut));
            }
        }
        for (double cut : List.of(0.90, 0.80, 0.70)) {
            for (int k : List.of(2, 4, 6, 8)) {
                rules.add(rule("W3_TARGET_OR_CANDIDATES_LT_" + cut + "_K" + k,
                        WitnessRule.Form.TARGET_OR_CHANGED_CANDIDATES, cut, k,
                        "target below " + cut + " or at least " + k + " candidates below " + cut));
            }
        }
        for (double cut : RAW_PANEL_CUTS) {
            rules.add(rule("W0_RAW_PANEL_GT_" + cut, WitnessRule.Form.RAW_PANEL_DELTA_ABOVE, cut, 0,
                    "raw panel mean absolute grayscale delta > " + cut));
        }
        for (double cut : EVIDENCE_CUTS) {
            rules.add(rule("W4_TARGET_VECTOR_GT_" + cut,
                    WitnessRule.Form.TARGET_VECTOR_MAX_DELTA_ABOVE, cut, 0,
                    "target score vector max delta > " + cut));
            rules.add(rule("W4_GRID_MEAN_GT_" + cut, WitnessRule.Form.GRID_MEAN_DELTA_ABOVE, cut, 0,
                    "fixed-reference fragment grid mean delta > " + cut));
        }
        return List.copyOf(rules);
    }

    /** W1 only: expected to miss every round whose target fingerprint repeats. */
    public static WitnessRule targetOnly(double cut) {
        return rule("W1_TARGET_LT_" + cut, WitnessRule.Form.TARGET_SIMILARITY_BELOW, cut, 0,
                "target similarity alone below " + cut);
    }

    /** Evaluates one rule over every observed hack. */
    public static RuleEvaluation evaluate(WitnessRule rule, List<HackObservation> hacks) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(hacks, "hacks");
        long sameRoundFrames = 0;
        long sameRoundTriggers = 0;
        long sameRoundUncertainTriggers = 0;
        long entryTriggers = 0;
        long exitTriggers = 0;
        int transitionsEvaluated = 0;
        int transitionsDetected = 0;
        List<String> recognizedOffsets = new ArrayList<>();
        List<String> stableOffsets = new ArrayList<>();
        List<String> perTransition = new ArrayList<>();
        for (HackObservation hack : hacks) {
            for (WitnessFrameRow row : hack.rows()) {
                switch (row.scope()) {
                    case SAME_ROUND -> {
                        sameRoundFrames++;
                        if (rule.fires(row)) {
                            sameRoundTriggers++;
                            if (!row.recognized()) {
                                sameRoundUncertainTriggers++;
                            }
                        }
                    }
                    case ENTRY -> {
                        if (rule.fires(row)) {
                            entryTriggers++;
                        }
                    }
                    case EXIT -> {
                        if (rule.fires(row)) {
                            exitTriggers++;
                        }
                    }
                    default -> {
                        // baseline, transition and control rows are scored below
                    }
                }
            }
            Long firstNewRecognized = hack.events().firstNewRecognizedFrame();
            Long firstNewStable = hack.events().firstNewStableFrame();
            if (firstNewRecognized == null || firstNewStable == null) {
                continue;
            }
            transitionsEvaluated++;
            long searchEnd = firstNewStable + WitnessAnalyzer.TRANSITION_OBSERVATION_FRAMES;
            Long detectionFrame = null;
            for (WitnessFrameRow row : hack.transitionRows()) {
                if (row.frameIndex() < firstNewRecognized || row.frameIndex() > searchEnd) {
                    continue;
                }
                if (rule.fires(row) && (detectionFrame == null || row.frameIndex() < detectionFrame)) {
                    detectionFrame = row.frameIndex();
                }
            }
            if (detectionFrame == null) {
                perTransition.add(hack.scope().hackLabel() + ":none");
                continue;
            }
            transitionsDetected++;
            long recognizedOffset = detectionFrame - firstNewRecognized;
            long stableOffset = detectionFrame - firstNewStable;
            recognizedOffsets.add(Long.toString(recognizedOffset));
            stableOffsets.add(Long.toString(stableOffset));
            perTransition.add(String.format(Locale.ROOT, "%s:f%d(%+d/%+d)",
                    hack.scope().hackLabel(), detectionFrame, recognizedOffset, stableOffset));
        }
        String notes = "";
        if (sameRoundTriggers > 0) {
            notes = "rule already fires inside observed same rounds, so it cannot separate them";
        }
        return new RuleEvaluation(rule.id(), rule.form().name(), rule.threshold(), rule.parameter(),
                rule.description(), sameRoundFrames, sameRoundTriggers, sameRoundUncertainTriggers,
                entryTriggers, exitTriggers, transitionsEvaluated, transitionsDetected,
                join(recognizedOffsets), join(stableOffsets), String.join("|", perTransition), notes);
    }

    /** Evaluates the whole sweep in order. */
    public static List<RuleEvaluation> evaluateAll(List<WitnessRule> rules,
            List<HackObservation> hacks) {
        List<RuleEvaluation> results = new ArrayList<>(rules.size());
        for (WitnessRule rule : rules) {
            results.add(evaluate(rule, hacks));
        }
        return List.copyOf(results);
    }

    /**
     * Worst observed same-round value and weakest true transition for one rule, in the units of the
     * rule's own firing quantity.
     *
     * <p>{@code safetyMargin} is how far the whole same-round population stays away from the cut on
     * the safe side; {@code detectionSlack} is how far the weakest true transition overshoots the
     * cut in the firing direction. Both being non-negative is exactly the observed separation of
     * this dataset; a negative margin is reported, never hidden.
     *
     * @param worstSameRoundFrame frame label of the worst same-round observation
     * @param weakestTransitionLabel transition label of the weakest transition observation
     * @param computed false for the OR form, which has no single firing quantity
     */
    public record RuleMargin(
            String ruleId,
            double worstSameRoundValue,
            String worstSameRoundFrame,
            double weakestTransitionValue,
            String weakestTransitionLabel,
            double safetyMargin,
            double detectionSlack,
            double totalMargin,
            boolean computed) {
    }

    /** Computes the observed separation margin of one rule. */
    public static RuleMargin margin(WitnessRule rule, List<HackObservation> hacks) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(hacks, "hacks");
        if (rule.form() == WitnessRule.Form.TARGET_OR_CHANGED_CANDIDATES) {
            return new RuleMargin(rule.id(), Double.NaN, "", Double.NaN, "", Double.NaN,
                    Double.NaN, Double.NaN, false);
        }
        boolean above = rule.firesWhenAbove();
        double worstSameRound = above ? -Double.MAX_VALUE : Double.MAX_VALUE;
        String worstSameRoundFrame = "";
        boolean sameRoundSeen = false;
        double weakestTransition = above ? Double.MAX_VALUE : -Double.MAX_VALUE;
        String weakestTransitionLabel = "";
        boolean transitionSeen = false;
        for (HackObservation hack : hacks) {
            for (WitnessFrameRow row : hack.rows()) {
                if (row.scope() != WitnessScope.SAME_ROUND) {
                    continue;
                }
                double value = rule.firingQuantity(row.features());
                if (!sameRoundSeen || (above ? value > worstSameRound : value < worstSameRound)) {
                    worstSameRound = value;
                    worstSameRoundFrame = label(row);
                    sameRoundSeen = true;
                }
            }
            Long firstNewRecognized = hack.events().firstNewRecognizedFrame();
            Long firstNewStable = hack.events().firstNewStableFrame();
            if (firstNewRecognized == null || firstNewStable == null) {
                continue;
            }
            long windowEnd = firstNewStable + WitnessAnalyzer.TRANSITION_OBSERVATION_FRAMES;
            double best = above ? -Double.MAX_VALUE : Double.MAX_VALUE;
            boolean seen = false;
            for (WitnessFrameRow row : hack.transitionRows()) {
                if (row.frameIndex() < firstNewRecognized || row.frameIndex() > windowEnd) {
                    continue;
                }
                double value = rule.firingQuantity(row.features());
                if (!seen || (above ? value > best : value < best)) {
                    best = value;
                    seen = true;
                }
            }
            if (!seen) {
                continue;
            }
            if (!transitionSeen || (above ? best < weakestTransition : best > weakestTransition)) {
                weakestTransition = best;
                weakestTransitionLabel = hack.scope().hackLabel();
                transitionSeen = true;
            }
        }
        double cut = rule.firingCut();
        double safety = above ? cut - worstSameRound : worstSameRound - cut;
        double slack = above ? weakestTransition - cut : cut - weakestTransition;
        return new RuleMargin(rule.id(), worstSameRound, worstSameRoundFrame, weakestTransition,
                weakestTransitionLabel, safety, slack, safety + slack, true);
    }

    private static String label(WitnessFrameRow row) {
        return String.format(Locale.ROOT, "%s %s f%d %.3fs", row.sourceId(), row.roundScope(),
                row.frameIndex(), row.timestampMs() / 1000.0);
    }

    private static WitnessRule rule(String id, WitnessRule.Form form, double cut, int k,
            String description) {
        return new WitnessRule(id, form, cut, k, description);
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "-" : String.join("|", values);
    }
}
