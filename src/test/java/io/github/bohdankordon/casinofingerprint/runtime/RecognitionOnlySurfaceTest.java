package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.bohdankordon.casinofingerprint.app.LiveRecognitionMain;
import io.github.bohdankordon.casinofingerprint.app.LiveRecognitionOptions;
import io.github.bohdankordon.casinofingerprint.capture.AwtCaptureResult;
import io.github.bohdankordon.casinofingerprint.capture.AwtMonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.AwtScreenCapture;
import io.github.bohdankordon.casinofingerprint.capture.BufferedImageMatConverter;
import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import io.github.bohdankordon.casinofingerprint.capture.CaptureFactory;
import io.github.bohdankordon.casinofingerprint.capture.CaptureProbe;
import io.github.bohdankordon.casinofingerprint.capture.MonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.MonitorSelector;
import io.github.bohdankordon.casinofingerprint.capture.PhysicalDisplayMode;
import io.github.bohdankordon.casinofingerprint.capture.Resolution;
import io.github.bohdankordon.casinofingerprint.capture.ResolutionVariant;
import io.github.bohdankordon.casinofingerprint.capture.ResolutionVariantSelector;
import io.github.bohdankordon.casinofingerprint.capture.ScreenBounds;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.capture.UnsupportedResolutionException;
import java.awt.Robot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Static guard for the Stage 5 stage boundary: the whole public surface of the capture backend,
 * the runtime and the command-line entry point is recognition-only.
 *
 * <p>The stage contract is that no input automation exists. These tests fail the moment a member
 * takes or returns an input type, describes a key or mouse action, or a runtime state stops being
 * a recognition outcome, so the boundary cannot erode silently in a later change.
 */
class RecognitionOnlySurfaceTest {
    private static final List<Class<?>> STAGE_5_SURFACE = List.of(
            ScreenCapture.class,
            CaptureFactory.class,
            MonitorEnumerator.class,
            MonitorInfo.class,
            ScreenBounds.class,
            PhysicalDisplayMode.class,
            Resolution.class,
            ResolutionVariant.class,
            ResolutionVariantSelector.class,
            MonitorSelector.class,
            AwtMonitorEnumerator.class,
            AwtScreenCapture.class,
            AwtCaptureResult.class,
            BufferedImageMatConverter.class,
            CaptureProbe.class,
            CaptureException.class,
            UnsupportedResolutionException.class,
            FrameRecognitionPipeline.class,
            FrameRecognitionResult.class,
            UnsupportedFrameSizeException.class,
            RecognitionConsensusTracker.class,
            LiveRecognitionRuntime.class,
            LiveRecognitionStatus.class,
            LiveRecognitionState.class,
            LiveFrameOutcome.class,
            LiveRecognitionMain.class,
            LiveRecognitionOptions.class,
            RecognitionIdentity.class,
            RoundLifecycleTracker.class,
            RoundLifecycleState.class,
            RoundLifecycleStatus.class,
            FrameRecognitionObservation.class,
            PuzzleContentTransitionEvidence.class,
            PuzzleContentTransitionWitness.class,
            RoundLifecycleWitnessCoordinator.class);

    private static final List<String> FORBIDDEN_TYPE_TOKENS = List.of(
            "input", "keyboard", "keyevent", "keycode", "hotkey", "keystroke", "mouse");

    private static final List<String> FORBIDDEN_MEMBER_TOKENS = List.of(
            "press", "click", "keydown", "keyup", "keypress", "sendinput", "movemouse",
            "keystroke", "hotkey", "navigate", "automate");

    @Test
    void publicSurfaceNeverMentionsAnInputOrAutomationType() {
        for (Class<?> type : STAGE_5_SURFACE) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                assertAllowed(type, method.getReturnType(), method.toString());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertAllowed(type, parameter, method.toString());
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (!Modifier.isPublic(constructor.getModifiers())) {
                    continue;
                }
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    assertAllowed(type, parameter, constructor.toString());
                }
            }
        }
    }

    @Test
    void publicMemberNamesNeverDescribeAnInputAction() {
        for (Class<?> type : STAGE_5_SURFACE) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                for (String token : FORBIDDEN_MEMBER_TOKENS) {
                    assertFalse(name.contains(token), type.getSimpleName() + "." + method.getName()
                            + " must not describe an input action");
                }
            }
        }
    }

    @Test
    void runtimeStatesAreRecognitionOutcomesOnly() {
        assertEquals(
                List.of("WAITING", "UNSUPPORTED_FRAME", "CAPTURE_ERROR", "UNCERTAIN",
                        "RECOGNIZED", "CANDIDATE_RECOGNITION", "STABLE_RECOGNIZED"),
                Arrays.stream(LiveRecognitionState.values()).map(Enum::name).toList(),
                "Runtime states describe what was recognized; an input state would break this "
                        + "stage contract");
    }

    @Test
    void lifecycleSourcesHaveNoTimersInputOrAutomation() throws java.io.IOException {
        // Stage 6C.1B/6C.1D correctness is purely event/state based: a clock, a sleep or a
        // duration must never become a lifecycle or witness signal. Pipeline phase timing keeps
        // using the existing System.nanoTime diagnostics, which must not be confused with
        // witness correctness, so FrameRecognitionPipeline/Result/Observation are excluded here
        // and covered by the witness-correctness test below instead.
        java.nio.file.Path runtime = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/io/github/bohdankordon/casinofingerprint/runtime");
        java.util.List<String> lifecycleSources = java.util.List.of(
                "RecognitionIdentity.java",
                "RoundLifecycleTracker.java",
                "RoundLifecycleState.java",
                "RoundLifecycleStatus.java",
                "PuzzleContentTransitionEvidence.java",
                "PuzzleContentTransitionWitness.java",
                "RoundLifecycleWitnessCoordinator.java");
        java.util.List<String> forbiddenTokens = java.util.List.of(
                "System.currentTimeMillis",
                "System.nanoTime",
                "Thread.sleep",
                "java.time.Duration",
                "java.awt.Robot",
                "KeyEvent",
                "MouseEvent",
                "keyPress",
                "keyRelease",
                "mousePress",
                "mouseRelease");
        for (String source : lifecycleSources) {
            String text = java.nio.file.Files.readString(runtime.resolve(source),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String token : forbiddenTokens) {
                assertFalse(text.contains(token),
                        source + " must not use " + token);
            }
        }
    }

    @Test
    void witnessCorrectnessNeverDependsOnATimer() throws java.io.IOException {
        // The witness decision is a pure structural comparison: no clock, sleep, duration or
        // input may appear in the witness, the evidence or the coordinator. Pipeline phase
        // timing (System.nanoTime diagnostics in FrameRecognitionPipeline and
        // LiveRecognitionRuntime) is explicitly out of scope here.
        java.nio.file.Path runtime = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/io/github/bohdankordon/casinofingerprint/runtime");
        for (String source : java.util.List.of("PuzzleContentTransitionWitness.java",
                "PuzzleContentTransitionEvidence.java",
                "RoundLifecycleWitnessCoordinator.java",
                "FrameRecognitionObservation.java")) {
            String text = java.nio.file.Files.readString(runtime.resolve(source),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String token : java.util.List.of("currentTimeMillis", "nanoTime",
                    "Thread.sleep", "Duration", "Robot", "KeyEvent", "MouseEvent", "keyPress",
                    "keyRelease", "mousePress", "mouseRelease")) {
                assertFalse(text.contains(token), source + " must not use " + token);
            }
        }
    }

    @Test
    void witnessImplementationIsIdentityIndependent() throws java.io.IOException {
        // The visual witness itself must not read identity, decision, consensus or lifecycle
        // state: its only job is comparing normalized structural content with a frozen baseline.
        java.nio.file.Path runtime = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/io/github/bohdankordon/casinofingerprint/runtime");
        String witness = java.nio.file.Files.readString(
                runtime.resolve("PuzzleContentTransitionWitness.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String forbidden : java.util.List.of("FingerprintId", "RecognitionIdentity",
                "selectedCandidate", "selectedCandidates", "RecognitionDecision",
                "LiveRecognitionStatus", "LiveRecognitionState", "RecognitionConsensusTracker",
                "RoundLifecycleTracker", "RoundLifecycleStatus", "RoundLifecycleState")) {
            assertFalse(witness.contains(forbidden),
                    "PuzzleContentTransitionWitness must not reference " + forbidden);
        }
        String evidence = java.nio.file.Files.readString(
                runtime.resolve("PuzzleContentTransitionEvidence.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String forbidden : java.util.List.of("FingerprintId", "RecognitionIdentity",
                "RecognitionDecision", "LiveRecognitionStatus", "RoundLifecycleTracker")) {
            assertFalse(evidence.contains(forbidden),
                    "PuzzleContentTransitionEvidence must not reference " + forbidden);
        }
    }

    private static void assertAllowed(Class<?> owner, Class<?> candidate, String member) {
        Class<?> type = candidate.isArray() ? candidate.getComponentType() : candidate;
        assertFalse(type == Robot.class,
                owner.getSimpleName() + " must not use an AWT Robot outside its capture backend: " + member);
        assertFalse(type.getName().startsWith("java.awt.event."),
                owner.getSimpleName() + " must not use AWT input events: " + member);
        String simpleName = type.getSimpleName().toLowerCase(Locale.ROOT);
        for (String token : FORBIDDEN_TYPE_TOKENS) {
            assertFalse(simpleName.contains(token), owner.getSimpleName() + " must not expose a "
                    + token + " type: " + member);
        }
    }
}
