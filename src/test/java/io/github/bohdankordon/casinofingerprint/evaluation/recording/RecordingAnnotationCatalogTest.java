package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for the committed Stage 6 annotations: the dataset must hold exactly eight rounds,
 * every round must be internally consistent, and a hand edit that breaks the contract must be
 * rejected instead of silently changing the benchmark.
 */
class RecordingAnnotationCatalogTest {

    @Test
    void committedAnnotationHoldsFourHacksAndEightRounds() throws IOException {
        RecordingAnnotationCatalog catalog = RecordingTestSupport.committedCatalog();

        assertEquals(8, catalog.rounds().size());
        assertEquals(4, catalog.hackWindows().size());
        assertEquals(List.of("recording_1440p", "recording_1080p"), catalog.sourceIds());
        assertEquals(4, catalog.roundsFor("recording_1440p").size());
        assertEquals(4, catalog.roundsFor("recording_1080p").size());
    }

    @Test
    void everyRoundHasFourUniqueCandidatesAgreeingWithTheFragmentMapping() throws IOException {
        for (RecordingRoundAnnotation round : RecordingTestSupport.committedCatalog().rounds()) {
            List<Integer> mapped = List.of(round.candidateForFragment(1), round.candidateForFragment(2),
                    round.candidateForFragment(3), round.candidateForFragment(4));
            assertEquals(4, mapped.stream().distinct().count(),
                    round.describe() + " must map the four fragments to four unique candidates");
            assertEquals(mapped.stream().sorted().toList(), round.correctCandidatesSorted(),
                    round.describe() + " sorted set must agree with the fragment mapping");
            for (int candidate : mapped) {
                assertTrue(candidate >= 0 && candidate <= 7,
                        "candidate indices are 0..7, got " + candidate);
            }
        }
    }

    @Test
    void intervalsAreOrderedAndDoNotOverlapInsideASource() throws IOException {
        for (String sourceId : RecordingTestSupport.committedCatalog().sourceIds()) {
            List<RecordingRoundAnnotation> rounds =
                    RecordingTestSupport.committedCatalog().roundsFor(sourceId);
            for (int index = 1; index < rounds.size(); index++) {
                RecordingRoundAnnotation previous = rounds.get(index - 1);
                RecordingRoundAnnotation current = rounds.get(index);
                assertTrue(current.startSeconds() > previous.startSeconds(),
                        sourceId + " rounds must be ordered by start time");
                assertTrue(current.startSeconds() > previous.endSeconds(),
                        sourceId + " rounds must not overlap: " + previous.describe() + " and "
                                + current.describe());
            }
        }
    }

    @Test
    void resolutionMatchesTheSourceMetadata() throws IOException {
        RecordingAnnotationCatalog catalog = RecordingTestSupport.committedCatalog();
        for (RecordingSource source : RecordingTestSupport.committedSources()) {
            assertEquals(source.resolution(), catalog.resolutionOf(source.sourceId()));
        }
    }

    @Test
    void fingerprintCoverageIsTwoFourTwoZero() throws IOException {
        var coverage = RecordingTestSupport.committedCatalog().coverageByFingerprint();

        assertEquals(2, coverage.get(FingerprintId.FP_1));
        assertEquals(0, coverage.get(FingerprintId.FP_2));
        assertEquals(4, coverage.get(FingerprintId.FP_3));
        assertEquals(2, coverage.get(FingerprintId.FP_4));
    }

    @Test
    void wrongSelectionRoundIsFlaggedAndDocumented() throws IOException {
        List<RecordingRoundAnnotation> flagged = RecordingTestSupport.committedCatalog().rounds()
                .stream().filter(RecordingRoundAnnotation::containsWrongSelection).toList();

        assertEquals(1, flagged.size());
        RecordingRoundAnnotation round = flagged.get(0);
        assertEquals("recording_1080p", round.sourceId());
        assertEquals("H1R2", round.scopeId());
        assertTrue(round.notes().toLowerCase(java.util.Locale.ROOT).contains("error"),
                "the wrong-selection round must document the ERROR episode");
        assertFalse(round.correctCandidatesSorted().contains(5),
                "the incorrectly selected candidate must not be part of the correct set");
    }

    @Test
    void negativeWindowsAreHackWindowsPaddedOnBothSides() throws IOException {
        RecordingAnnotationCatalog catalog = RecordingTestSupport.committedCatalog();

        assertEquals(List.of(new TimeWindow(14.0, 52.0), new TimeWindow(117.0, 142.0)),
                catalog.negativeWindows("recording_1440p", 2.0));
        assertEquals(List.of(new TimeWindow(38.0, 86.0), new TimeWindow(132.0, 172.0)),
                catalog.negativeWindows("recording_1080p", 2.0));
    }

    @Test
    void hackWindowCoverageNotesNameTheRoundThatLeavesItsWindow() throws IOException {
        List<String> notes = RecordingTestSupport.committedCatalog().hackWindowCoverageNotes();

        assertEquals(1, notes.size(), "only one round leaves its approximate hack window");
        assertTrue(notes.get(0).contains("recording_1080p H2R1"), notes.get(0));
    }

    @Test
    void roundWhoseSortedSetDisagreesWithItsMappingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingRoundAnnotation(
                "recording_1440p", "2560x1440", 1, 1, 10.0, 20.0, FingerprintId.FP_4,
                java.util.Map.of(1, 6, 2, 5, 3, 1, 4, 4), List.of(1, 4, 5, 7), false, ""));
    }

    @Test
    void roundWithDuplicateCorrectCandidatesIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingRoundAnnotation(
                "recording_1440p", "2560x1440", 1, 1, 10.0, 20.0, FingerprintId.FP_4,
                java.util.Map.of(1, 6, 2, 6, 3, 1, 4, 4), List.of(1, 4, 6, 6), false, ""));
    }

    @Test
    void catalogWithTheWrongRoundCountIsRejected(@TempDir Path tempDir) throws IOException {
        Path rounds = tempDir.resolve("rounds.csv");
        Path hacks = tempDir.resolve("hacks.csv");
        Files.writeString(rounds, RecordingAnnotationCatalog.ROUNDS_HEADER + "\n"
                + "recording_1440p,2560x1440,1,1,17.500,33.750,FP_4,6,5,1,4,1;4;5;6,false,only one round\n",
                StandardCharsets.UTF_8);
        Files.writeString(hacks, RecordingAnnotationCatalog.HACK_WINDOWS_HEADER + "\n"
                + "recording_1440p,1,16.000,50.000,window\n", StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RecordingAnnotationCatalog.read(rounds, hacks));
        assertTrue(error.getMessage().contains("exactly 8 rounds"), error.getMessage());
    }

    @Test
    void catalogWithAnUnknownFingerprintIsRejected(@TempDir Path tempDir) throws IOException {
        Path rounds = tempDir.resolve("rounds.csv");
        Path hacks = tempDir.resolve("hacks.csv");
        Files.writeString(rounds, RecordingAnnotationCatalog.ROUNDS_HEADER + "\n"
                + "recording_1440p,2560x1440,1,1,17.500,33.750,FP_9,6,5,1,4,1;4;5;6,false,unknown\n",
                StandardCharsets.UTF_8);
        Files.writeString(hacks, RecordingAnnotationCatalog.HACK_WINDOWS_HEADER + "\n"
                + "recording_1440p,1,16.000,50.000,window\n", StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RecordingAnnotationCatalog.read(rounds, hacks));
        assertTrue(error.getMessage().contains("target_fingerprint"), error.getMessage());
    }

    @Test
    void catalogWithOverlappingRoundsIsRejected(@TempDir Path tempDir) throws IOException {
        Path rounds = tempDir.resolve("rounds.csv");
        Path hacks = tempDir.resolve("hacks.csv");
        StringBuilder csv = new StringBuilder(RecordingAnnotationCatalog.ROUNDS_HEADER).append('\n');
        for (int index = 1; index <= 8; index++) {
            csv.append("recording_1440p,2560x1440,1,").append(index).append(',')
                    .append(10 + index).append(".000,").append(30 + index).append(".000,")
                    .append("FP_4,6,5,1,4,1;4;5;6,false,overlapping\n");
        }
        Files.writeString(rounds, csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(hacks, RecordingAnnotationCatalog.HACK_WINDOWS_HEADER + "\n"
                + "recording_1440p,1,10.000,40.000,window\n", StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RecordingAnnotationCatalog.read(rounds, hacks));
        assertTrue(error.getMessage().contains("overlap"), error.getMessage());
    }

    @Test
    void catalogWithACandidateOutsideZeroToSevenIsRejected(@TempDir Path tempDir) throws IOException {
        Path rounds = tempDir.resolve("rounds.csv");
        Path hacks = tempDir.resolve("hacks.csv");
        Files.writeString(rounds, RecordingAnnotationCatalog.ROUNDS_HEADER + "\n"
                + "recording_1440p,2560x1440,1,1,17.500,33.750,FP_4,6,5,1,8,1;4;5;6,false,bad index\n",
                StandardCharsets.UTF_8);
        Files.writeString(hacks, RecordingAnnotationCatalog.HACK_WINDOWS_HEADER + "\n"
                + "recording_1440p,1,16.000,50.000,window\n", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> RecordingAnnotationCatalog.read(rounds, hacks));
    }

    @Test
    void quotedNotesWithCommasSurviveParsing(@TempDir Path tempDir) throws IOException {
        Path rounds = tempDir.resolve("rounds.csv");
        Files.writeString(rounds, RecordingAnnotationCatalog.ROUNDS_HEADER + "\n"
                + "recording_1440p,2560x1440,1,1,17.500,33.750,FP_4,6,5,1,4,1;4;5;6,false,"
                + "\"quoted, with a comma\"\n", StandardCharsets.UTF_8);

        CsvTable table = CsvTable.read(rounds, RecordingAnnotationCatalog.ROUNDS_HEADER);

        assertEquals(1, table.rows().size());
        assertEquals("quoted, with a comma", table.rows().get(0).optional(13));
        assertEquals(17.5, table.rows().get(0).decimal(4, "start_seconds"));
    }
}
