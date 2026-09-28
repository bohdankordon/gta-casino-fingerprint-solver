package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Stage boundary guard for Stage 6C.1C.
 *
 * <p>Static source inspection, so the guard fails the moment production starts reaching for the
 * witness package, or the moment the measurement classes start reading a recognition identity.
 */
class WitnessIsolationTest {
    private static final Path MAIN_SOURCES =
            WitnessTestSupport.PROJECT_ROOT.resolve("src/main/java");
    private static final String PRODUCTION_PACKAGE =
            "io/github/bohdankordon/casinofingerprint/";
    private static final String WITNESS_PACKAGE =
            PRODUCTION_PACKAGE + "evaluation/recording/transition/witness/";

    /**
     * The classes that MEASURE content: they may not mention a recognition identity, the lifecycle
     * tracker or the consensus tracker at all. Scope bookkeeping is a different layer.
     */
    private static final List<String> MEASUREMENT_CLASSES = List.of(
            "PuzzleContentFeatures.java",
            "RegionContentSimilarity.java",
            "PuzzleContentBaseline.java",
            "PuzzleContentWitness.java",
            "WitnessAnalysisThresholds.java",
            "WitnessRule.java");

    @Test
    void measurementClassesNeverReadARecognitionIdentity() throws IOException {
        for (String name : MEASUREMENT_CLASSES) {
            String text = Files.readString(MAIN_SOURCES.resolve(WITNESS_PACKAGE + name),
                    StandardCharsets.UTF_8);
            for (String forbidden : List.of("RecognitionIdentity", "RoundLifecycleTracker",
                    "RecognitionConsensusTracker", "selectedCandidates", "fingerprintId")) {
                assertFalse(text.contains(forbidden),
                        name + " must not reference " + forbidden);
            }
        }
    }

    @Test
    void theWitnessRuleOnlyReadsContentFeatures() throws IOException {
        String source = Files.readString(MAIN_SOURCES.resolve(WITNESS_PACKAGE + "WitnessRule.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("PuzzleContentFeatures"),
                "a rule must be expressed over content features");
        for (String forbidden : List.of("answerIdentity", "consensusState", "roundScope",
                "scope()", "decisionStatus")) {
            assertFalse(source.contains(forbidden),
                    "a rule must not read the reporting columns: " + forbidden);
        }
    }

    @Test
    void productionSourcesNeverReferenceTheWitnessPackage() throws IOException {
        List<Path> sources = javaFiles(MAIN_SOURCES);
        assertFalse(sources.isEmpty());
        for (Path file : sources) {
            String relative = MAIN_SOURCES.relativize(file).toString().replace('\\', '/');
            if (relative.startsWith("io/github/bohdankordon/casinofingerprint/evaluation/")) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String forbidden : List.of("transition.witness", "PuzzleContentWitness",
                    "PuzzleContentBaseline", "WitnessFrameRow", "WitnessAnalyzer")) {
                assertFalse(text.contains(forbidden),
                        relative + " must not depend on " + forbidden);
            }
        }
    }

    @Test
    void theWitnessPackageSendsNoInputAndHasNoTimingConstant() throws IOException {
        for (Path file : javaFiles(MAIN_SOURCES.resolve(WITNESS_PACKAGE))) {
            String relative = MAIN_SOURCES.relativize(file).toString().replace('\\', '/');
            boolean commandLineEntryPoint = relative.endsWith("Main.java");
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String forbidden : List.of("java.awt.Robot", "Thread.sleep", "TimeUnit",
                    "keyPress", "keyRelease", "mousePress", "mouseMove", "KeyEvent", "VK_")) {
                assertFalse(text.contains(forbidden),
                        relative + " must not contain " + forbidden);
            }
            if (!commandLineEntryPoint) {
                assertFalse(text.contains("System.exit"),
                        relative + " must not exit a process outside the command line entry point");
            }
        }
    }

    @Test
    void everyWitnessArtifactStaysBelowTheIgnoredTargetDirectory() {
        for (String artifact : List.of(
                TransitionWitnessAnalysis.FRAMES_CSV_REL,
                TransitionWitnessAnalysis.TRANSITIONS_CSV_REL,
                TransitionWitnessAnalysis.DISTRIBUTIONS_CSV_REL,
                TransitionWitnessAnalysis.SEPARATION_CSV_REL,
                TransitionWitnessAnalysis.RULES_CSV_REL,
                TransitionWitnessAnalysis.COUNTERFACTUAL_CSV_REL,
                TransitionWitnessAnalysis.SAME_TARGET_CSV_REL,
                TransitionWitnessAnalysis.REPORT_REL,
                TransitionWitnessAnalysis.CONTACT_SHEET_DIRECTORY_REL)) {
            assertTrue(artifact.startsWith("target/"),
                    artifact + " must be build output, never a committed artifact");
        }
        Path outputDir = WitnessTestSupport.PROJECT_ROOT.resolve("target");
        assertEquals(outputDir.resolve("stage6c1c-witness-report.txt"),
                TransitionWitnessAnalysis.artifactPath(outputDir,
                        TransitionWitnessAnalysis.REPORT_REL));
        assertThrows(IllegalArgumentException.class, () -> TransitionWitnessAnalysis
                .artifactPath(outputDir, "docs/transition-witness-analysis.md"));
        assertThrows(IllegalArgumentException.class, () -> TransitionWitnessAnalysis
                .artifactPath(outputDir, "target/nested/artifact.csv"));
    }

    @Test
    void theCommittedDocumentationHoldsNoRecordingFrame() throws IOException {
        Path docs = WitnessTestSupport.PROJECT_ROOT.resolve("docs");
        List<String> docFiles;
        try (Stream<Path> files = Files.walk(docs)) {
            docFiles = files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertTrue(docFiles.contains("transition-witness-analysis.md"),
                "the Stage 6C.1C documentation must be committed");
        for (String name : docFiles) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.endsWith(".png") || lower.endsWith(".mp4") || lower.endsWith(".mkv")
                            || lower.endsWith(".jpg"),
                    "docs must hold text only, found " + name);
        }
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")
                            || path.toString().endsWith(".png")
                            || path.toString().endsWith(".mp4"))
                    .sorted()
                    .toList();
        }
    }
}
