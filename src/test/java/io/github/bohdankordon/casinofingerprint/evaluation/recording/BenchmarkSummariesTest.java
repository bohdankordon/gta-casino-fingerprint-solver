package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.NegativeRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.PositiveRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.ConsensusSummaryRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.ResolutionSummary;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.RoundSummary;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Aggregate tests: the per-round, per-resolution and consensus summaries must count exactly what
 * the per-frame rows say, so a failure cannot be averaged away between the two.
 */
class BenchmarkSummariesTest {
    private static final RecordingRoundAnnotation ROUND = RecordingTestSupport.round(
            "recording_1440p", "2560x1440", 1, 1, 10.0, 12.0, FingerprintId.FP_4, 6, 5, 1, 4, false);
    private static final List<Integer> CORRECT = List.of(1, 4, 5, 6);

    @Test
    void roundSummaryCountsEveryBucketSeparately() {
        List<PositiveRow> rows = List.of(
                row(0, 10_000, RecordingTestSupport.recognized(FingerprintId.FP_4, CORRECT)),
                row(1, 10_033, RecordingTestSupport.recognized(FingerprintId.FP_4, CORRECT)),
                row(2, 10_066, RecordingTestSupport.recognized(FingerprintId.FP_3, CORRECT)),
                row(3, 10_100, RecordingTestSupport.uncertain()));

        RoundSummary summary = BenchmarkSummaries.roundSummary(ROUND, rows);

        assertEquals(4, summary.frames());
        assertEquals(2, summary.correctRecognized());
        assertEquals(1, summary.wrongRecognized());
        assertEquals(1, summary.uncertain());
        assertEquals(50.0, summary.correctPercent(), 1e-9);
        assertEquals(25.0, summary.wrongPercent(), 1e-9);
        assertEquals("H1R1", summary.scopeId());
    }

    @Test
    void roundSummaryUsesTheNearestRankConvention() {
        List<PositiveRow> rows = List.of(
                row(0, 10_000, RecordingTestSupport.recognized(FingerprintId.FP_4, CORRECT)),
                row(1, 10_033, RecordingTestSupport.recognized(FingerprintId.FP_4, CORRECT)),
                row(2, 10_066, RecordingTestSupport.recognized(FingerprintId.FP_4, CORRECT)),
                row(3, 10_100, RecordingTestSupport.recognized(FingerprintId.FP_4, CORRECT)));

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
                row(0, 10_000, RecordingTestSupport.recognized(FingerprintId.FP_4, CORRECT)),
                row(1, 10_033, RecordingTestSupport.uncertain()));
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
        assertEquals(2, positive.frames());
        assertEquals(1, positive.correct());
        assertEquals(1, positive.uncertain());
        assertEquals(0, positive.wrongRecognized());
        assertEquals(2, negative.frames());
        assertEquals(1, negative.falseRecognized());
        assertEquals(1, negative.uncertain());
        assertEquals(0, exhaustive.frames(), "an empty exhaustive pass must stay empty, not merged");
    }

    @Test
    void roundConsensusReportsStableCorrectWithLatency() {
        RecognitionDecision decision = RecordingTestSupport.recognized(FingerprintId.FP_4, CORRECT);
        List<PositiveRow> rows = List.of(
                row(0, 10_000, decision), row(1, 10_033, decision), row(2, 10_066, decision));
        ConsensusReplay replay = new ConsensusReplay();
        replay.accept(0, 10_000, decision);
        replay.accept(1, 10_033, decision);
        replay.accept(2, 10_066, decision);

        ConsensusSummaryRow summary = BenchmarkSummaries.roundConsensus(ROUND, rows, replay);

        assertTrue(summary.stableCorrect());
        assertFalse(summary.stableWrong());
        assertFalse(summary.stableFalse());
        assertEquals(10_000L, summary.firstCorrectRecognizedMs());
        assertEquals(10_066L, summary.firstStableCorrectMs());
        assertEquals(66L, summary.stableCorrectLatencyMs());
    }

    @Test
    void roundConsensusReportsStableWrongSeparately() {
        RecognitionDecision wrong = RecordingTestSupport.recognized(FingerprintId.FP_3, CORRECT);
        List<PositiveRow> rows = List.of(
                row(0, 10_000, wrong), row(1, 10_033, wrong), row(2, 10_066, wrong));
        ConsensusReplay replay = new ConsensusReplay();
        replay.accept(0, 10_000, wrong);
        replay.accept(1, 10_033, wrong);
        replay.accept(2, 10_066, wrong);

        ConsensusSummaryRow summary = BenchmarkSummaries.roundConsensus(ROUND, rows, replay);

        assertTrue(summary.stableWrong());
        assertFalse(summary.stableCorrect());
        assertEquals(10_066L, summary.firstStableWrongOrFalseMs());
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
        assertEquals(5_400L, summary.firstStableWrongOrFalseMs());
        assertTrue(summary.detail().contains("outside-hack-windows"), summary.detail());
    }

    @Test
    void csvHeadersStayStable() {
        assertTrue(BenchmarkSummaries.roundCsv(List.of()).startsWith("source_id,resolution,hack_id"));
        assertTrue(BenchmarkSummaries.resolutionCsv(List.of()).startsWith("source_id,resolution,population"));
        assertTrue(BenchmarkSummaries.consensusCsv(List.of()).startsWith("source_id,resolution,scope"));
        assertTrue(BenchmarkRows.positiveCsv(List.of()).startsWith("source_id,resolution,hack_id"));
        assertTrue(BenchmarkRows.negativeCsv(List.of()).startsWith("source_id,resolution,frame_index"));
    }

    private static PositiveRow row(long frameIndex, long timestampMs, RecognitionDecision decision) {
        return PositiveRow.from(ROUND, frameIndex, timestampMs, decision);
    }
}
