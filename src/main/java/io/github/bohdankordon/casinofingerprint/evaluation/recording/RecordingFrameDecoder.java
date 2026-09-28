package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;

/**
 * Sequential decoder for one recording, built on the OpenCV videoio support that already ships with
 * the project ({@code org.bytedeco.opencv.opencv_videoio.VideoCapture}). No extra decoder dependency
 * is added for these two containers, and the source file is never transcoded, never copied and
 * never modified.
 *
 * <p>Decoding is strictly sequential: the benchmark walks a recording from the first frame to the
 * last, so no random seek is issued and no keyframe index is required. The decoder reports both the
 * zero-based frame index and the timestamp the backend assigns to that frame, and it counts
 * timestamps that do not advance monotonically instead of hiding them.
 *
 * <p>Ownership: the decoder allocates exactly ONE frame {@link Mat} and reuses it for every read,
 * so a full pass over a recording does not depend on garbage collection. {@link #frame()} is
 * borrowed - callers must not close it, modify it or keep it after the next {@link #read()} - and
 * {@link #close()} releases both the frame buffer and the underlying capture, also when a read
 * failed half way through.
 */
public final class RecordingFrameDecoder implements AutoCloseable {

    private final Path video;
    private final VideoCapture capture;
    private final Mat reusableFrame = new Mat();
    private final int width;
    private final int height;
    private final double fps;
    private final long reportedFrameCount;
    private final String backendName;

    private long decodedFrames;
    private double timestampMillis = -1.0;
    private long nonMonotonicTimestamps;
    private long wrongSizedFrames;
    private int depth = -1;
    private int channels = -1;
    private boolean closed;

    private RecordingFrameDecoder(Path video, VideoCapture capture, int width, int height,
            double fps, long reportedFrameCount, String backendName) {
        this.video = video;
        this.capture = capture;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.reportedFrameCount = reportedFrameCount;
        this.backendName = backendName;
    }

    /**
     * Opens {@code video} and validates the container facts the benchmark depends on: the file
     * opens, its decoded frames have the expected size, and the frame rate is finite and positive.
     *
     * @throws IOException when the file does not exist or is not readable
     * @throws IllegalStateException when the backend cannot open it or its metadata is unusable
     */
    public static RecordingFrameDecoder open(Path video, int expectedWidth, int expectedHeight)
            throws IOException {
        Objects.requireNonNull(video, "video");
        if (expectedWidth <= 0 || expectedHeight <= 0) {
            throw new IllegalArgumentException("Expected frame size must be positive");
        }
        if (!Files.isRegularFile(video)) {
            throw new IOException("Recording not found: " + video);
        }
        Loader.load(opencv_core.class);
        Loader.load(opencv_videoio.class);
        VideoCapture capture = new VideoCapture(video.toString());
        boolean opened = capture.isOpened();
        if (!opened) {
            capture.close();
            throw new IllegalStateException("OpenCV could not open the recording: " + video
                    + " (backend may not support this container on this machine)");
        }
        try {
            int width = (int) Math.round(capture.get(opencv_videoio.CAP_PROP_FRAME_WIDTH));
            int height = (int) Math.round(capture.get(opencv_videoio.CAP_PROP_FRAME_HEIGHT));
            if (width != expectedWidth || height != expectedHeight) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "Recording %s decoded as %dx%d, expected %dx%d",
                        video, width, height, expectedWidth, expectedHeight));
            }
            double fps = capture.get(opencv_videoio.CAP_PROP_FPS);
            if (!Double.isFinite(fps) || fps <= 0.0) {
                throw new IllegalStateException(
                        "Recording " + video + " reports a non-usable frame rate: " + fps);
            }
            long frames = (long) Math.round(capture.get(opencv_videoio.CAP_PROP_FRAME_COUNT));
            return new RecordingFrameDecoder(
                    video, capture, width, height, fps, frames, backendNameOf(capture));
        } catch (RuntimeException e) {
            capture.close();
            throw e;
        }
    }

    private static String backendNameOf(VideoCapture capture) {
        try (BytePointer name = capture.getBackendName()) {
            String value = name == null ? null : name.getString();
            return value == null || value.isBlank() ? "unknown" : value;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /**
     * Decodes the next frame into the reusable buffer.
     *
     * @return true when a frame is available, false at the end of the recording
     */
    public boolean read() {
        checkOpen();
        if (!capture.read(reusableFrame)) {
            return false;
        }
        decodedFrames++;
        if (reusableFrame.empty()) {
            throw new IllegalStateException("Decoder returned an empty frame at index "
                    + (decodedFrames - 1) + " of " + video);
        }
        if (reusableFrame.depth() != opencv_core.CV_8U) {
            throw new IllegalStateException("Decoded frame is not 8-bit at index "
                    + (decodedFrames - 1) + ", depth " + reusableFrame.depth());
        }
        int frameChannels = reusableFrame.channels();
        if (frameChannels != 1 && frameChannels != 3 && frameChannels != 4) {
            throw new IllegalStateException("Decoded frame has unusable channel count "
                    + frameChannels + " at index " + (decodedFrames - 1));
        }
        if (reusableFrame.cols() != width || reusableFrame.rows() != height) {
            wrongSizedFrames++;
        }
        depth = reusableFrame.depth();
        channels = frameChannels;
        double millis = capture.get(opencv_videoio.CAP_PROP_POS_MSEC);
        if (!Double.isFinite(millis) || millis < 0.0) {
            millis = timestampMillis < 0.0
                    ? (decodedFrames - 1) * 1000.0 / fps
                    : timestampMillis + 1000.0 / fps;
        } else if (timestampMillis >= 0.0 && millis < timestampMillis) {
            nonMonotonicTimestamps++;
        }
        timestampMillis = millis;
        return true;
    }

    /** Borrowed reusable frame buffer holding the frame most recently decoded. */
    public Mat frame() {
        checkOpen();
        return reusableFrame;
    }

    /** Zero-based index of the frame currently in {@link #frame()}, or -1 before the first read. */
    public long frameIndex() {
        return decodedFrames - 1;
    }

    /** Timestamp of the frame currently in {@link #frame()}, in milliseconds. */
    public double timestampMillis() {
        return timestampMillis;
    }

    /** Timestamp of the frame currently in {@link #frame()}, in seconds. */
    public double timestampSeconds() {
        return timestampMillis / 1000.0;
    }

    public double fps() {
        return fps;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Frame count the container header advertises; may differ from a full sequential decode. */
    public long reportedFrameCount() {
        return reportedFrameCount;
    }

    /** Frames successfully decoded so far. */
    public long decodedFrames() {
        return decodedFrames;
    }

    /** Backend that actually decoded the file, for example {@code FFMPEG}. */
    public String backendName() {
        return backendName;
    }

    /** Depth of the decoded frames; validated as {@code CV_8U} on every read. */
    public int depth() {
        return depth;
    }

    /** Channel count of the decoded frames; 3 means BGR-compatible. */
    public int channels() {
        return channels;
    }

    /** Frames whose timestamp did not advance, which would break time-based interval selection. */
    public long nonMonotonicTimestamps() {
        return nonMonotonicTimestamps;
    }

    /** Frames whose decoded size differed from the validated container size. */
    public long wrongSizedFrames() {
        return wrongSizedFrames;
    }

    /** One-line decoder description used by the benchmark report. */
    public String describe() {
        return String.format(Locale.ROOT,
                "%s backend=%s %dx%d %.6f fps reported frames %d depth %d channels %d",
                video.getFileName(), backendName, width, height, fps, reportedFrameCount,
                depth, channels);
    }

    /** Releases the frame buffer and the capture. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            capture.release();
        } finally {
            try {
                reusableFrame.close();
            } finally {
                capture.close();
            }
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Decoder is closed: " + video);
        }
    }
}
