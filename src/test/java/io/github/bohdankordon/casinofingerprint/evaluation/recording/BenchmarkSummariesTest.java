package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.NegativeRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.PositiveRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.ConsensusSummaryRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.ReplayClassification;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.ReplayOutcome;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.ResolutionSummary;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.RoundSummary;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Aggregate and consensus-category tests.
 *
 * <p>The summaries must count exactly what the per-frame rows say, and the consensus categories must
 * separate a correct answer, a previous-round carryover, an unexplained mismatch, a hack-transition
 * answer and a strict-negative false answer.
 */
class BenchmarkSummariesTest {
    private static final RecordingRoundAnnotation PREVIOUS = RecordingTestSupport.round(
            "recording_1440p", "2560x1440", 1, 1, 10.0, 12.0, FingerprintId.FP_4, 6, 5, 1, 4, false);
    private static final RecordingRoundAnnotation ROUND = RecordingTestSupport.round(
            "recording_1440p", "2560x1440", 1, 2, 12.5, 14.0, FingerprintId.FP_3, 2, 4, 6, 7, false);
    private static final List<Integer> ROUND_SET = List.of(2, 4, 6, 7);
    private static final List<Integer> PREVIOUS_SET = List.of(1, 4, 5, 6);

    @Test
    void roundSummaryCountsEveryCategorySeparately() {
        List<PositiveRow> rows = List.of(
                row(0, 12_500, RecordingTestSupport.recognized(FingerprintId.FP_3, ROUND_SET)),
                row(1, 12_533, RecordingTestSupport.recognized(FingerprintId.FP_3, ROUND_SET)),
                row(2, 12_566, RecordingTestSupport.recognized(FingerprintId.FP_4, PREVIOUS_SET)),
                row(3, 12_600, RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3))),
                row(4, 12_633, RecordingTestSupport.uncertain()));

        RoundSummary summary = BenchmarkSummaries.roundSummary(ROUND, rows);

        assertEquals(5, summary.frames());
        assertEquals(2, summary.currentRoundMatch());
        assertEquals(1, summary.previousRoundCarryover());
        assertEquals(1, summary.unexplainedMismatch());
        assertEquals(1, summary.uncertain());
        assertEquals(2, summary.nominalRoundDisagreements());
        assertEquals(40.0, summary.currentRoundPercent(), 1e-9);
        assertEquals(20.0, summary.carryoverPercent(), 1e-9);
        assertEquals(20.0, summary.unexplainedPercent(), 1e-9);
        assertEquals("H1R2", summary.scopeId());
    }

    @Test
    void roundSummaryUsesTheNearestRankConvention() {
        List<PositiveRow> rows = List.of(
                row(0, 12_500, RecordingTestSupport.recognized(FingerprintId.FP_3, ROUND_SET)),
                row(1, 12_533, RecordingTestSupport.recognized(FingerprintId.FP_3, ROUND_SET)),
                row(2, 12_566, RecordingTestSupport.recognized(FingerprintId.FP_3, ROUND_SET)),
                row(3, 12_600, RecordingTestSupport.recognized(FingerprintId.FP_3, ROUND_SET)));

        RoundSummary summary = BenchmarkSummaries.roundSummary(ROUND, rows);
        double[] scores = Percentiles.sortedCopy(
                rows.stream().map(PositiveRow::bestTargetScore).toList());

        assertEquals(Percentiles.minimum(scores), summary.minTargetScore(), 1e-12);
        assertEquals(Percentiles.p05(scores), summary.p05TargetScore(), 1e-12);
        assertEquals(Percentiles.median(scores), summary.medianTargetScore(), 1e-12);
    }

    @Test
    void resolutionSummarySeparatesPositiveAndNegativePopulations() {
        RecordingSource source = new RecordingSource("recording_1440p", "casino-heist-1440p.mkv",
                "matroska", 2560, 1440, 30.0, 6000, 200.0, 1000L, "A".repeat(64));
        List<PositiveRow> positives = List.of(
                row(0, 12_500, RecordingTestSupport.recognized(FingerprintId.FP_3, ROUND_SET)),
                row(1, 12_533, RecordingTestSupport.recognized(FingerprintId.FP_4, PREVIOUS_SET)),
                row(2, 12_566, RecordingTestSupport.uncertain()));
        List<NegativeRow> negatives = List.of(
                NegativeRow.from("recording_1440p", "2560x1440", 0, 0, RecordingTestSupport.uncertain()),
                NegativeRow.from("recording_1440p", "2560x1440", 6, 200,
                        RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3))));

        List<ResolutionSummary> summaries = BenchmarkSummaries.resolutionSummaries(
                List.of(source), positives, Map.of("recording_1440p", negatives),
                Map.of("recording_1440p", List.of()));

        ResolutionSummary positive = summaries.stream()
                .filter(row -> row.population().equals("POSITIVE")).findFirst().orElseThrow();
        ResolutionSummary negative = summaries.stream()
                .filter(row -> row.population().equals("NEGATIVE_SAMPLED")).findFirst().orElseThrow();
        ResolutionSummary exhaustive = summaries.stream()
                .filter(row -> row.population().equals("NEGATIVE_EXHAUSTIVE")).findFirst().orElseThrow();
        assertEquals(3, positive.frames());
        assertEquals(1, positive.currentRoundMatch());
        assertEquals(1, positive.previousRoundCarryover());
        assertEquals(0, positive.unexplainedMismatch());
        assertEquals(1, positive.uncertain());
        assertEquals(2, negative.frames());
        assertEquals(1, negative.falseRecognized());
        assertEquals(1, negative.uncertain());
        assertEquals(0, exhaustive.frames(), "an empty exhaustive pass must stay empty, not merged");
    }

    @Test
    void roundConsensusReportsStableCorrectWithLatency() {
        RecognitionDecision decision = RecordingTestSupport.recognized(FingerprintId.FP_3, ROUND_SET);
        List<PositiveRow> rows = List.of(
                row(0, 12_500, decision), row(1, 12_533, decision), row(2, 12_566, decision));
        ConsensusReplay replay = new ConsensusReplay();
        replay.accept(0, 12_500, decision);
        replay.accept(1, 12_533, decision);
        replay.accept(2, 12_566, decision);

        ConsensusSummaryRow summary =
                BenchmarkSummaries.roundConsensus(ROUND, PREVIOUS, rows, replay);

        assertTrue(summary.stableCorrect());
        assertFalse(summary.stablePreviousRoundCarryover());
        assertFalse(summary.stableUnexplainedMismatch());
        assertFalse(summary.stableFalse());
        assertEquals(12_500L, summary.firstCorrectRecognizedMs());
        assertEquals(12_566L, summary.firstStableCorrectMs());
        assertEquals(66L, summary.stableCorrectLatencyMs());
        assertTrue(summary.detail().contains("STABLE_CORRECT"), summary.detail());
    }

    @Test
    void roundConsensusReportsThePreviousRoundAnswerAsCarryoverNotAsAnError() {
        RecognitionDecision decision = RecordingTestSupport.recognized(FingerprintId.FP_4, PREVIOUS_SET);
        List<PositiveRow> rows = List.of(
                row(0, 12_500, decision), row(1, 12_533, decision), row(2, 12_566, decision));
        ConsensusReplay replay = new ConsensusReplay();
        replay.accept(0, 12_500, decision);
        replay.accept(1, 12_533, decision);
        replay.accept(2, 12_566, decision);

        ConsensusSummaryRow summary =
                BenchmarkSummaries.roundConsensus(ROUND, PREVIOUS, rows, replay);

        assertTrue(summary.stablePreviousRoundCarryover());
        assertFalse(summary.stableUnexplainedMismatch());
        assertFalse(summary.stableCorrect());
        assertEquals(12_566L, summary.firstStableNonCorrectMs());
        assertTrue(summary.detail().contains("STABLE_PREVIOUS_ROUND_CARRYOVER"), summary.detail());
    }

    @Test
    void roundConsensusReportsAnUnexplainedStableAnswerAsAnUnexplainedMismatch() {
        RecognitionDecision decision =
                RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3));
        List<PositiveRow> rows = List.of(
                row(0, 12_500, decision), row(1, 12_533, decision), row(2, 12_566, decision));
        ConsensusReplay replay = new ConsensusReplay();
        replay.accept(0, 12_500, decision);
        replay.accept(1, 12_533, decision);
        replay.accept(2, 12_566, decision);

        ConsensusSummaryRow summary =
                BenchmarkSummaries.roundConsensus(ROUND, PREVIOUS, rows, replay);

        assertTrue(summary.stableUnexplainedMismatch());
        assertFalse(summary.stablePreviousRoundCarryover());
        assertFalse(summary.stableCorrect());
    }

    @Test
    void stableAnswerInAHackTransitionIsNotAWrongAnswer() {
        RecognitionDecision decision =
                RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3));
        ConsensusReplay replay = new ConsensusReplay();
        // 13.9 s lies inside the hack window but outside every approximate round interval
        // (H1R1 is 10.0-12.0 s and H1R2 is 12.5-14.0 s, so 14.2 s is the transition).
        replay.accept(0, 14_200, decision);
        replay.accept(1, 14_400, decision);
        replay.accept(2, 14_600, decision);

        ReplayClassification classification = BenchmarkSummaries.classifyEvents(
                replay.stableEvents(), List.of(PREVIOUS, ROUND),
                List.of(new TimeWindow(9.5, 14.5)), List.of(new TimeWindow(7.5, 16.5)));

        assertEquals(1, classification.unlabeledTransition().size());
        assertEquals(0, classification.unexplainedMismatch().size());
        assertEquals(0, classification.falseRecognized().size());
        assertEquals(0, classification.correct().size());
        assertEquals(1, BenchmarkSummaries.eventsFor(classification,
                ReplayOutcome.STABLE_UNLABELED_TRANSITION).size());
    }

    @Test
    void stableAnswerInTheTransitionZoneAroundAHackIsAlsoATransition() {
        RecognitionDecision decision =
                RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3));
        ConsensusReplay replay = new ConsensusReplay();
        replay.accept(0, 8_000, decision);
        replay.accept(1, 8_200, decision);
        replay.accept(2, 8_400, decision);

        ReplayClassification classification = BenchmarkSummaries.classifyEvents(
                replay.stableEvents(), List.of(PREVIOUS, ROUND),
                List.of(new TimeWindow(9.5, 14.5)), List.of(new TimeWindow(7.5, 16.5)));

        assertEquals(1, classification.unlabeledTransition().size());
        assertEquals(0, classification.falseRecognized().size());
    }

    @Test
    void stableAnswerOutsideThePaddedHackRegionIsAFalsePositive() {
        RecognitionDecision decision =
                RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3));
        ConsensusReplay replay = new ConsensusReplay();
        replay.accept(0, 20_000, decision);
        replay.accept(1, 20_200, decision);
        replay.accept(2, 20_400, decision);

        ReplayClassification classification = BenchmarkSummaries.classifyEvents(
                replay.stableEvents(), List.of(PREVIOUS, ROUND),
                List.of(new TimeWindow(9.5, 14.5)), List.of(new TimeWindow(7.5, 16.5)));

        assertEquals(1, classification.falseRecognized().size());
        assertEquals(0, classification.unlabeledTransition().size());

        ConsensusSummaryRow summary = BenchmarkSummaries.fullReplayConsensus(
                "recording_1440p", "2560x1440", replay, List.of(new TimeWindow(9.5, 14.5)),
                List.of(new TimeWindow(7.5, 16.5)), List.of(PREVIOUS, ROUND));
        assertTrue(summary.stableFalse());
        assertFalse(summary.stableUnexplainedMismatch());
        assertFalse(summary.stableCorrect());
        assertEquals(20_400L, summary.firstStableNonCorrectMs());
    }

    @Test
    void fullReplayClassifiesEveryZone() {
        ConsensusReplay replay = new ConsensusReplay();
        // inside a round, matching the nominal answer
        feed(replay, 10_500, RecordingTestSupport.recognized(FingerprintId.FP_4, PREVIOUS_SET));
        // inside the next round, still the previous round's answer
        feed(replay, 12_600, RecordingTestSupport.recognized(FingerprintId.FP_4, PREVIOUS_SET));
        // inside the hack window but outside every round interval
        feed(replay, 14_200, RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3)));
        // outside the padded hack region: strict negative gameplay
        feed(replay, 20_000, RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3)));

        ConsensusSummaryRow summary = BenchmarkSummaries.fullReplayConsensus(
                "recording_1440p", "2560x1440", replay, List.of(new TimeWindow(9.5, 14.5)),
                List.of(new TimeWindow(7.5, 16.5)), List.of(PREVIOUS, ROUND));

        assertTrue(summary.stableCorrect());
        assertTrue(summary.stablePreviousRoundCarryover());
        assertTrue(summary.stableUnlabeledTransition());
        assertTrue(summary.stableFalse());
        assertFalse(summary.stableUnexplainedMismatch());
        assertTrue(summary.detail().contains("STABLE_PREVIOUS_ROUND_CARRYOVER"), summary.detail());
        assertTrue(summary.detail().contains("STABLE_UNLABELED_TRANSITION"), summary.detail());
        assertTrue(summary.detail().contains("STABLE_FALSE"), summary.detail());
    }

    @Test
    void negativeConsensusTreatsAnyStableAnswerAsFalse() {
        RecognitionDecision decision = RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3));
        ConsensusReplay replay = new ConsensusReplay();
        replay.accept(0, 5_000, decision);
        replay.accept(1, 5_200, decision);
        replay.accept(2, 5_400, decision);

        ConsensusSummaryRow summary = BenchmarkSummaries.negativeConsensus(
                "recording_1440p", "2560x1440", replay,
                BenchmarkSummaries.SCOPE_STRICT_NEGATIVE, "outside-hack-windows", List.of());

        assertTrue(summary.stableFalse());
        assertFalse(summary.stableCorrect());
        assertEquals(5_400L, summary.firstStableNonCorrectMs());
        assertTrue(summary.detail().contains("STABLE_FALSE"), summary.detail());
    }

    @Test
    void csvHeadersStayStable() {
        assertTrue(BenchmarkSummaries.roundCsv(List.of())
                .startsWith("source_id,resolution,hack_id,round_id,scope_id,frames,current_round_match"));
        assertTrue(BenchmarkSummaries.resolutionCsv(List.of())
                .startsWith("source_id,resolution,population,frames,current_round_match"));
        assertTrue(BenchmarkSummaries.consensusCsv(List.of())
                .startsWith("source_id,resolution,scope,scope_id,required_consecutive_frames,stable_correct"));
        assertTrue(BenchmarkRows.positiveCsv(List.of()).startsWith("source_id,resolution,hack_id"));
        assertTrue(BenchmarkRows.negativeCsv(List.of()).startsWith("source_id,resolution,frame_index"));
    }

    private static void feed(ConsensusReplay replay, long timestampMs, RecognitionDecision decision) {
        replay.accept(timestampMs, timestampMs, decision);
        replay.accept(timestampMs + 1, timestampMs + 1, decision);
        replay.accept(timestampMs + 2, timestampMs + 2, decision);
        // Break the streak so the next zone starts its own stable episode.
        replay.accept(timestampMs + 3, timestampMs + 3, RecordingTestSupport.uncertain());
    }

    private static PositiveRow row(long frameIndex, long timestampMs, RecognitionDecision decision) {
        return PositiveRow.from(ROUND, PREVIOUS, frameIndex, timestampMs, decision);
    }
}
