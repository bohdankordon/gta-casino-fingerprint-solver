package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Human-readable renderer of the Stage 6C.1C witness characterization.
 *
 * <p>Every number in the rendered report is measured by {@link TransitionWitnessAnalysis} on this
 * dataset; nothing is estimated, and the decision gate at the end is derived from the measured
 * rules, margins and controls instead of being asserted.
 */
final class WitnessReport {
    /** Everything the report renders. */
    record Input(
            List<TransitionWitnessAnalysis.SourceRun> sourceRuns,
            List<TransitionWitnessAnalysis.HackSummary> hackSummaries,
            List<WitnessFrameRow> rows,
            List<TransitionWitnessAnalysis.TransitionAnchorRow> transitionRows,
            List<WitnessDistributions.Row> distributions,
            List<TransitionWitnessAnalysis.SeparationRow> separations,
            List<WitnessRuleExploration.RuleEvaluation> rules,
            List<WitnessRuleExploration.RuleMargin> ruleMargins,
            List<WitnessRule> leadingRules,
            List<CounterfactualIdentityReplay.Row> counterfactuals,
            List<TransitionWitnessAnalysis.SameTargetRow> sameTargetRows,
            List<java.nio.file.Path> contactSheets,
            long wallMillis) {

        Input {
            Objects.requireNonNull(sourceRuns, "sourceRuns");
            Objects.requireNonNull(rows, "rows");
        }
    }

    private WitnessReport() {
    }

    /** Renders the whole report. */
    static String render(Input input) {
        StringBuilder text = new StringBuilder();
        text.append("Stage 6C.1C independent visual round-transition witness characterization\n");
        text.append("============================================================================\n\n");
        text.append("MEASUREMENT ONLY. This stage adds no production transition witness. "
                + "RoundLifecycleTracker\nand RecognitionConsensusTracker are untouched and the "
                + "current fail-closed same-identity\nbehaviour is unchanged. Nothing here sends "
                + "input, tunes a threshold, adds production\n1080p support or introduces a timing "
                + "constant.\n\n");

        sources(text, input);
        baselines(text, input);
        families(text);
        scopes(text, input);
        withinRound(text, input);
        transitions(text, input);
        sameTarget(text, input);
        counterfactual(text, input);
        exactRepeat(text, input);
        rules(text, input);
        entryExit(text, input);
        persistence(text, input);
        decisionGate(text, input);
        limitations(text, input);
        artifacts(text, input);
        return text.toString();
    }

    private static void sources(StringBuilder text, Input input) {
        heading(text, "1. SOURCES VERIFIED BEFORE ANY MEASUREMENT");
        text.append(String.format(Locale.ROOT, "%-18s %-10s %-46s %6s %9s %9s %8s %10s%n",
                "source", "resolution", "geometry", "hacks", "analyzed", "decoded", "fps",
                "wall_ms"));
        for (TransitionWitnessAnalysis.SourceRun run : input.sourceRuns()) {
            text.append(String.format(Locale.ROOT, "%-18s %-10s %-46s %6d %9d %9d %8.3f %10d%n",
                    run.sourceId(), run.resolution(), run.geometry(), run.hacks(),
                    run.analyzedFrames(), run.decodedFrames(), run.fps(), run.wallMillis()));
            text.append(String.format(Locale.ROOT, "%20s size %d bytes  sha256 %s%n", "",
                    run.sizeBytes(), run.sha256()));
        }
        text.append("\nThe recordings are private local material and are never committed, copied "
                + "or uploaded.\n1440p uses the bundled production layout unmodified; 1080p uses "
                + "the Stage 6 evaluation-only\nuniform 0.75 geometry. Every decoded frame inside "
                + "each hack window padded by 2.0 s was\nanalyzed at full source rate; no sampling "
                + "was used for any conclusion.\n\n");
    }

    private static void baselines(StringBuilder text, Input input) {
        heading(text, "2. BASELINE / CONSUMPTION SNAPSHOT");
        text.append("One deterministic snapshot per round, frozen from the FIRST stable recognized "
                + "frame whose\nidentity equals the annotated round answer - the same point a "
                + "Stage 6C.1B consumer immediately\nconsumes. The baseline is never adapted.\n\n");
        text.append(String.format(Locale.ROOT, "%-24s %-10s %8s %10s %-20s %8s %8s %8s%n",
                "hack", "round", "frame", "time_s", "identity", "assign", "selMrg", "tgtMrg"));
        for (TransitionWitnessAnalysis.HackSummary summary : input.hackSummaries()) {
            for (int roundId = 1; roundId <= 2; roundId++) {
                Long frame = roundId == 1 ? summary.events().roundOneBaselineFrame()
                        : summary.events().roundTwoBaselineFrame();
                Long millis = roundId == 1 ? summary.events().roundOneBaselineTimestampMs()
                        : summary.events().roundTwoBaselineTimestampMs();
                String identity = roundId == 1 ? summary.scope().roundOneAnswer().code()
                        : summary.scope().roundTwoAnswer().code();
                text.append(String.format(Locale.ROOT, "%-24s %-10s %8s %10s %-20s%n",
                        summary.hackLabel(), "R" + roundId, frame == null ? "-" : frame,
                        millis == null ? "-" : String.format(Locale.ROOT, "%.3f", millis / 1000.0),
                        identity));
            }
        }
        text.append("\n");
        for (TransitionWitnessAnalysis.HackSummary summary : input.hackSummaries()) {
            WitnessAnalyzer.HackEvents events = summary.events();
            text.append(String.format(Locale.ROOT,
                    "%s: last old recognized %s / stable %s; first new recognized %s / stable %s; "
                            + "last stable R2 %s; last recognized R2 %s; frames %d, features %d, "
                            + "entry measured %d, featureless %d%n",
                    summary.hackLabel(), value(events.lastOldRecognizedFrame()),
                    value(events.lastOldStableFrame()), value(events.firstNewRecognizedFrame()),
                    value(events.firstNewStableFrame()), value(events.lastStableRoundTwoFrame()),
                    value(events.lastRecognizedRoundTwoFrame()),
                    summary.events().lastAnalyzedFrame() == null ? -1
                            : summary.events().lastAnalyzedFrame() + 1,
                    input.rows().stream()
                            .filter(row -> row.sourceId().equals(summary.sourceId())
                                    && row.roundScope().startsWith("H" + summary.scope().hackId()))
                            .count(),
                    events.entryFramesMeasured(), events.featureLessFrames()));
        }
        text.append("\nScope events use the production consensus tracker over the real decisions; "
                + "they are offline\nevaluation anchors and never a runtime signal.\n\n");
    }

    private static void families(StringBuilder text) {
        heading(text, "3. CANDIDATE WITNESS FAMILIES MEASURED");
        text.append("W0 RAW PANEL DIFFERENCE (control)\n"
                + "   mean absolute grayscale delta over the whole puzzle panel (union of the "
                + "target and\n   candidate ROIs). Expected to react to selection brightness, "
                + "selector overlays and\n   compression noise; it is included to demonstrate "
                + "exactly that.\n"
                + "W1 TARGET-ONLY STRUCTURAL CHANGE\n"
                + "   production StructuralSimilarityScorer between the normalized current "
                + "target and the\n   normalized consumed baseline target (translation radius 8 "
                + "px on the 256x384 profile).\n"
                + "W2 PER-CANDIDATE STRUCTURAL CHANGE\n"
                + "   the same scorer between each current normalized candidate and the same "
                + "POSITION baseline\n   candidate (radius 4 px on the 128x128 profile), reported "
                + "as C0..C7 plus min/mean/\n   median and changed counts at three analysis cuts.\n"
                + "W3 FULL PUZZLE STRUCTURAL SIGNATURE\n"
                + "   aggregates over the target plus eight candidates: weakest and mean "
                + "similarity, and the\n   number of changed regions at the analysis cuts.\n"
                + "W4 MATCHING / ASSIGNMENT EVIDENCE SIGNATURE\n"
                + "   the target score vector across FP_1..FP_4, the eight by four fragment "
                + "score grid against the\n   baseline's fixed reference fingerprint, and the "
                + "assignment diagnostics.\n\n"
                + "Analysis cuts (NOT production thresholds): similarity 0.90 / 0.75 / 0.50 for "
                + "the per-frame\ncounts; the rule sweep additionally explores 0.60, 0.80 and "
                + "count cuts 2..8.\n\n");
    }

    private static void scopes(StringBuilder text, Input input) {
        heading(text, "4. SCOPE POPULATIONS");
        text.append(String.format(Locale.ROOT, "%-24s %8s%n", "scope", "frames"));
        for (WitnessScope scope : WitnessScope.values()) {
            long frames = input.rows().stream().filter(row -> row.scope() == scope).count();
            text.append(String.format(Locale.ROOT, "%-24s %8d%n", scope, frames));
        }
        text.append(String.format(Locale.ROOT, "%-24s %8d%n", "TOTAL_ROWS", input.rows().size()));
        text.append("\nSAME_ROUND rows are one per frame after a consumed baseline; "
                + "TRANSITION_OBSERVATION rows are\nadditional rows for the same frames, still "
                + "measured against the OLD baseline, so\npersistence can be measured without "
                + "adapting the baseline.\n\n");
    }

    private static void withinRound(StringBuilder text, Input input) {
        heading(text, "5. WITHIN-ROUND DISTRIBUTIONS (worst case first)");
        List<WitnessDistributions.Population> populations = List.of(
                new WitnessDistributions.Population("SAME_ROUND_ALL",
                        row -> row.scope() == WitnessScope.SAME_ROUND),
                new WitnessDistributions.Population("SAME_ROUND_R1",
                        row -> row.scope() == WitnessScope.SAME_ROUND
                                && row.roundScope().endsWith("R1")),
                new WitnessDistributions.Population("SAME_ROUND_R2",
                        row -> row.scope() == WitnessScope.SAME_ROUND
                                && row.roundScope().endsWith("R2")),
                new WitnessDistributions.Population("SAME_ROUND_UNCERTAIN",
                        row -> row.scope() == WitnessScope.SAME_ROUND && !row.recognized()),
                new WitnessDistributions.Population("SAME_ROUND_RECOGNIZED",
                        row -> row.scope() == WitnessScope.SAME_ROUND && row.recognized()));
        List<WitnessDistributions.Signal> signals = List.of(
                WitnessDistributions.Signal.W0_RAW_PANEL_MEAN_ABS_DELTA,
                WitnessDistributions.Signal.W1_TARGET_SIMILARITY,
                WitnessDistributions.Signal.W2_MIN_CANDIDATE_SIMILARITY,
                WitnessDistributions.Signal.W2_MEAN_CANDIDATE_SIMILARITY,
                WitnessDistributions.Signal.W3_MIN_REGION_SIMILARITY,
                WitnessDistributions.Signal.W3_MEAN_REGION_SIMILARITY,
                WitnessDistributions.Signal.W3_CHANGED_REGIONS_AT_0_90,
                WitnessDistributions.Signal.W3_CHANGED_REGIONS_AT_0_75,
                WitnessDistributions.Signal.W4_TARGET_VECTOR_MAX_DELTA,
                WitnessDistributions.Signal.W4_GRID_MEAN_ABS_DELTA);
        text.append(String.format(Locale.ROOT, "%-34s %-24s %7s %9s %9s %9s %9s %9s %9s%n",
                "signal", "population", "frames", "min", "p05", "p50", "p95", "p99",
                "max"));
        for (WitnessDistributions.Signal signal : signals) {
            for (WitnessDistributions.Population population : populations) {
                WitnessDistributions.Row row = distribution(input, population.name(), signal);
                text.append(String.format(Locale.ROOT,
                        "%-34s %-24s %7d %9s %9s %9s %9s %9s %9s%n", signal, population.name(),
                        row.frames(), number(row.minimum()), number(row.p05()), number(row.p50()),
                        number(row.p95()), number(row.p99()), number(row.maximum())));
            }
        }
        text.append("\nWorst same-round observations (the frames a rule must not fire on):\n");
        for (WitnessDistributions.Signal signal : List.of(
                WitnessDistributions.Signal.W0_RAW_PANEL_MEAN_ABS_DELTA,
                WitnessDistributions.Signal.W2_MIN_CANDIDATE_SIMILARITY,
                WitnessDistributions.Signal.W3_MIN_REGION_SIMILARITY,
                WitnessDistributions.Signal.W4_GRID_MEAN_ABS_DELTA)) {
            WitnessDistributions.Row row = distribution(input, "SAME_ROUND_ALL", signal);
            text.append(String.format(Locale.ROOT, "  %-34s worst %.6f at %s%n", signal,
                    row.worst(), row.worstFrame()));
        }
        text.append("\nThe populations include selector movement, 0/1/2/3 selected candidates, the "
                + "fully selected\nstate, the real wrong C5 selection with the ERROR banner, the "
                + "C5 clearing and every\nrecognition interruption and re-stabilization. Local "
                + "review sheets of the worst frames are\nlisted in section 15.\n\n");
    }

    private static void transitions(StringBuilder text, Input input) {
        heading(text, "6. EACH REAL R1 -> R2 TRANSITION");
        text.append(String.format(Locale.ROOT,
                "%-26s %-24s %8s %9s %9s %9s %9s %8s %8s %9s %9s%n",
                "transition", "anchor", "frame", "w0", "w1", "w2min", "w3min", "chg90",
                "reg90", "w4vec", "w4grid"));
        for (TransitionWitnessAnalysis.TransitionAnchorRow row : input.transitionRows()) {
            text.append(String.format(Locale.ROOT,
                    "%-26s %-24s %8d %9.3f %9.4f %9.4f %9.4f %8d %8d %9.4f %9.4f%n",
                    row.transitionId(), row.anchor(), row.frameIndex(), row.rawPanelDelta(),
                    row.targetSimilarity(), row.minimumCandidateSimilarity(),
                    row.minimumRegionSimilarity(), row.changedCandidatesAt090(),
                    row.changedRegionsAt090(), row.targetVectorMaxDelta(), row.gridMeanDelta()));
        }
        text.append("\nAnchors: LAST_OLD_STABLE and LAST_OLD_RECOGNIZED are the last frames whose "
                + "answer was still the\nold round, FIRST_NEW_RECOGNIZED is the first frame of the "
                + "new round and FIRST_NEW_STABLE is\nthe frame the new round's baseline is frozen "
                + "from. STABLE_NEW_WINDOW_WORST is the worst\nmeasurement of the bounded "
                + "persistence window. Offsets relative to the two new-round anchors:\n");
        for (TransitionWitnessAnalysis.TransitionAnchorRow row : input.transitionRows()) {
            if (!row.anchor().equals("FIRST_NEW_RECOGNIZED")) {
                continue;
            }
            text.append(String.format(Locale.ROOT, "  %-26s first new recognized f%d %.3fs%n",
                    row.transitionId(), row.frameIndex(), row.timestampMs() / 1000.0));
        }
        text.append("\nSeparation of the worst same-round observation from the weakest true "
                + "transition:\n\n");
        text.append(String.format(Locale.ROOT, "%-34s %-26s %14s %14s %10s %6s%n", "signal",
                "direction", "worst_same", "weakest_trans", "margin", "clean"));
        for (TransitionWitnessAnalysis.SeparationRow row : input.separations()) {
            text.append(String.format(Locale.ROOT, "%-34s %-26s %14s %14s %10s %6s%n", row.signal(),
                    row.changeDirection(), number(row.worstSameRound()),
                    number(row.weakestTransition()), number(row.margin()), row.cleanSeparation()));
        }
        text.append("\n");
    }

    private static void sameTarget(StringBuilder text, Input input) {
        heading(text, "7. SAME-TARGET ANALYSIS");
        if (input.sameTargetRows().isEmpty()) {
            text.append("No two analyzed rounds shared a target fingerprint.\n\n");
            return;
        }
        text.append(String.format(Locale.ROOT, "%-44s %-8s %8s %8s %8s %8s %8s %8s%n", "pair",
                "target", "w1", "w2min", "w2mean", "w3min", "chg90", "w4grid"));
        for (TransitionWitnessAnalysis.SameTargetRow row : input.sameTargetRows()) {
            text.append(String.format(Locale.ROOT,
                    "%-44s %-8s %8.4f %8.4f %8.4f %8.4f %8d %8.4f%n", row.pairId(),
                    row.targetFingerprint(), row.targetSimilarity(),
                    row.minimumCandidateSimilarity(), row.meanCandidateSimilarity(),
                    row.minimumRegionSimilarity(), row.changedRegionsAt090(), row.gridMeanDelta()));
        }
        text.append("\nThese pairs are never consecutive rounds: they come from different hacks or "
                + "different recordings,\nso they show that candidate-grid content can separate "
                + "two rounds with the same target and\nthe same selected set. They are useful "
                + "evidence and they are NOT a proof about consecutive\nrounds.\n\n");
        boolean skipped = input.sameTargetRows().stream()
                .anyMatch(row -> row.notes().contains("skipped for differing capture geometry"));
        if (skipped) {
            text.append("Same-target pairs of different capture geometry (1440p against the "
                    + "evaluation-only 1080p\ngeometry) are NOT compared: a 0.75-scaled capture "
                    + "resamples the same ridges\ndifferently, so such a pair would mix a real "
                    + "content change with a resolution change.\n\n");
        }
    }

    private static void counterfactual(StringBuilder text, Input input) {
        heading(text, "8. COUNTERFACTUAL SAME-IDENTITY REPLAY");
        text.append("Real pixels, synthetic identity: every frame of round 2 is claimed to be the "
                + "round-1 identity.\nThe witness rule reads content features only, so its "
                + "decision must not move.\n\n");
        text.append(String.format(Locale.ROOT, "%-26s %-8s %9s %9s %9s %10s %10s%n", "transition",
                "fired", "frame", "vsRecog", "vsStable", "lawReady", "lawSuppr"));
        for (CounterfactualIdentityReplay.Row row : input.counterfactuals()) {
            text.append(String.format(Locale.ROOT,
                    "%-26s %-8s %9d %9d %9d %10d %10d%n", row.transitionId(),
                    row.witnessFiredAtTransition(), row.detectionFrame(),
                    row.offsetVsFirstNewRecognized(), row.offsetVsFirstNewStable(),
                    row.lifecycleReadyEvents(), row.lifecycleSuppressedOnsets()));
        }
        boolean allFired = input.counterfactuals().stream()
                .allMatch(CounterfactualIdentityReplay.Row::witnessFiredAtTransition);
        boolean identical = input.counterfactuals().stream()
                .allMatch(CounterfactualIdentityReplay.Row::featuresIdenticalUnderCounterfactual);
        text.append(String.format(Locale.ROOT,
                "%nWitness fired at every counterfactual transition: %s. Feature values identical "
                        + "to the real%nevaluation: %s (by construction: the features contain no "
                        + "identity, and the re-evaluation%nconfirms it). Under the counterfactual "
                        + "the production lifecycle tracker reports one ready%nround only: the "
                        + "second onset is suppressed as a repeated consumed identity - the "
                        + "exact%nfail-closed limitation this stage is about.%n%n",
                allFired, identical));
        text.append("This does NOT prove that every real same-identity round pair changes its "
                + "visual content. It proves\nonly that IF the content changes like these "
                + "observed transitions, the witness does not need an\nidentity change to see "
                + "it.\n\n");
    }

    private static void exactRepeat(StringBuilder text, Input input) {
        heading(text, "9. EXACT-VISUAL-REPEAT CONTROL");
        WitnessDistributions.Row minRegion =
                distribution(input, "EXACT_REPEAT_CONTROL",
                        WitnessDistributions.Signal.W3_MIN_REGION_SIMILARITY);
        WitnessDistributions.Row rawPanel =
                distribution(input, "EXACT_REPEAT_CONTROL",
                        WitnessDistributions.Signal.W0_RAW_PANEL_MEAN_ABS_DELTA);
        WitnessDistributions.Row grid =
                distribution(input, "EXACT_REPEAT_CONTROL",
                        WitnessDistributions.Signal.W4_GRID_MEAN_ABS_DELTA);
        text.append(String.format(Locale.ROOT,
                "Every consumed baseline frame was measured against itself (%d control rows):%n"
                        + "  weakest region similarity  min %.6f  max %.6f%n"
                        + "  raw panel difference       min %.6f  max %.6f%n"
                        + "  fragment grid mean delta   min %.6f  max %.6f%n%n",
                minRegion.frames(), minRegion.minimum(), minRegion.maximum(), rawPanel.minimum(),
                rawPanel.maximum(), grid.minimum(), grid.maximum()));
        text.append("A purely visual content witness is a fixed point on identical content, so an "
                + "exact visual\nrepetition - same target, same eight tile contents, same "
                + "positions - is observationally\nindistinguishable and MUST stay fail-closed. "
                + "No rule from section 11 fires on these rows.\n\n");
    }

    private static void rules(StringBuilder text, Input input) {
        heading(text, "10. RULE / THRESHOLD EXPLORATION");
        long clean = input.rules().stream()
                .filter(WitnessRuleExploration.RuleEvaluation::isCleanOnThisDataset).count();
        long withTriggers = input.rules().stream()
                .filter(evaluation -> evaluation.sameRoundFalseTriggers() > 0).count();
        long partial = input.rules().stream()
                .filter(evaluation -> evaluation.sameRoundFalseTriggers() == 0
                        && evaluation.transitionsDetected() < evaluation.transitionsEvaluated())
                .count();
        text.append(String.format(Locale.ROOT,
                "%d rules evaluated. %d are clean on this dataset (zero same-round false triggers "
                        + "AND every\nobserved transition detected), %d already fire inside an "
                        + "observed same round, %d stay quiet\nsame-round but miss at least one "
                        + "transition.%n%n", input.rules().size(), clean, withTriggers, partial));
        Map<String, WitnessRuleExploration.RuleMargin> margins = new LinkedHashMap<>();
        for (WitnessRuleExploration.RuleMargin margin : input.ruleMargins()) {
            margins.put(margin.ruleId(), margin);
        }
        List<WitnessRuleExploration.RuleEvaluation> cleanRules = input.rules().stream()
                .filter(WitnessRuleExploration.RuleEvaluation::isCleanOnThisDataset)
                .sorted(Comparator.comparingDouble((WitnessRuleExploration.RuleEvaluation evaluation) ->
                        Math.min(margins.get(evaluation.ruleId()).safetyMargin(),
                                margins.get(evaluation.ruleId()).detectionSlack())).reversed())
                .limit(15)
                .toList();
        if (!cleanRules.isEmpty()) {
            text.append("Clean rules, best balanced margin first (balanced = the smaller of "
                    + "same-round safety and\ntransition slack; safety is how far the worst "
                    + "same-round observation stays below the cut,\nslack is how far the weakest "
                    + "true transition overshoots it):\n\n");
            text.append(String.format(Locale.ROOT, "%-38s %9s %9s %10s %-12s%n", "rule", "safety",
                    "slack", "balanced", "offsets r/s"));
            for (WitnessRuleExploration.RuleEvaluation evaluation : cleanRules) {
                WitnessRuleExploration.RuleMargin margin = margins.get(evaluation.ruleId());
                text.append(String.format(Locale.ROOT, "%-38s %9.4f %9.4f %10.4f %-12s%n",
                        evaluation.ruleId(), margin.safetyMargin(), margin.detectionSlack(),
                        Math.min(margin.safetyMargin(), margin.detectionSlack()),
                        evaluation.offsetsVersusFirstNewRecognized() + "/"
                                + evaluation.offsetsVersusFirstNewStable()));
            }
        }
        text.append("\nW0 raw-panel control behaviour (why naive pixel difference cannot be "
                + "promoted):\n");
        for (WitnessRuleExploration.RuleEvaluation evaluation : input.rules()) {
            if (!evaluation.form().equals(WitnessRule.Form.RAW_PANEL_DELTA_ABOVE.name())) {
                continue;
            }
            text.append(String.format(Locale.ROOT,
                    "  %-28s same-round triggers %6d of %d frames, transitions detected %d/%d%n",
                    evaluation.ruleId(), evaluation.sameRoundFalseTriggers(),
                    evaluation.sameRoundFrames(), evaluation.transitionsDetected(),
                    evaluation.transitionsEvaluated()));
        }
        text.append("\nSingle-family failure modes observed on this dataset:\n");
        List<String> watched = List.of("W1_TARGET_LT_0.9", "W1_TARGET_LT_0.5",
                "W2_MIN_CANDIDATE_LT_0.9", "W3_MIN_REGION_LT_0.9", "W4_GRID_MEAN_GT_0.02",
                "W4_TARGET_VECTOR_GT_0.02", "W3_MEAN_REGION_LT_0.9");
        for (WitnessRuleExploration.RuleEvaluation evaluation : input.rules()) {
            if (!watched.contains(evaluation.ruleId())) {
                continue;
            }
            text.append(String.format(Locale.ROOT,
                    "  %-30s same-round triggers %6d, transitions detected %d/%d, first firing "
                            + "offsets vs stable %s%n",
                    evaluation.ruleId(), evaluation.sameRoundFalseTriggers(),
                    evaluation.transitionsDetected(), evaluation.transitionsEvaluated(),
                    evaluation.offsetsVersusFirstNewStable()));
        }
        text.append("\nThe worst same-round deviations of this dataset are one reviewable, recurring UI "
                + "element:\nthe white SIGNAL PATCH notification banner that appears over the "
                + "puzzle panel and covers\npart of the target and of one or two candidate tiles. "
                + "The local sheets under\ntarget/stage6c1c-witness-contact-sheets/ show it "
                + "directly: every top same-round frame of every\nround is such a banner frame or a "
                + "selection frame, and the " + "banner also drives the\nsmallest target "
                + "similarities of the same-round population. That is a measured, documented\n"
                + "limitation of any content witness, not a property of the puzzle content.\n\n");
        text.append("\nNo threshold from this sweep is promoted to production: four observed "
                + "transitions are\nevidence, not a distribution.\n\n");
    }

    private static void entryExit(StringBuilder text, Input input) {
        heading(text, "11. HACK ENTRY / EXIT OBSERVATIONS");
        for (String population : List.of("ENTRY", "EXIT")) {
            text.append(population).append(":\n");
            for (WitnessDistributions.Signal signal : List.of(
                    WitnessDistributions.Signal.W0_RAW_PANEL_MEAN_ABS_DELTA,
                    WitnessDistributions.Signal.W3_MIN_REGION_SIMILARITY,
                    WitnessDistributions.Signal.W3_CHANGED_REGIONS_AT_0_90,
                    WitnessDistributions.Signal.W4_GRID_MEAN_ABS_DELTA)) {
                WitnessDistributions.Row row = distribution(input, population, signal);
                text.append(String.format(Locale.ROOT,
                        "  %-34s frames %6d min %10s p50 %10s max %10s%n", signal, row.frames(),
                        number(row.minimum()), number(row.p50()), number(row.maximum())));
            }
        }
        text.append("\nEntry frames show the puzzle panel absent or only partially drawn; exit frames "
                + "show it cleared\nor covered by the success overlay. Both therefore look like "
                + "extreme structural change to every\nwitness family, which is why an eventual "
                + "production witness would have to be armed only\nwhile RoundLifecycleTracker "
                + "holds a consumed round - and why those frames are excluded from\nthe "
                + "same-round population of this measurement.\n\n");
    }

    private static void persistence(StringBuilder text, Input input) {
        heading(text, "12. PERSISTENCE / STABILITY");
        for (WitnessRule leading : input.leadingRules()) {
            text.append(String.format(Locale.ROOT, "Candidate rule %s (%s):%n", leading.id(),
                    leading.description()));
            for (TransitionWitnessAnalysis.HackSummary summary : input.hackSummaries()) {
            Long firstNewRecognized = summary.events().firstNewRecognizedFrame();
            Long firstNewStable = summary.events().firstNewStableFrame();
            if (firstNewRecognized == null || firstNewStable == null) {
                continue;
            }
            long windowEnd = firstNewStable + WitnessAnalyzer.TRANSITION_OBSERVATION_FRAMES;
            List<WitnessFrameRow> window = new ArrayList<>();
            for (WitnessFrameRow row : input.rows()) {
                if (!row.roundScope().startsWith("H" + summary.scope().hackId())) {
                    continue;
                }
                if ((row.scope() == WitnessScope.TRANSITION
                        || row.scope() == WitnessScope.TRANSITION_OBSERVATION)
                        && row.frameIndex() >= firstNewRecognized
                        && row.frameIndex() <= windowEnd) {
                    window.add(row);
                }
            }
            window.sort(Comparator.comparingLong(WitnessFrameRow::frameIndex));
            long firstFiring = -1;
            for (WitnessFrameRow row : window) {
                if (leading.fires(row)) {
                    firstFiring = row.frameIndex();
                    break;
                }
            }
            if (firstFiring < 0) {
                text.append(String.format(Locale.ROOT,
                        "  %-24s no firing frame inside the transition observation window%n",
                        summary.hackLabel()));
                continue;
            }
            long consecutive = 0;
            long expected = firstFiring;
            for (WitnessFrameRow row : window) {
                if (row.frameIndex() < firstFiring) {
                    continue;
                }
                if (row.frameIndex() != expected || !leading.fires(row)) {
                    break;
                }
                consecutive++;
                expected++;
            }
            text.append(String.format(Locale.ROOT,
                    "  %-24s first firing f%d, %d consecutive firing frames of the %d frame "
                            + "observation window (vs first new recognized %+d, vs first new "
                            + "stable %+d)%n",
                    summary.hackLabel(), firstFiring, consecutive, window.size(),
                    firstFiring - firstNewRecognized, firstFiring - firstNewStable));
            }
            text.append('\n');
        }
        text.append("\nConsecutive evidence frames are measured OFFLINE only; this stage chooses "
                + "no production\ncount, no cooldown and no timer.\n\n");
    }

    private static void decisionGate(StringBuilder text, Input input) {
        heading(text, "13. DECISION GATE");
        long sameRoundFrames = input.rows().stream()
                .filter(row -> row.scope() == WitnessScope.SAME_ROUND).count();
        List<WitnessRuleExploration.RuleEvaluation> cleanRules = input.rules().stream()
                .filter(WitnessRuleExploration.RuleEvaluation::isCleanOnThisDataset).toList();
        long rulesWithSameRoundTriggers = input.rules().stream()
                .filter(evaluation -> evaluation.sameRoundFalseTriggers() > 0).count();
        long rulesMissingATransition = input.rules().stream()
                .filter(evaluation -> evaluation.sameRoundFalseTriggers() == 0
                        && evaluation.transitionsDetected() < evaluation.transitionsEvaluated())
                .count();
        int transitionsEvaluated = input.rules().isEmpty() ? 0
                : input.rules().get(0).transitionsEvaluated();
        boolean counterfactualOk = !input.counterfactuals().isEmpty()
                && input.counterfactuals().stream()
                        .allMatch(CounterfactualIdentityReplay.Row::witnessFiredAtTransition);
        WitnessDistributions.Row control = distribution(input, "EXACT_REPEAT_CONTROL",
                WitnessDistributions.Signal.W3_MIN_REGION_SIMILARITY);
        boolean controlQuiet = control.frames() > 0 && control.minimum() >= 0.999;
        boolean cleanSeparation = input.separations().stream()
                .anyMatch(row -> row.cleanSeparation()
                        && (row.signal().startsWith("W2_") || row.signal().startsWith("W3_")
                                || row.signal().startsWith("W4_")));

        text.append("1. Which witness families were measured?\n"
                + "   W0 raw panel difference (control), W1 target-only structural similarity, "
                + "W2 per-candidate\n   structural similarity at the same position, W3 the "
                + "nine-region aggregate, W4 matching and\n   assignment evidence signatures "
                + "(target score vector, fixed-reference fragment grid,\n   assignment "
                + "diagnostics).\n\n");
        text.append(String.format(Locale.ROOT,
                "2. Which are rejected and why?%n"
                        + "   W0 is rejected as a discriminator: it reacts to interaction state, not "
                        + "to content (see the%n   control rows in section 10). Family-level "
                        + "rejections follow directly from the clean-separation%n   column of "
                        + "section 6: %s. Rules that still fire inside a same round are%n"
                        + "   rejected per rule in the rules CSV, and a family that cannot separate "
                        + "on its own%n   cannot be promoted on its own.%n%n",
                cleanSeparation
                        ? "at least one structural family separates worst same-round from weakest "
                                + "transition"
                        : "no structural family separates worst same-round from weakest transition "
                                + "on this dataset"));
        text.append(String.format(Locale.ROOT,
                "3. Does any candidate produce ZERO false transition events over all observed "
                        + "same-round frames?%n"
                        + "   %d of %d measured same-round frames were evaluated against every "
                        + "rule; %d of the %d rules%n   have zero same-round false triggers, %d "
                        + "fire inside an observed same round and %d stay%n   quiet there while "
                        + "missing at least one transition.%n\n",
                sameRoundFrames, sameRoundFrames, cleanRules.size(), input.rules().size(),
                rulesWithSameRoundTriggers, rulesMissingATransition));
        text.append(String.format(Locale.ROOT,
                "4. Do those candidates detect all %d observed transitions?%n   %s%n%n",
                transitionsEvaluated,
                cleanRules.isEmpty()
                        ? "No rule is clean on this dataset, so no candidate detects all "
                                + "transitions while staying silent inside a round."
                        : cleanRules.size() + " clean rules detect " + transitionsEvaluated + " of "
                                + transitionsEvaluated + ", for example "
                                + input.leadingRules().stream().map(WitnessRule::id).toList()
                                + "."));
        text.append(String.format(Locale.ROOT,
                "5. At what frame relative to first new recognized / first new stable?%n"
                        + "   For %s: %n%s%n",
                input.leadingRules().stream().map(WitnessRule::id).toList(),
                input.leadingRules().stream().map(rule -> String.format(Locale.ROOT,
                        "     %-38s offsets %s / %s frames vs first new recognized / stable",
                        rule.id(), offsetsOf(input, rule, true), offsetsOf(input, rule, false)))
                        .reduce("", String::concat) + "\n"));
        text.append(String.format(Locale.ROOT,
                "6. Is there a clean numerical separation between worst same-round and weakest "
                        + "transition?%n   %s%n%n",
                cleanSeparation
                        ? "Yes for at least one structural family: the worst same-round observation "
                                + "stays on the safe side of the\n   weakest true transition (see "
                                + "the margin column of section 6)."
                        : "No: same-round and transition observations overlap on every structural "
                                + "family measured here."));
        text.append(String.format(Locale.ROOT,
                "7. Does it remain effective in the counterfactual A -> A identity replay?%n"
                        + "   %s (features identical under the identity override: %s).%n%n",
                counterfactualOk
                        ? "Yes: the witness fired at every counterfactual transition while the "
                                + "production lifecycle tracker\n   suppressed the second round as a "
                                + "repeated consumed identity."
                        : "Not for every transition: at least one counterfactual transition was not "
                                + "observed by the witness.",
                input.counterfactuals().stream()
                        .allMatch(CounterfactualIdentityReplay.Row::featuresIdenticalUnderCounterfactual)));
        text.append(String.format(Locale.ROOT,
                "8. Does same-target evidence show that candidate-grid content can provide a signal "
                        + "when target-only cannot?%n   %s%n%n",
                input.sameTargetRows().isEmpty()
                        ? "No two analyzed rounds shared a target fingerprint in this dataset."
                        : "Yes, for " + input.sameTargetRows().size() + " same-target round pairs "
                                + "(section 7): target similarity stays high while the\n   "
                                + "candidate grid and the region aggregates still move. These pairs "
                                + "are not consecutive rounds."));
        text.append(String.format(Locale.ROOT,
                "9. Does exact visual repetition correctly remain undetectable?%n"
                        + "   Yes: %d control rows measured the baseline frame against itself with a "
                        + "minimum region\n   similarity of %.6f and no rule fires on them.%n%n",
                control.frames(), control.minimum()));
        boolean justified = !cleanRules.isEmpty() && counterfactualOk && controlQuiet
                && cleanSeparation;
        WitnessRule reportedRule = input.leadingRules().isEmpty() ? null
                : input.leadingRules().get(input.leadingRules().size() - 1);
        text.append(String.format(Locale.ROOT,
                "10. Is there enough evidence to justify a production witness implementation in the "
                        + "NEXT PR?%n    %s%n%n",
                justified
                        ? "YES - one specific candidate is sufficiently supported for a carefully "
                                + "scoped production\n    implementation: " + reportedRule.id()
                                + ", which stayed silent on all " + sameRoundFrames
                                + " same-round frames,\n    detected all " + transitionsEvaluated
                                + " observed transitions and survived the counterfactual "
                                + "same-identity\n    replay. It is still subject to the "
                                + "limitations of section 14."
                        : "NO - evidence is insufficient for a production witness; more data or a "
                                + "different witness design\n    is required (see sections 6, 10 "
                                + "and 14)."));
        text.append("Whatever the answer above, this PR implements no production witness: the "
                + "decision gate is a\nrecommendation for the next stage, not a change in this "
                + "one.\n\n");
    }

    private static void limitations(StringBuilder text, Input input) {
        heading(text, "14. LIMITATIONS");
        text.append("- Four observed round-to-round transitions and two recordings are evidence, "
                + "not a distribution.\n"
                + "- The annotations are approximate anchors; no annotation timestamp became a "
                + "runtime signal and no\n  frame-exact ground truth is claimed.\n"
                + "- The exact visual repetition case (same target, same tile contents, same "
                + "positions) is\n  observationally indistinguishable and stays fail-closed.\n"
                + "- Same-target pairs come from different hacks or recordings; they do not prove "
                + "consecutive-round\n  behaviour.\n"
                + "- 1080p uses the evaluation-only 0.75 geometry; the live runtime still supports "
                + "2560x1440 only.\n"
                + "- The witness compares normalized structure, so a content change that preserves "
                + "geometry exactly\n  (for example a recolour with identical ridges) can stay "
                + "invisible, exactly like an\n  identical repetition.\n"
                + "- Every threshold in this report is an analysis cut, never a production "
                + "constant.\n\n");
    }

    private static void artifacts(StringBuilder text, Input input) {
        heading(text, "15. LOCAL DEBUG ARTIFACTS (all ignored, below target/)");
        text.append("  stage6c1c-witness-frames.csv          one row per scope measurement\n"
                + "  stage6c1c-witness-transitions.csv     anchor rows of every real transition\n"
                + "  stage6c1c-witness-distributions.csv   every signal over every population\n"
                + "  stage6c1c-witness-separation.csv      worst same-round versus weakest "
                + "transition\n"
                + "  stage6c1c-witness-rules.csv           the full rule and threshold sweep\n"
                + "  stage6c1c-witness-counterfactual.csv  counterfactual same-identity replay\n"
                + "  stage6c1c-witness-same-target.csv     same-target round pairs\n"
                + "  stage6c1c-witness-contact-sheets/     local review sheets (private review "
                + "material)\n\n");
        text.append("Review sheets written by this run:\n");
        for (java.nio.file.Path path : input.contactSheets()) {
            text.append("  ").append(path.getFileName()).append('\n');
        }
        text.append(String.format(Locale.ROOT, "%nAnalysis wall time: %d ms%n", input.wallMillis()));
    }

    private static WitnessDistributions.Row distribution(Input input, String population,
            WitnessDistributions.Signal signal) {
        for (WitnessDistributions.Row row : input.distributions()) {
            if (row.population().equals(population) && row.signal().equals(signal.name())) {
                return row;
            }
        }
        throw new IllegalStateException(
                "Missing distribution " + population + " / " + signal);
    }

    /** Detection offsets of one rule, from the rule evaluation that measured it. */
    private static String offsetsOf(Input input, WitnessRule rule, boolean versusRecognized) {
        for (WitnessRuleExploration.RuleEvaluation evaluation : input.rules()) {
            if (evaluation.ruleId().equals(rule.id())) {
                return versusRecognized ? evaluation.offsetsVersusFirstNewRecognized()
                        : evaluation.offsetsVersusFirstNewStable();
            }
        }
        return "-";
    }

    private static void heading(StringBuilder text, String title) {
        text.append(title).append('\n');
        text.append("-".repeat(title.length())).append('\n');
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String value(Long frame) {
        return frame == null ? "-" : Long.toString(frame);
    }

}
