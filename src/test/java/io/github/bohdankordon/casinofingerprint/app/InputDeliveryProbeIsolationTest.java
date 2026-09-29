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
 * Stage 8C.2 isolation guard: the characterization probe and its pure primitives stay
 * disconnected from the solver stack, the pure package never reaches a native Windows API, the
 * native diagnostic backend is constructed in exactly one place, and no test constructs or
 * calls a native input backend.
 */
class InputDeliveryProbeIsolationTest {
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
            "LiveSolverMain",
            "Robot",
            "KeyEvent",
            "MouseEvent",
            "keyPress",
            "keyRelease",
            "mousePress",
            "mouseRelease",
            "java.awt",
            "PostMessage",
            "SendMessage",
            "DirectInput",
            "SetWindowsHookEx",
            "WriteProcessMemory",
            "CreateRemoteThread");
    private static final List<String> NATIVE_MARKERS = List.of(
            "com.sun.jna",
            "User32",
            ".INSTANCE",
            "GetAsyncKeyState",
            "GetForegroundWindow",
            "SendInput(",
            "SetWindowsHookEx",
            "WriteProcessMemory",
            "CreateRemoteThread",
            "PostMessage",
            "SendMessage");
    private static final List<String> NATIVE_BACKENDS = List.of(
            "WindowsSendInputSink",
            "WindowsDiagnosticInputSink",
            "WindowsForegroundTargetGuard",
            "WindowsEmergencyAbort");

    @Test
    void probeSourcesStayDisconnectedFromTheSolverStack() throws IOException {
        List<Path> sources = probeSources();
        assertFalse(sources.isEmpty(), "Stage 8C.2 sources must exist");
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
                "The characterization probe must never touch recognition, capture, navigation or"
                        + " execution: " + violations);
    }

    @Test
    void noProbeSourceReachesANativeWindowsApi() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : probeSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String marker : NATIVE_MARKERS) {
                if (text.contains(marker)) {
                    violations.add(source.getFileName() + " mentions " + marker);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "All native input lives behind the WindowsDiagnosticInputSink boundary: "
                        + violations);
    }

    @Test
    void thePureCharacterizationPrimitivesNeverMentionANativeBackend() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : purePrimitiveSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String backend : NATIVE_BACKENDS) {
                if (text.contains(backend)) {
                    violations.add(source.getFileName() + " mentions " + backend);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "The input.diagnostic package must stay free of native Windows types: "
                        + violations);
    }

    @Test
    void nativeInputIsConstructedInExactlyOnePlace() throws IOException {
        List<String> mentions = new ArrayList<>();
        for (Path source : probeAppSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String backend : NATIVE_BACKENDS) {
                if (text.contains(backend)) {
                    mentions.add(source.getFileName() + " mentions " + backend);
                }
            }
        }
        assertFalse(mentions.isEmpty(), "The production factory must construct the backends");
        for (String mention : mentions) {
            assertTrue(mention.startsWith("InputDeliveryProbeMain.java"),
                    "Native input belongs to InputDeliveryProbeMain only: " + mention);
        }
    }

    @Test
    void noTestConstructsANativeInputBackend() throws IOException {
        Path testRoot = Path.of(System.getProperty("user.dir")).resolve("src/test/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(testRoot)) {
            for (Path source : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                for (String backend : NATIVE_BACKENDS) {
                    if (text.contains("new " + backend)) {
                        offenders.add(source.getFileName() + " constructs " + backend);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "No test may construct a native input backend: " + offenders);
    }

    /** The Stage 8C.2 probe's production sources in the app package. */
    private static List<Path> probeAppSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_ROOT.resolve("app"))) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getFileName().toString()
                            .startsWith("InputDeliveryProbe"))
                    .forEach(sources::add);
        }
        return sources;
    }

    /** Every Stage 8C.2 source: the probe CLI plus the pure characterization primitives. */
    private static List<Path> probeSources() throws IOException {
        List<Path> sources = new ArrayList<>(probeAppSources());
        sources.addAll(purePrimitiveSources());
        return sources;
    }

    /** The pure, native-free characterization primitives. */
    private static List<Path> purePrimitiveSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_ROOT.resolve("input/diagnostic"))) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
        }
        return sources;
    }
}
