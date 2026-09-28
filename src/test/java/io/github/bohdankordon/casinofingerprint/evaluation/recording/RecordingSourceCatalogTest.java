package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for the committed Stage 6 recording source metadata. */
class RecordingSourceCatalogTest {

    @Test
    void committedCatalogDescribesBothPrivateRecordings() throws IOException {
        List<RecordingSource> sources = RecordingTestSupport.committedSources();

        assertEquals(2, sources.size());
        RecordingSource hd = RecordingSourceCatalog.require(sources, "recording_1440p");
        assertEquals("2560x1440", hd.resolution());
        assertEquals("casino-heist-1440p.mkv", hd.fileName());
        assertEquals(30.000030, hd.fps(), 1e-9);
        assertEquals(322082164L, hd.sizeBytes());
        RecordingSource fullHd = RecordingSourceCatalog.require(sources, "recording_1080p");
        assertEquals("1920x1080", fullHd.resolution());
        assertEquals("casino-heist-1080p.mp4", fullHd.fileName());
        for (RecordingSource source : sources) {
            assertTrue(source.sha256().matches("[0-9A-F]{64}"), source.sha256());
        }
    }

    @Test
    void localPathIsRelativeAndContainsNoUserDirectory() throws IOException {
        for (RecordingSource source : RecordingTestSupport.committedSources()) {
            assertEquals("local-data/stage6/" + source.fileName(), source.localRelativePath());
            assertTrue(source.localPath(RecordingTestSupport.PROJECT_ROOT).isAbsolute());
        }
    }

    @Test
    void duplicateSourceIdsAreRejected(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("sources.csv");
        String row = "recording_1440p,casino-heist-1440p.mkv,matroska,2560,1440,30.000000,10,0.333,"
                + "100," + "A".repeat(64);
        Files.writeString(csv, RecordingSourceCatalog.HEADER + "\n" + row + "\n" + row + "\n",
                StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RecordingSourceCatalog.read(csv));
        assertTrue(error.getMessage().contains("Duplicate source_id"), error.getMessage());
    }

    @Test
    void malformedHashIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingSource(
                "recording_x", "file.mkv", "matroska", 2560, 1440, 30.0, 10, 0.333, 100, "not-a-hash"));
    }

    @Test
    void fileNameMustBeAPlainName() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingSource(
                "recording_x", "sub/dir/file.mkv", "matroska", 2560, 1440, 30.0, 10, 0.333, 100,
                "A".repeat(64)));
    }
}
