package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.util.List;
import java.util.Objects;

/**
 * Decides which decoded frames belong to one benchmark population.
 *
 * <p>Selections are pure functions of the frame index and its timestamp, so a population is fully
 * reproducible from the annotation and the cadence alone.
 */
@FunctionalInterface
public interface FrameSelection {

    /**
     * @param frameIndex zero-based index of the decoded frame
     * @param timestampSeconds timestamp reported by the decoder for that frame
     * @return true when this frame belongs to the population
     */
    boolean includes(long frameIndex, double timestampSeconds);

    /** Every decoded frame. */
    static FrameSelection everyFrame() {
        return (frameIndex, timestampSeconds) -> true;
    }

    /** Every frame whose timestamp lies inside the closed interval. */
    static FrameSelection interval(double startSeconds, double endSeconds) {
        TimeWindow window = new TimeWindow(startSeconds, endSeconds);
        return (frameIndex, timestampSeconds) -> window.contains(timestampSeconds);
    }

    /** Every frame whose timestamp lies inside any of {@code windows}. */
    static FrameSelection inside(List<TimeWindow> windows) {
        Objects.requireNonNull(windows, "windows");
        List<TimeWindow> copy = List.copyOf(windows);
        return (frameIndex, timestampSeconds) -> copy.stream()
                .anyMatch(window -> window.contains(timestampSeconds));
    }

    /** Frames of the fixed index cadence. */
    static FrameSelection cadence(int step) {
        return (frameIndex, timestampSeconds) -> FrameCadence.includes(frameIndex, step);
    }

    /** Frames of the cadence whose timestamp lies inside the closed interval. */
    static FrameSelection cadenceWithin(int step, double startSeconds, double endSeconds) {
        TimeWindow window = new TimeWindow(startSeconds, endSeconds);
        return (frameIndex, timestampSeconds) ->
                FrameCadence.includes(frameIndex, step) && window.contains(timestampSeconds);
    }

    /** Frames of the cadence that lie outside every excluded window. */
    static FrameSelection cadenceOutside(int step, List<TimeWindow> excludedWindows) {
        Objects.requireNonNull(excludedWindows, "excludedWindows");
        List<TimeWindow> copy = List.copyOf(excludedWindows);
        return (frameIndex, timestampSeconds) ->
                FrameCadence.includes(frameIndex, step) && copy.stream()
                        .noneMatch(window -> window.contains(timestampSeconds));
    }

    /** Selection that requires both operands. */
    static FrameSelection and(FrameSelection first, FrameSelection second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return (frameIndex, timestampSeconds) ->
                first.includes(frameIndex, timestampSeconds)
                        && second.includes(frameIndex, timestampSeconds);
    }
}
