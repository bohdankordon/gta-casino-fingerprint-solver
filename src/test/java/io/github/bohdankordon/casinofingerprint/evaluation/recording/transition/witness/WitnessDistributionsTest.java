package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Distribution arithmetic and population definitions. */
class WitnessDistributionsTest {
    @Test
    void percentilesAndWorstFramesFollowTheMeasuredRows() {
        List<WitnessFrameRow> rows = new ArrayList<>();
        double[] similarities = {0.95, 0.90, 0.80, 0.70, 0.60};
        for (int index = 0; index < similarities.length; index++) {
            rows.add(WitnessTestSupport.row("recording_test", "H1R1", "recording_test-H1R1",
                    WitnessScope.SAME_ROUND, index, index * 33L, "FP_1[0;1;2;3]",
                    WitnessTestSupport.features(similarities[index],
                            List.of(0.95, 0.95, 0.95, 0.95, 0.95, 0.95, 0.95, 0.95))));
        }
        List<WitnessDistributions.Row> distributions = WitnessDistributions.compute(rows);
        WitnessDistributions.Row target = find(distributions, "SAME_ROUND_ALL",
                WitnessDistributions.Signal.W1_TARGET_SIMILARITY);
        assertEquals(5, target.frames());
        assertEquals(0.60, target.minimum(), 1e-9);
        assertEquals(0.80, target.p50(), 1e-9);
        assertEquals(0.95, target.maximum(), 1e-9);
        assertEquals(0.60, target.worst(), 1e-9);
        assertTrue(target.worstFrame().contains("f4"), "worst frame must be reported");

        WitnessDistributions.Row raw = find(distributions, "SAME_ROUND_ALL",
                WitnessDistributions.Signal.W0_RAW_PANEL_MEAN_ABS_DELTA);
        assertEquals(0.0, raw.worst(), 1e-9, "a higher-is-change signal is worst at its maximum");
    }

    @Test
    void roundScopesSplitTheSameRoundPopulationWithoutMixingThem() {
        List<WitnessFrameRow> rows = List.of(
                WitnessTestSupport.row("recording_test", "H1R1", "recording_test-H1R1",
                        WitnessScope.SAME_ROUND, 0, 0, "FP_1[0;1;2;3]",
                        WitnessTestSupport.features(0.95, List.of(0.95, 0.95, 0.95, 0.95, 0.95, 0.95,
                                0.95, 0.95))),
                WitnessTestSupport.row("recording_test", "H1R2", "recording_test-H1R2",
                        WitnessScope.SAME_ROUND, 1, 33, "FP_2[4;5;6;7]",
                        WitnessTestSupport.features(0.90, List.of(0.95, 0.95, 0.95, 0.95, 0.95, 0.95,
                                0.95, 0.95))),
                WitnessTestSupport.row("recording_test", "H1ENTRY", "recording_test-H1R1",
                        WitnessScope.ENTRY, 2, 66, null,
                        WitnessTestSupport.features(0.10, List.of(0.10, 0.10, 0.10, 0.10, 0.10, 0.10,
                                0.10, 0.10))));
        List<WitnessDistributions.Row> distributions = WitnessDistributions.compute(rows);
        assertEquals(1, find(distributions, "SAME_ROUND_R1",
                WitnessDistributions.Signal.W1_TARGET_SIMILARITY).frames());
        assertEquals(1, find(distributions, "SAME_ROUND_R2",
                WitnessDistributions.Signal.W1_TARGET_SIMILARITY).frames());
        assertEquals(2, find(distributions, "SAME_ROUND_ALL",
                WitnessDistributions.Signal.W1_TARGET_SIMILARITY).frames());
        assertEquals(1, find(distributions, "ENTRY",
                WitnessDistributions.Signal.W1_TARGET_SIMILARITY).frames());
        assertEquals(0, find(distributions, "EXIT",
                WitnessDistributions.Signal.W1_TARGET_SIMILARITY).frames());
    }

    @Test
    void anEmptyPopulationReportsNoFramesInsteadOfFabricatingStatistics() {
        List<WitnessDistributions.Row> distributions = WitnessDistributions.compute(List.of(
                WitnessTestSupport.row("recording_test", "H1R1", "recording_test-H1R1",
                        WitnessScope.SAME_ROUND, 0, 0, "FP_1[0;1;2;3]",
                        WitnessTestSupport.features(0.95, List.of(0.95, 0.95, 0.95, 0.95, 0.95, 0.95,
                                0.95, 0.95)))));
        WitnessDistributions.Row exit = find(distributions, "EXIT",
                WitnessDistributions.Signal.W3_MIN_REGION_SIMILARITY);
        assertEquals(0, exit.frames());
        assertTrue(Double.isNaN(exit.minimum()));
        assertTrue(exit.notes().contains("no frames"));
    }

    private static WitnessDistributions.Row find(List<WitnessDistributions.Row> rows,
            String population, WitnessDistributions.Signal signal) {
        return rows.stream()
                .filter(row -> row.population().equals(population)
                        && row.signal().equals(signal.name()))
                .findFirst()
                .orElseThrow();
    }
}
