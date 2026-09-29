package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage 8A artifact writer checks: unsafe session names and existing sessions are refused,
 * CSVs stay stable and human-readable, and the report covers the required sections.
 */
class ExternalSessionWriterTest {
    @Test
    void unsafeSessionNamesAreRefused(@TempDir Path temp) {
        assertEquals("youtube-01", ExternalSessionWriter.validateSessionName("youtube-01"));
        assertEquals("A_1-2", ExternalSessionWriter.validateSessionName("A_1-2"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSessionWriter.validateSessionName(""));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSessionWriter.validateSessionName(null));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSessionWriter.validateSessionName("../escape"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSessionWriter.validateSessionName("a/b"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSessionWriter.validateSessionName("a\\b"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSessionWriter.validateSessionName("."));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSessionWriter.validateSessionName("-leading"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSessionWriter.validateSessionName("has space"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSessionWriter.validateSessionName("x".repeat(65)));
    }

    @Test
    void existingSessionDirectoryIsNeverOverwritten(@TempDir Path temp) throws Exception {
        Path first = ExternalSessionWriter.createSessionDirs(temp, "youtube-01");
        assertTrue(Files.isDirectory(first.resolve("screenshots")));
        assertTrue(Files.isDirectory(first.resolve("crops")));
        assertThrows(FileAlreadyExistsException.class,
                () -> ExternalSessionWriter.createSessionDirs(temp, "youtube-01"));
    }

    @Test
    void roundsCsvAndReportCoverRequiredFields(@TempDir Path temp) throws Exception {
        Path dir = ExternalSessionWriter.createSessionDirs(temp, "youtube-01");
        ExternalObservedRound match = new ExternalObservedRound(1, 0L, 100L, 200L, 600L,
                RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 3, 6, 7)),
                List.of(1, 3, 6, 7), 5, List.of(1, 3, 6, 7), 1, List.of(),
                ExternalRoundOutcome.MATCH, ExternalPredictionTiming.ON_TIME, false, "");
        SortedSet<Integer> failed = new TreeSet<>(List.of(0, 1, 2, 3));
        ExternalObservedRound review = new ExternalObservedRound(2, 700L, 750L, 800L, 900L,
                RecognitionIdentity.of(FingerprintId.FP_2, List.of(0, 2, 4, 7)),
                List.of(0, 2, 4, 7), 7, null, 1, List.of(failed),
                ExternalRoundOutcome.NEEDS_REVIEW_FINAL_EXIT, ExternalPredictionTiming.ON_TIME,
                false, "note, with comma");
        List<ExternalObservedRound> rounds = List.of(match, review);
        ExternalSessionWriter.writeRoundsCsv(dir, "youtube-01", rounds);
        String csv = Files.readString(dir.resolve("rounds.csv"), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith(ExternalSessionWriter.ROUNDS_HEADER),
                "rounds header: " + csv);
        assertTrue(csv.contains("MATCH"), "match row: " + csv);
        assertTrue(csv.contains("NEEDS_REVIEW_FINAL_EXIT"), "review row: " + csv);
        assertTrue(csv.contains("\"note, with comma\""), "comma notes are quoted: " + csv);
        assertTrue(csv.contains("FP_3"), "predicted fingerprint: " + csv);

        ExternalSessionEvent event = new ExternalSessionEvent(100L, 1,
                ExternalSessionEvent.EventType.PREDICTION, "C0", "[]", "FP_3[1;3;6;7]", "note");
        ExternalSessionWriter.writeEventsCsv(dir, List.of(event));
        String events = Files.readString(dir.resolve("events.csv"), StandardCharsets.UTF_8);
        assertTrue(events.startsWith(ExternalSessionWriter.EVENTS_HEADER));
        assertTrue(events.contains("PREDICTION"));

        String report = ExternalSessionWriter.writeReportTxt(dir, "youtube-01", "creator video",
                rounds);
        assertTrue(report.contains("EXTERNAL VIDEO VALIDATION"));
        assertTrue(report.contains("session youtube-01"));
        assertTrue(report.contains("observed rounds: 2"));
        assertTrue(report.contains("matches: 1"));
        assertTrue(report.contains("needs review: 1"));
        assertTrue(report.contains("predicted FP distribution:"));
        assertTrue(report.contains("FP2 1"));
        assertTrue(report.contains("FP3 1"));
        assertTrue(report.contains("ROUND 1"));
        assertTrue(report.contains("ROUND 2"));
        assertTrue(report.contains("not independent ground"), "FP limitation note: " + report);

        ExternalSessionWriter.writeSessionJson(dir, "youtube-01", "creator video", "2560x1440",
                1L, 2L, rounds);
        String json = Files.readString(dir.resolve("session.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"matches\": 1"), "json counts: " + json);
    }

    @Test
    void zeroRoundReportGivesSourceGuidance(@TempDir Path temp) throws Exception {
        Path dir = ExternalSessionWriter.createSessionDirs(temp, "empty-01");
        String report =
                ExternalSessionWriter.buildReport("empty-01", "", dir, List.of());
        assertTrue(report.contains("observed rounds = 0"));
        assertTrue(report.contains("NOT a solver failure"));
    }
}
