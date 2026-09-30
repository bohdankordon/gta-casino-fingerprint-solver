package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryPlan;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticInputDeliveryMode;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.ScanCodeSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.3 isolation guard: the dedicated proceed probe stays disconnected from the
 * solver stack, the pure Tab primitives never reach a native Windows API, the native
 * proceed backend is constructed in exactly one place, no test constructs a native input
 * backend, and the general PROCEED ban is unchanged: the Stage 8C.1 diagnostic, the Stage
 * 8C.2 probe, the general plan factory and the general scan-code mapping still refuse Tab.
 * The dedicated proceed entry point is the only Stage 8C diagnostic allowed to emit Tab.
 */
class ProceedInputProbeIsolationTest {
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
            "WindowsProceedInputSink",
            "WindowsForegroundTargetGuard",
            "WindowsEmergencyAbort");

    @Test
    void proceedSourcesStayDisconnectedFromTheSolverStack() throws IOException {
        List<Path> sources = proceedSources();
        assertFalse(sources.isEmpty(), "Stage 8C.3 sources must exist");
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
                "The dedicated proceed probe must never touch recognition, capture,"
                        + " navigation or execution: " + violations);
    }

    @Test
    void noProceedAppSourceReachesANativeWindowsApi() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : proceedAppSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String marker : NATIVE_MARKERS) {
                if (text.contains(marker)) {
                    violations.add(source.getFileName() + " mentions " + marker);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "All native Tab input lives behind the WindowsProceedInputSink boundary: "
                        + violations);
    }

    @Test
    void pureProceedPrimitivesNeverMentionANativeBackend() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : pureProceedSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String backend : NATIVE_BACKENDS) {
                if (text.contains(backend)) {
                    violations.add(source.getFileName() + " mentions " + backend);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "The dedicated Tab plan and delivery must stay free of native Windows types: "
                        + violations);
    }

    @Test
    void nativeProceedInputIsConstructedInExactlyOnePlace() throws IOException {
        List<String> mentions = new ArrayList<>();
        for (Path source : proceedAppSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String backend : NATIVE_BACKENDS) {
                if (text.contains(backend)) {
                    mentions.add(source.getFileName() + " mentions " + backend);
                }
            }
        }
        assertFalse(mentions.isEmpty(), "The production factory must construct the backends");
        for (String mention : mentions) {
            assertTrue(mention.startsWith("ProceedInputProbeMain.java"),
                    "Native Tab input belongs to ProceedInputProbeMain only: " + mention);
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

    @Test
    void generalDiagnosticStillRefusesProceed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> InputDiagnosticOptions.parse(new String[] {"--enable-input",
                        "--target-exe", "GTA5_Enhanced.exe", "--control", "PROCEED",
                        "--abort-key", "SCROLL_LOCK"}));
        assertTrue(error.getMessage().contains("PROCEED"), error.getMessage());
        assertTrue(error.getMessage().contains("Tab"), error.getMessage());
    }

    @Test
    void generalProbeStillRefusesProceedInEveryMode() {
        for (String mode : new String[] {"VK_BATCH", "VK_HOLD", "SCANCODE_BATCH",
                "SCANCODE_HOLD"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> InputDeliveryProbeOptions.parse(new String[] {"--enable-input",
                            "--target-exe", "GTA5_Enhanced.exe", "--control", "PROCEED",
                            "--delivery-mode", mode, "--abort-key", "F8"}),
                    mode + " must refuse PROCEED");
            assertTrue(error.getMessage().contains("PROCEED"), mode + ": " + error.getMessage());
        }
    }

    @Test
    void generalPlanFactoryStillRefusesProceed() {
        for (DiagnosticInputDeliveryMode mode : DiagnosticInputDeliveryMode.values()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DiagnosticDeliveryPlan.forTap(GameControl.PROCEED, mode,
                            mode.holds() ? 50 : 0),
                    mode + " must refuse PROCEED");
            assertTrue(error.getMessage().contains("Tab"), mode + ": " + error.getMessage());
        }
    }

    @Test
    void generalScanCodeMappingStillRefusesProceed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScanCodeSpec.forControl(GameControl.PROCEED));
        assertTrue(error.getMessage().contains("PROCEED"), error.getMessage());
        assertTrue(error.getMessage().contains("Tab"), error.getMessage());
    }

    @Test
    void dedicatedTabMappingIsSeparateFromTheGeneralBan() {
        ScanCodeSpec tab = ScanCodeSpec.tabForProceedProbe();
        assertTrue(tab.scanCode() == 0x0F, "Tab make code");
        assertFalse(tab.extended(), "Tab is non-extended");
        // The ban above still throws, so the dedicated mapping did not weaken it.
        assertThrows(IllegalArgumentException.class,
                () -> ScanCodeSpec.forControl(GameControl.PROCEED));
    }

    /** The Stage 8C.3 probe production sources in the app package. */
    private static List<Path> proceedAppSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_ROOT.resolve("app"))) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getFileName().toString().startsWith("Proceed"))
                    .forEach(sources::add);
        }
        return sources;
    }

    /** The pure dedicated Tab primitives. */
    private static List<Path> pureProceedSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_ROOT.resolve("input/diagnostic"))) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getFileName().toString().startsWith("Proceed"))
                    .forEach(sources::add);
        }
        return sources;
    }

    /** Every Stage 8C.3 source: the dedicated CLI plus the pure Tab primitives. */
    private static List<Path> proceedSources() throws IOException {
        List<Path> sources = new ArrayList<>(proceedAppSources());
        sources.addAll(pureProceedSources());
        return sources;
    }
}
