package io.github.bohdankordon.casinofingerprint.orchestration;

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
 * Stage 7B surface guards: native input stays inside {@code input.win32}, the guarded
 * executor and the live orchestration never sleep on the JDK clock, and the live CLI keeps
 * its platform refusal visible. Neutral domain words (UP, DOWN, SELECT, PROCEED, Navigate,
 * tap as a verb) stay allowed: only precise input-implementation tokens are forbidden.
 */
class LiveSurfaceTest {
    private static final List<String> PURE_SOURCES = List.of(
            "control/PuzzleControlState.java",
            "control/ControlThresholds.java",
            "control/PuzzleControlStateDetector.java",
            "control/LayoutControlReader.java",
            "execution/ExecutionState.java",
            "execution/VerificationPolicy.java",
            "execution/ExecutionClock.java",
            "execution/ControlStateSource.java",
            "execution/RoundClaim.java",
            "execution/ExecutionReport.java",
            "execution/GuardedPlanExecutor.java",
            "input/GameControl.java",
            "input/GameInputException.java",
            "input/GameInputSink.java",
            "input/ForegroundTarget.java",
            "input/ForegroundTargetGuard.java",
            "input/AbortSignal.java",
            "orchestration/LiveFrameResult.java",
            "orchestration/FrameControlReader.java",
            "orchestration/FrameCapture.java",
            "orchestration/LiveSolveOrchestrator.java",
            "app/LiveSolverOptions.java");

    private static final List<String> FORBIDDEN_INPUT_TOKENS = List.of(
            "java.awt.Robot",
            "KeyEvent",
            "MouseEvent",
            "keyPress",
            "keyRelease",
            "mousePress",
            "mouseRelease",
            "SendInput",
            "GetAsyncKeyState",
            "GetForegroundWindow",
            "com.sun.jna",
            "java.awt.event");

    private static final List<String> FORBIDDEN_TIMER_TOKENS = List.of(
            "Thread.sleep",
            "System.nanoTime",
            "System.currentTimeMillis",
            "java.time.Duration");

    private static final List<String> TIMER_FREE_SOURCES = List.of(
            "control/PuzzleControlState.java",
            "control/ControlThresholds.java",
            "control/PuzzleControlStateDetector.java",
            "control/LayoutControlReader.java",
            "execution/ExecutionState.java",
            "execution/VerificationPolicy.java",
            "execution/ExecutionClock.java",
            "execution/ControlStateSource.java",
            "execution/RoundClaim.java",
            "execution/ExecutionReport.java",
            "execution/GuardedPlanExecutor.java",
            "input/GameControl.java",
            "input/GameInputException.java",
            "input/GameInputSink.java",
            "input/ForegroundTarget.java",
            "input/ForegroundTargetGuard.java",
            "input/AbortSignal.java",
            "orchestration/LiveFrameResult.java",
            "orchestration/FrameControlReader.java",
            "orchestration/FrameCapture.java",
            "orchestration/LiveSolveOrchestrator.java",
            "app/LiveSolverOptions.java");

    @Test
    void liveSourcesStayInputFreeOutsideWin32() throws IOException {
        for (String source : PURE_SOURCES) {
            String text = read(source);
            for (String token : FORBIDDEN_INPUT_TOKENS) {
                assertFalse(text.contains(token), source + " must not use " + token);
            }
        }
    }

    @Test
    void liveCliReachesInputOnlyThroughWin32Backends() throws IOException {
        String main = read("app/LiveSolverMain.java");
        for (String token : List.of("java.awt.Robot", "KeyEvent", "MouseEvent", "keyPress",
                "keyRelease", "mousePress", "mouseRelease", "GetAsyncKeyState",
                "GetForegroundWindow", "com.sun.jna", "java.awt.event")) {
            assertFalse(main.contains(token), "LiveSolverMain must not use " + token);
        }
        assertTrue(main.contains("WindowsSendInputSink"), "input goes through SendInput");
        assertTrue(main.contains("WindowsForegroundTargetGuard"), "foreground is guarded");
        assertTrue(main.contains("WindowsEmergencyAbort"), "abort is wired");
    }

    @Test
    void executionCorrectnessNeverDependsOnJdkTimers() throws IOException {
        for (String source : TIMER_FREE_SOURCES) {
            String text = read(source);
            for (String token : FORBIDDEN_TIMER_TOKENS) {
                assertFalse(text.contains(token), source + " must not use " + token);
            }
        }
    }

    @Test
    void nativeInputTokensLiveOnlyInWin32() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/io/github/bohdankordon/casinofingerprint");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative =
                        root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                if (relative.startsWith("input/win32/")
                        || relative.equals("app/LiveSolverMain.java")
                        || relative.equals("app/InputDiagnosticMain.java")) {
                    // win32 holds the native calls; the CLIs that wire the guarded live
                    // path and the Stage 8C.1 single-tap diagnostic only name those
                    // backend classes, pinned by liveCliReachesInputOnlyThroughWin32Backends
                    // and by the Stage 8C.1 input-diagnostic isolation test.
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String token : List.of("SendInput", "GetAsyncKeyState",
                        "GetForegroundWindow", "com.sun.jna", "QueryFullProcessImageName")) {
                    if (text.contains(token)) {
                        offenders.add(relative + " uses " + token);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "native input escaped win32: " + offenders);
    }

    @Test
    void liveCliKeepsItsPlatformRefusalVisible() throws IOException {
        String main = read("app/LiveSolverMain.java");
        assertTrue(main.contains("requires Windows"), "Windows refusal stays visible");
        assertTrue(main.contains("LIVE INPUT ENABLED"), "safety banner stays visible");
        assertTrue(main.contains("emergency abort"), "abort notice stays visible");
    }

    private static String read(String relative) throws IOException {
        Path root = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/io/github/bohdankordon/casinofingerprint");
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }
}
