package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * In-memory {@link ScreenCapture} for runtime orchestration tests: no display, no Robot and no
 * AWT dependency, just a scripted sequence of frames and failures.
 *
 * <p>Each queued frame is cloned when it is captured, so the object handed to the runtime is
 * independently owned exactly like a real capture, and closing it cannot disturb the prototype.
 */
public final class FakeScreenCapture implements ScreenCapture {
    private final List<Supplier<Mat>> steps = new ArrayList<>();
    private final List<Mat> captured = new ArrayList<>();
    private int next;

    public static FakeScreenCapture create() {
        return new FakeScreenCapture();
    }

    /** Next capture returns a fresh clone of {@code prototype}. */
    public FakeScreenCapture frame(Mat prototype) {
        steps.add(prototype::clone);
        return this;
    }

    /** Next {@code count} captures return fresh clones of {@code prototype}. */
    public FakeScreenCapture frames(Mat prototype, int count) {
        for (int i = 0; i < count; i++) {
            frame(prototype);
        }
        return this;
    }

    /** Next capture returns {@code prototype} resized to a different (logical) size. */
    public FakeScreenCapture resizedFrame(Mat prototype, int width, int height) {
        steps.add(() -> {
            Mat resized = new Mat();
            opencv_imgproc.resize(prototype, resized, new Size(width, height));
            return resized;
        });
        return this;
    }

    /** Next capture fails with {@code failure}, emulating a capture backend error. */
    public FakeScreenCapture failure(RuntimeException failure) {
        steps.add(() -> {
            throw failure;
        });
        return this;
    }

    @Override
    public Mat capture() {
        if (next >= steps.size()) {
            throw new IllegalStateException(
                    "FakeScreenCapture has no step queued for capture " + (next + 1));
        }
        Mat frame = steps.get(next++).get();
        captured.add(frame);
        return frame;
    }

    /** Number of captures performed so far. */
    public int captureCount() {
        return next;
    }

    /** Frames handed to the runtime so far, in capture order. */
    public List<Mat> capturedFrames() {
        return List.copyOf(captured);
    }
}
