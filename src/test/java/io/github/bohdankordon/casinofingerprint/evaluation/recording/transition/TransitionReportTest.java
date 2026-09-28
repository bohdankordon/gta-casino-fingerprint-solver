package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Guard;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Row;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.Entry;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.Exit;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.InterRound;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The report must render every section for every transition kind with plain synthetic data. CI has
 * no recordings, so this test is the guard that keeps the report path (and its number formatting)
 * working without one.
 */
class TransitionReportTest {
    private static final AnswerIdentity OLD =
            TransitionTestSupport.identity(FingerprintId.FP_4, 1, 4, 5, 6);
    private static final AnswerIdentity NEW =
            TransitionTestSupport.identity(FingerprintId.FP_3, 2, 4, 6, 7);

    @Test
    void reportRendersEverySectionFromSyntheticTransitionData() {
        RoundTransitionAnalysis.Options options =
                new RoundTransitionAnalysis.Options(Path.of(System.getProperty("user.dir")));
        List<TransitionSummary.Row> summaries = List.of(entry(), interRound(), exit());
        List<Row> guardRows = guardRows();
        List<TransitionRun> runs = List.of(
                new TransitionRun(TransitionTestSupport.SOURCE_ID, TransitionTestSupport.RESOLUTION,
                        1, 0, 0, 2, 10_000, 10_066, 3, LiveRecognitionState.STABLE_RECOGNIZED,
                        OLD, 3, 5),
                new TransitionRun(TransitionTestSupport.SOURCE_ID, TransitionTestSupport.RESOLUTION,
                        1, 1, 3, 5, 10_099, 10_165, 3, LiveRecognitionState.UNCERTAIN, null, 0, 0));

        String report = TransitionReport.render(options, List.of(sourceRun()), summaries, guardRows,
                runs, List.of("recording_test H1 frame 7 10.231 s FP_1[1;3;4;6]"),
                List.of(Path.of("sheet.png")), 12_345L);

        assertTrue(report.contains("Stage 6C.1A full-rate round-transition characterization"));
        assertTrue(report.contains("Source integrity and population"));
        assertTrue(report.contains("ROUND_1 -> ROUND_2 transitions"));
        assertTrue(report.contains("HACK_ENTRY -> ROUND_1 transitions"));
        assertTrue(report.contains("ROUND_2 -> HACK_EXIT transitions"));
        assertTrue(report.contains("Consensus reset experiment"));
        assertTrue(report.contains("Offline lifecycle guard simulation"));
        assertTrue(report.contains("Same-answer consecutive rounds (UNPROVEN CASE)"));
        assertTrue(report.contains("Decision gate for Stage 6C.1B"));
        assertTrue(report.contains("Appendix: decision / consensus runs per transition region"));
        assertTrue(report.contains("OBSERVED_CARRYOVER_LINE"));
        assertTrue(report.contains("12345 ms wall"), "the source run wall time is rendered");
        assertFalse(report.contains("%"), "no unexpanded format specifier survives rendering");
    }

    private static RoundTransitionAnalysis.SourceRun sourceRun() {
        return new RoundTransitionAnalysis.SourceRun(TransitionTestSupport.SOURCE_ID,
                TransitionTestSupport.RESOLUTION, "synthetic geometry", "synthetic layout", 1,
                1234L, 2345L, 30.0, 200.0, 322_082_164L, "AB".repeat(32), 12_345L);
    }

    private static Entry entry() {
        return new Entry(TransitionTestSupport.SOURCE_ID + "-H1-ENTRY",
                TransitionTestSupport.SOURCE_ID, TransitionTestSupport.RESOLUTION, 1, 16_000L,
                14_000L, 33_000L, 570L, OLD, 12L, 16_400L, OLD, 14L, 16_466L, OLD, 16L, 16_533L,
                OLD, 533L, 10L, 11L, 16_367L, 0L, List.of(), false, 0L, 0L, "");
    }

    private static InterRound interRound() {
        return new InterRound(TransitionTestSupport.SOURCE_ID + "-H1-R1R2",
                TransitionTestSupport.SOURCE_ID, TransitionTestSupport.RESOLUTION, 1, 34_000L,
                14_000L, 51_000L, 1110L, OLD, NEW, 33_000L, 33_000L, 33_100L, 33_100L, 34_033L,
                34_033L, 34_100L, 34_100L, 2L, 99L, false, 1000L, 5L, 166L, 0L, 0L, Boolean.TRUE,
                34_100L, OLD, 2L, "OBSERVED_CARRYOVER_LINE");
    }

    private static Exit exit() {
        return new Exit(TransitionTestSupport.SOURCE_ID + "-H1-EXIT",
                TransitionTestSupport.SOURCE_ID, TransitionTestSupport.RESOLUTION, 1, 50_000L,
                48_000L, 52_000L, 120L, NEW, 49_000L, 49_000L, 49_000L, 49_000L, 49_033L, 49_033L,
                49_033L, 49_033L, false, false, 0L, false, false, 0L, 0L, 0L, "");
    }

    private static List<Row> guardRows() {
        List<Row> rows = new ArrayList<>();
        for (Guard guard : LifecycleGuardSimulation.guards()) {
            rows.add(new Row(guard.name(), TransitionTestSupport.SOURCE_ID,
                    TransitionTestSupport.RESOLUTION, 1, TransitionTestSupport.SOURCE_ID + "-H1-R1R2",
                    OLD, 33_100L, NEW, 34_033L, NEW, Boolean.FALSE, Boolean.TRUE, 1L, List.of(NEW),
                    1L, Boolean.FALSE, Boolean.TRUE, 0L, "synthetic guard row"));
        }
        return rows;
    }
}
