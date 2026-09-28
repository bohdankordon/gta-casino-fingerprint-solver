package io.github.bohdankordon.casinofingerprint.orchestration;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 7A input-freedom guard: the dry-run orchestration, navigation and dry-run CLI sources
 * contain no input APIs, no key or mouse handling and no native calls. Neutral domain words
 * (UP, DOWN, SELECT, PROCEED, Navigate) stay allowed: only precise input-implementation tokens
 * are forbidden, so the abstract movement model is never mistaken for an implementation.
 */
class DryRunSurfaceTest {
    private static final List<String> PURE_SOURCES = List.of(
            "navigation/Move.java",
            "navigation/GridPosition.java",
            "navigation/GridNavigationPolicy.java",
            "navigation/ProvenGridNavigationPolicy.java",
            "navigation/DryRunAction.java",
            "navigation/DryRunPlan.java",
            "navigation/DryRunPlanner.java",
            "navigation/PlanValidator.java",
            "orchestration/NavigationContext.java",
            "orchestration/DryRunFrameResult.java",
            "orchestration/DryRunSolveOrchestrator.java");

    private static final List<String> CLI_SOURCES = List.of(
            "app/DryRunSolverOptions.java",
            "app/DryRunSolverMain.java");

    private static final List<String> FORBIDDEN_INPUT_TOKENS = List.of(
            "java.awt.Robot",
            "KeyEvent",
            "MouseEvent",
            "keyPress",
            "keyRelease",
            "mousePress",
            "mouseRelease",
            "SendInput",
            "java.awt.event");

    private static final List<String> FORBIDDEN_CORRECTNESS_TOKENS = List.of(
            "Thread.sleep",
            "System.nanoTime",
            "System.currentTimeMillis",
            "java.time.Duration");

    @Test
    void orchestrationAndNavigationStayInputFree() throws IOException {
        for (String source : PURE_SOURCES) {
            String text = read(source);
            for (String token : FORBIDDEN_INPUT_TOKENS) {
                assertFalse(text.contains(token), source + " must not use " + token);
            }
        }
    }

    @Test
    void orchestrationCorrectnessNeverDependsOnTimers() throws IOException {
        for (String source : PURE_SOURCES) {
            String text = read(source);
            for (String token : FORBIDDEN_CORRECTNESS_TOKENS) {
                assertFalse(text.contains(token), source + " must not use " + token);
            }
        }
    }

    @Test
    void dryRunCliStaysInputFree() throws IOException {
        for (String source : CLI_SOURCES) {
            String text = read(source);
            for (String token : FORBIDDEN_INPUT_TOKENS) {
                assertFalse(text.contains(token), source + " must not use " + token);
            }
        }
    }

    private static String read(String relative) throws IOException {
        Path root = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/io/github/bohdankordon/casinofingerprint");
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }
}
