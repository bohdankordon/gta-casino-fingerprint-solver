package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Stage boundary guard for Stage 6.
 *
 * <p>The recording annotations, the private recordings and the whole benchmark are evaluation-only:
 * no production package may read them, no production class may depend on the evaluation package, and
 * no 1920x1080 layout may become a bundled production manifest. The guard is static source
 * inspection, so it fails the moment a production class starts reaching for benchmark material.
 */
class EvaluationIsolationTest {
    private static final String EVALUATION_DIRECTORY =
            "io/github/bohdankordon/casinofingerprint/evaluation/recording/";
    private static final Path MAIN_SOURCES =
            RecordingTestSupport.PROJECT_ROOT.resolve("src/main/java");
    private static final Path EVALUATION_SOURCES = MAIN_SOURCES.resolve(EVALUATION_DIRECTORY);

    @Test
    void productionSourcesNeverReferenceBenchmarkMaterial() throws IOException {
        List<Path> sources = javaFiles(MAIN_SOURCES);
        assertFalse(sources.isEmpty(), "main sources must be present");
        for (Path file : sources) {
            String relative = MAIN_SOURCES.relativize(file).toString().replace('\\', '/');
            if (relative.startsWith(EVALUATION_DIRECTORY)) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(text.contains("evaluation.recording"),
                    relative + " must not depend on the Stage 6 evaluation tooling");
            assertFalse(text.contains("fixtures/gameplay/recordings"),
                    relative + " must not read the recording annotations");
            assertFalse(text.contains("local-data"),
                    relative + " must not reference the private local recordings");
        }
    }

    @Test
    void evaluationSourcesOwnTheAnnotationAndRecordingPaths() throws IOException {
        String catalog = Files.readString(
                EVALUATION_SOURCES.resolve("RecordingAnnotationCatalog.java"), StandardCharsets.UTF_8);
        String sources = Files.readString(
                EVALUATION_SOURCES.resolve("RecordingSource.java"), StandardCharsets.UTF_8);
        String decoder = Files.readString(
                EVALUATION_SOURCES.resolve("RecordingFrameDecoder.java"), StandardCharsets.UTF_8);

        assertTrue(catalog.contains("fixtures/gameplay/recordings/stage6-rounds.csv"));
        assertTrue(catalog.contains("fixtures/gameplay/recordings/stage6-hack-windows.csv"));
        assertTrue(sources.contains("local-data/stage6"));
        assertTrue(decoder.contains("opencv_videoio"),
                "the decoder must use the OpenCV videoio support that already ships with the project");
    }

    @Test
    void everyBenchmarkArtifactStaysBelowTheIgnoredTargetDirectory() {
        for (String artifact : List.of(
                RecordingBenchmark.POSITIVE_CSV_REL,
                RecordingBenchmark.NEGATIVE_CSV_REL,
                RecordingBenchmark.NEGATIVE_EXHAUSTIVE_CSV_REL,
                RecordingBenchmark.ROUND_SUMMARY_REL,
                RecordingBenchmark.RESOLUTION_SUMMARY_REL,
                RecordingBenchmark.CONSENSUS_SUMMARY_REL,
                RecordingBenchmark.FULL_REPLAY_CSV_REL,
                RecordingBenchmark.REPORT_REL,
                RecordingBenchmark.LAYOUT_OVERLAY_REL,
                RecordingBenchmark.ROUND_KEYFRAMES_REL,
                RecordingBenchmark.ERROR_CASE_REL,
                EvaluationLayoutScaler.DERIVED_LAYOUT_REL)) {
            assertTrue(artifact.startsWith("target/"),
                    artifact + " must be build output, never a committed artifact");
        }
    }

    @Test
    void onlyTheProduction2560x1440LayoutIsBundled() throws IOException {
        assertEquals(2560, GameplayLayout.REPRESENTATIVE_WIDTH);
        assertEquals(1440, GameplayLayout.REPRESENTATIVE_HEIGHT);
        Path layoutDirectory = RecordingTestSupport.PROJECT_ROOT.resolve("fixtures/gameplay/layout");
        List<String> manifests;
        try (Stream<Path> files = Files.list(layoutDirectory)) {
            manifests = files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("representative-2560x1440.csv"), manifests,
                "the derived 1920x1080 geometry must stay evaluation-only");
    }

    @Test
    void artifactPathsArePlainFilesDirectlyBelowTheOutputDirectory() {
        Path outputDir = RecordingTestSupport.PROJECT_ROOT.resolve("target");

        assertEquals(outputDir.resolve("stage6-positive-frame-results.csv"),
                RecordingBenchmark.artifactPath(outputDir, RecordingBenchmark.POSITIVE_CSV_REL));
        assertEquals(outputDir.resolve("stage6-benchmark-report.txt"),
                RecordingBenchmark.artifactPath(outputDir, RecordingBenchmark.REPORT_REL));
        assertThrows(IllegalArgumentException.class,
                () -> RecordingBenchmark.artifactPath(outputDir, "docs/not-build-output.md"));
        assertThrows(IllegalArgumentException.class,
                () -> RecordingBenchmark.artifactPath(outputDir, "target/nested/path.csv"));
    }

    @Test
    void recordingAnnotationDirectoryHoldsNoImageOrVideo() throws IOException {
        Path directory = RecordingTestSupport.PROJECT_ROOT.resolve("fixtures/gameplay/recordings");
        List<String> files;
        try (Stream<Path> listed = Files.list(directory)) {
            files = listed.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("README.md", "stage6-hack-windows.csv", "stage6-rounds.csv",
                "stage6-sources.csv"), files,
                "the committed recording fixture holds annotations only: no frames, no contact "
                        + "sheets, no videos");
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }
}
