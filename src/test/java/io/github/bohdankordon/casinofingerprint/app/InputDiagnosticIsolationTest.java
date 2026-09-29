package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.1 isolation guard: the diagnostic is deliberately tiny and input-only. It must never
 * reach the recognition, capture, lifecycle, planning or execution stack, it never carries an
 * alternative OS input path, and the Windows backends are constructed in exactly one place,
 * behind the OS gate.
 */
class InputDiagnosticIsolationTest {
    private static final Path MAIN_ROOT = Path.of(System.getProperty("user.dir"))
            .resolve("src/main/java/io/github/bohdankordon/casinofingerprint");
    private static final List<String> FORBIDDEN = List.of(
            "FrameRecognitionPipeline",
            "DryRunSolveOrchestrator",
            "LiveSolveOrchestrator",
            "DryRunPlanner",
            "GuardedPlanExecutor",
            "GameplayLayout",
            "ScreenCapture",
            "RoundLifecycleTracker",
            "RecognitionPolicy",
           "VerificationPolicy",
           "Robot",
            "KeyEvent",
            "MouseEvent",
           "keyPress",
            "keyRelease",
            "mousePress",
            "mouseRelease",
            "com.sun.jna",
            "GetAsyncKeyState",
            "GetForegroundWindow",
            "java.awt.event");
    private static final List<String> NATIVE_BACKENDS = List.of(
            "WindowsSendInputSink",
            "WindowsForegroundTargetGuard",
            "WindowsEmergencyAbort");

    @Test
    void diagnosticSourcesStayDisconnectedFromTheSolverStack() throws IOException {
        List<Path> sources = diagnosticSources();
        assertFalse(sources.isEmpty(), "Stage 8C.1 sources must exist");
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN) {
                if (text.contains(forbidden)) {
                    violations.add(source.getFileName() + " mentions " + forbidden);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "The diagnostic must never touch recognition, capture or execution: " + violations);
    }

    @Test
    void nativeInputIsConstructedInExactlyOnePlace() throws IOException {
        List<String> mentions = new ArrayList<>();
        for (Path source : diagnosticSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String backend : NATIVE_BACKENDS) {
                if (text.contains(backend)) {
                    mentions.add(source.getFileName() + " mentions " + backend);
                }
            }
        }
        assertFalse(mentions.isEmpty(), "The production factory must construct the Windows backends");
        for (String mention : mentions) {
            assertTrue(mention.startsWith("InputDiagnosticMain.java"),
                    "Native input belongs to InputDiagnosticMain only: " + mention);
        }
    }

    @Test
    void reusesTheStage7bInputContractsInsteadOfNewOnes() throws IOException {
        String core =
                Files.readString(MAIN_ROOT.resolve("app/SingleTapInputDiagnostic.java"),
                        StandardCharsets.UTF_8);
        assertTrue(core.contains("GameInputSink"), "Reuses the Stage 7B tap contract");
        assertTrue(core.contains("ForegroundTargetGuard"), "Reuses the Stage 7B foreground guard");
        assertTrue(core.contains("AbortSignal"), "Reuses the Stage 7B abort signal");
        assertTrue(core.contains("GameControl"), "Reuses the Stage 7B control enum");
    }

    /** Every production source of the Stage 8C.1 diagnostic. */
    private static List<Path> diagnosticSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_ROOT.resolve("app"))) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("InputDiagnostic")
                                || name.equals("SingleTapInputDiagnostic.java");
                    })
                    .forEach(sources::add);
        }
        return sources;
    }
}
