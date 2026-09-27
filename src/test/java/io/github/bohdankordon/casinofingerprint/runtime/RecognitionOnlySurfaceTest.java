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
            LiveRecognitionOptions.class);

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
