package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import io.github.bohdankordon.casinofingerprint.matching.FragmentScoreMatrix;
import io.github.bohdankordon.casinofingerprint.matching.SimilarityScore;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.AssignmentSearchResult;
import io.github.bohdankordon.casinofingerprint.recognition.ConstrainedAssignmentSolver;
import io.github.bohdankordon.casinofingerprint.recognition.PuzzleRecognitionEngine;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionPolicy;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Counterfactual same-identity replay: what happens when the actual pixels of a round-to-round
 * transition are kept, but the lifecycle is told that the old and the new answer identity are the
 * same.
 *
 * <p>Evaluation only, and explicitly synthetic: the identity override exists so an A -&gt; A round
 * pair - which does not occur in the recordings - can be approximated with real transition content.
 * It proves exactly one thing: IF the visual content changes like these observed transitions, the
 * candidate witness does not need an identity change to see it. It does NOT prove that every real
 * same-identity round pair changes its visual content.
 *
 * <p>Two independent demonstrations per transition:
 *
 * <ol>
 *   <li><b>Witness independence.</b> The witness rows are rebuilt with the round-2 identity
 *       rewritten to the round-1 identity, and the candidate rule is evaluated again. The rule
 *       reads {@link PuzzleContentFeatures} only, so the outcome must be bit-identical to the real
 *       evaluation, and it is reported as such.</li>
 *   <li><b>Lifecycle consequence.</b> The real per-frame consensus timeline (state, identity,
 *       streak) is replayed through an UNMODIFIED production {@link RoundLifecycleTracker} with the
 *       same identity override. The second round never becomes ready - the production tracker fails
 *       closed on a repeated consumed identity, which is the limitation this stage is about.</li>
 * </ol>
 */
public final class CounterfactualIdentityReplay {
    private static final int REQUIRED_STREAK =
            io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker
                    .DEFAULT_REQUIRED_CONSECUTIVE_FRAMES;

    /** One counterfactual transition result. */
    public record Row(
            String transitionId,
            String actualOldIdentity,
            String actualNewIdentity,
            String counterfactualIdentityClaim,
            String witnessRule,
            boolean featuresIdenticalUnderCounterfactual,
            boolean witnessFiredAtTransition,
            long detectionFrame,
            long offsetVsFirstNewRecognized,
            long offsetVsFirstNewStable,
            long firedFramesInTransitionWindow,
            int lifecycleReadyEvents,
            int lifecycleSuppressedOnsets,
            boolean secondRoundReadyUnderCounterfactual,
            String notes) {

        /** Header of {@code target/stage6c1c-witness-counterfactual.csv}. */
        public static final String HEADER =
                "transition_id,actual_old_identity,actual_new_identity,counterfactual_identity_claim,"
                        + "witness_rule,features_identical_under_counterfactual,"
                        + "witness_fired_at_transition,detection_frame,"
                        + "offset_vs_first_new_recognized,offset_vs_first_new_stable,"
                        + "fired_frames_in_transition_window,lifecycle_ready_events,"
                        + "lifecycle_suppressed_onsets,second_round_ready_under_counterfactual,notes";

        /** One CSV line. */
        public String csv() {
            return transitionId + ',' + actualOldIdentity + ',' + actualNewIdentity + ','
                    + counterfactualIdentityClaim + ',' + witnessRule + ','
                    + featuresIdenticalUnderCounterfactual + ',' + witnessFiredAtTransition + ','
                    + detectionFrame + ',' + offsetVsFirstNewRecognized + ','
                    + offsetVsFirstNewStable + ',' + firedFramesInTransitionWindow + ','
                    + lifecycleReadyEvents + ',' + lifecycleSuppressedOnsets + ','
                    + secondRoundReadyUnderCounterfactual + ',' + notes;
        }

        /** Renders the counterfactual CSV, header included. */
        public static String csv(List<Row> rows) {
            StringBuilder text = new StringBuilder(HEADER).append('\n');
            for (Row row : rows) {
                text.append(row.csv()).append('\n');
            }
            return text.toString();
        }
    }

    /** Outcome of the synthetic lifecycle replay of one hack. */
    public record LifecycleOutcome(int readyEvents, int suppressedOnsets,
            boolean secondRoundReady, String recordedIdentities) {
    }

    private CounterfactualIdentityReplay() {
    }

    /** Runs both demonstrations for one analyzed hack. */
    public static Row run(WitnessRule rule, WitnessRuleExploration.HackObservation hack,
            List<WitnessAnalyzer.FrameState> frames) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(hack, "hack");
        Objects.requireNonNull(frames, "frames");
        String oldIdentity = hack.scope().roundOneAnswer().code();
        String newIdentity = hack.scope().roundTwoAnswer().code();
        Long firstNewRecognized = hack.events().firstNewRecognizedFrame();
        Long firstNewStable = hack.events().firstNewStableFrame();

        List<WitnessFrameRow> overridden = new ArrayList<>(hack.rows().size());
        boolean identicalFeatures = true;
        for (WitnessFrameRow row : hack.rows()) {
            WitnessFrameRow rewritten = row;
            if (row.answerIdentity().equals(newIdentity)) {
                rewritten = withIdentity(row, oldIdentity);
            }
            if (!rewritten.features().equals(row.features())) {
                identicalFeatures = false;
            }
            overridden.add(rewritten);
        }
        WitnessRuleExploration.HackObservation counterfactualHack =
                new WitnessRuleExploration.HackObservation(hack.scope(), hack.events(), overridden);
        WitnessRuleExploration.RuleEvaluation real = WitnessRuleExploration.evaluate(rule, List.of(hack));
        WitnessRuleExploration.RuleEvaluation counterfactual =
                WitnessRuleExploration.evaluate(rule, List.of(counterfactualHack));

        long detectionFrame = -1;
        long firedInWindow = 0;
        if (firstNewRecognized != null && firstNewStable != null) {
            long searchEnd = firstNewStable + WitnessAnalyzer.TRANSITION_OBSERVATION_FRAMES;
            for (WitnessFrameRow row : counterfactualHack.transitionRows()) {
                if (row.frameIndex() < firstNewRecognized || row.frameIndex() > searchEnd
                        || !rule.fires(row)) {
                    continue;
                }
                firedInWindow++;
                if (detectionFrame < 0 || row.frameIndex() < detectionFrame) {
                    detectionFrame = row.frameIndex();
                }
            }
        }
        boolean fired = detectionFrame >= 0;
        boolean identicalOutcome = real.sameRoundFalseTriggers()
                == counterfactual.sameRoundFalseTriggers()
                && real.transitionsDetected() == counterfactual.transitionsDetected();
        LifecycleOutcome lifecycle = lifecycleUnderIdentitySubstitution(frames,
                hack.scope().roundOneAnswer(), hack.scope().roundTwoAnswer());
        String notes = "pixels unchanged; identity metadata only; witness outcome identical="
                + identicalOutcome + "; proves independence from RecognitionIdentity, not that "
                + "every same-identity round changes content";
        return new Row(hack.scope().hackLabel() + "-R1R2", oldIdentity, newIdentity,
                "old identity claimed for every frame of round 2", rule.id(), identicalFeatures,
                fired, detectionFrame,
                detectionFrame < 0 || firstNewRecognized == null ? Long.MIN_VALUE
                        : detectionFrame - firstNewRecognized,
                detectionFrame < 0 || firstNewStable == null ? Long.MIN_VALUE
                        : detectionFrame - firstNewStable,
                firedInWindow, lifecycle.readyEvents(), lifecycle.suppressedOnsets(),
                lifecycle.secondRoundReady(), notes);
    }

    /**
     * Replays the real per-frame consensus timeline through the unmodified production lifecycle
     * tracker while every frame whose identity equals {@code newIdentity} claims
     * {@code oldIdentity} instead.
     */
    public static LifecycleOutcome lifecycleUnderIdentitySubstitution(
            List<WitnessAnalyzer.FrameState> frames, RecognitionIdentity oldIdentity,
            RecognitionIdentity newIdentity) {
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(oldIdentity, "oldIdentity");
        Objects.requireNonNull(newIdentity, "newIdentity");
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        int readyEvents = 0;
        int suppressedOnsets = 0;
        boolean secondRoundReady = false;
        boolean firstReadySeen = false;
        List<String> recorded = new ArrayList<>();
        for (WitnessAnalyzer.FrameState frame : frames) {
            RecognitionIdentity identity = newIdentity.equals(frame.identity())
                    ? oldIdentity
                    : frame.identity();
            RoundLifecycleStatus update = tracker.accept(statusOf(frame, identity));
            if (update.newRoundReady()) {
                readyEvents++;
                RecognitionIdentity ready = update.ready().orElseThrow();
                recorded.add(ready.code());
                if (firstReadySeen) {
                    secondRoundReady = true;
                }
                firstReadySeen = true;
                tracker.consumeReadyRound();
            }
            if (update.consumedIdentityRepeated()) {
                suppressedOnsets++;
            }
        }
        return new LifecycleOutcome(readyEvents, suppressedOnsets, secondRoundReady,
                String.join("|", recorded));
    }

    private static WitnessFrameRow withIdentity(WitnessFrameRow row, String identity) {
        return new WitnessFrameRow(row.sourceId(), row.resolution(), row.hackId(), row.roundScope(),
                row.baselineId(), row.scope(), row.frameIndex(), row.timestampMs(),
                row.decisionStatus(), identity, row.consensusState(), row.streak(),
                row.uncertaintyReasons(), row.features());
    }

    private static LiveRecognitionStatus statusOf(WitnessAnalyzer.FrameState frame,
            RecognitionIdentity identity) {
        return switch (frame.consensusState()) {
            case WAITING -> LiveRecognitionStatus.waiting();
            case UNCERTAIN, CAPTURE_ERROR, UNSUPPORTED_FRAME ->
                    LiveRecognitionStatus.uncertain(syntheticUncertain());
            case RECOGNIZED -> LiveRecognitionStatus.recognized(
                    syntheticRecognized(identity.fingerprint(), identity.candidates()));
            case CANDIDATE_RECOGNITION -> LiveRecognitionStatus.candidate(
                    syntheticRecognized(identity.fingerprint(), identity.candidates()),
                    Math.max(1, Math.min(frame.streak(), REQUIRED_STREAK)), REQUIRED_STREAK);
            case STABLE_RECOGNIZED -> LiveRecognitionStatus.stable(
                    syntheticRecognized(identity.fingerprint(), identity.candidates()),
                    Math.max(REQUIRED_STREAK, frame.streak()), REQUIRED_STREAK);
        };
    }

    /**
     * Strong synthetic decision for one answer, built through the production Stage 4 decision path
     * with a hand-built fragment matrix - the same construction the Stage 5 tests use. The decision
     * is synthetic; the tracker that consumes it is the unmodified production one.
     */
    private static RecognitionDecision syntheticRecognized(FingerprintId fingerprint,
            List<Integer> selected) {
        double[][] values = new double[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragment = 0; fragment < 4; fragment++) {
                values[candidate][fragment] = 0.10;
            }
        }
        for (int fragment = 0; fragment < 4; fragment++) {
            values[selected.get(fragment)][fragment] = 0.95;
        }
        AssignmentSearchResult assignment =
                new ConstrainedAssignmentSolver().solve(matrix(values));
        return PuzzleRecognitionEngine.decide(fingerprint, 0.61, 0.18, assignment,
                RecognitionPolicy.defaultPolicy());
    }

    private static RecognitionDecision syntheticUncertain() {
        double[][] values = new double[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragment = 0; fragment < 4; fragment++) {
                values[candidate][fragment] = 0.10;
            }
        }
        for (int index = 0; index < 4; index++) {
            values[index][index] = 0.20;
        }
        AssignmentSearchResult assignment =
                new ConstrainedAssignmentSolver().solve(matrix(values));
        return PuzzleRecognitionEngine.decide(FingerprintId.FP_2, 0.20, 0.05, assignment,
                RecognitionPolicy.defaultPolicy());
    }

    private static FragmentScoreMatrix matrix(double[][] values) {
        SimilarityScore[][] scores = new SimilarityScore[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragment = 0; fragment < 4; fragment++) {
                scores[candidate][fragment] = new SimilarityScore(values[candidate][fragment]);
            }
        }
        return new FragmentScoreMatrix(scores);
    }

    /** Renders one counterfactual row for the report. */
    public static String describe(Row row) {
        return String.format(Locale.ROOT,
                "%s: rule %s fired=%s at frame %d (%+d vs first new recognized, %+d vs first new "
                        + "stable); lifecycle ready=%d suppressed=%d secondRoundReady=%s",
                row.transitionId(), row.witnessRule(), row.witnessFiredAtTransition(),
                row.detectionFrame(), row.offsetVsFirstNewRecognized(),
                row.offsetVsFirstNewStable(), row.lifecycleReadyEvents(),
                row.lifecycleSuppressedOnsets(), row.secondRoundReadyUnderCounterfactual());
    }
}
