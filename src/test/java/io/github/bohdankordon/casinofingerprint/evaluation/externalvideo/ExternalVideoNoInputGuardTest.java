package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

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
 * Stage 8A no-input source guard: the passive evaluation package plus its CLI can never
 * reach gameplay-input classes. AWT Robot SCREEN CAPTURE through the existing capture
 * backend stays allowed; every keyboard, mouse, SendInput or automation path is refused.
 */
class ExternalVideoNoInputGuardTest {
    private static final Path MAIN_ROOT = Path.of(System.getProperty("user.dir"))
            .resolve("src/main/java/io/github/bohdankordon/casinofingerprint");
    private static final List<String> FORBIDDEN = List.of(
            "WindowsSendInputSink",
            "GameInputSink",
            "GuardedPlanExecutor",
            "LiveSolveOrchestrator",
            "WindowsForegroundTargetGuard",
            "WindowsEmergencyAbort",
            "Win32Support",
            "GameControl",
            "Robot",
            "keyPress",
            "mousePress",
            "mouseMove",
            "VerificationPolicy");

    @Test
    void stage8aSourcesNeverTouchGameplayInput() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_ROOT.resolve("evaluation/externalvideo"))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }
        sources.add(MAIN_ROOT.resolve("app/ExternalVideoValidationMain.java"));
        sources.add(MAIN_ROOT.resolve("app/ExternalVideoValidationOptions.java"));
        assertFalse(sources.isEmpty(), "Stage 8A sources must exist");
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
                "Stage 8A must never access gameplay input: " + violations);
    }

    @Test
    void stage8aReusesOnlyTheInputFreeProductionPath() throws IOException {
        Path runner =
                MAIN_ROOT.resolve("evaluation/externalvideo/ExternalVideoSessionRunner.java");
        String text = Files.readString(runner, StandardCharsets.UTF_8);
        assertTrue(text.contains("DryRunSolveOrchestrator"),
                "the passive loop reuses the input-free dry-run orchestrator");
        assertTrue(text.contains("FrameControlReader"),
                "the passive loop observes human control input-free");
        assertTrue(text.contains("ScreenCapture"), "the passive loop captures the monitor");
    }
}
