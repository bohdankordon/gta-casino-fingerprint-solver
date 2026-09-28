package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.util.Locale;

/**
 * Deterministic frame-index sampling cadence.
 *
 * <p>A target sampling rate is expressed as an integer frame STEP derived from the source frame
 * rate: {@code step = max(1, round(fps / targetFps))}, and a frame is sampled exactly when its
 * zero-based index is divisible by that step. This is a pure arithmetic convention: the same video
 * on the same machine always produces the same sampled set, no randomness and no time-based
 * scheduling are involved. When the source frame rate is not a multiple of the target rate the
 * effective rate is {@code fps / step}, which is reported alongside every result.
 */
public final class FrameCadence {
    private FrameCadence() {
    }

    /** Integer frame step closest to {@code fps / targetFramesPerSecond}; at least one. */
    public static int stepFor(double fps, int targetFramesPerSecond) {
        if (!Double.isFinite(fps) || fps <= 0.0) {
            throw new IllegalArgumentException("fps must be finite and positive, got " + fps);
        }
        if (targetFramesPerSecond < 1) {
            throw new IllegalArgumentException(
                    "targetFramesPerSecond must be positive, got " + targetFramesPerSecond);
        }
        return Math.max(1, (int) Math.round(fps / targetFramesPerSecond));
    }

    /** True when the zero-based {@code frameIndex} is part of the cadence. */
    public static boolean includes(long frameIndex, int step) {
        if (step < 1) {
            throw new IllegalArgumentException("step must be positive, got " + step);
        }
        if (frameIndex < 0) {
            throw new IllegalArgumentException("frameIndex must be non-negative, got " + frameIndex);
        }
        return frameIndex % step == 0;
    }

    /** Sampling rate the cadence actually delivers, in frames per second. */
    public static double effectiveFps(double fps, int step) {
        if (step < 1) {
            throw new IllegalArgumentException("step must be positive, got " + step);
        }
        if (!Double.isFinite(fps) || fps <= 0.0) {
            throw new IllegalArgumentException("fps must be finite and positive, got " + fps);
        }
        return fps / step;
    }

    /** Human-readable convention, used in reports so the cadence is never implicit. */
    public static String describe(double fps, int targetFramesPerSecond, int step) {
        return String.format(Locale.ROOT,
                "step %d of a %.6f fps source (every %dth frame, effective %.4f fps), target %d fps",
                step, fps, step, effectiveFps(fps, step), targetFramesPerSecond);
    }
}
