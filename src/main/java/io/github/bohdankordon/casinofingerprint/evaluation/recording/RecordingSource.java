package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * One locally held gameplay screen recording, addressed by a generic source id.
 *
 * <p>Evaluation only. The recordings themselves are private local material: they are never
 * committed, never copied into test resources and never uploaded. This record carries the metadata
 * that is safe to commit - a generic source id, a plain file name, resolution, container, frame
 * rate, decoded frame count, derived duration, byte size and SHA-256 - and it knows where the
 * file has to sit locally. No personal user name, no absolute path and no screen content is part
 * of it.
 *
 * @param sourceId generic id used by every annotation and report row, for example
 *        {@code recording_1440p}
 * @param fileName plain file name inside the local recording directory
 * @param container container format label, for example {@code matroska} or {@code mp4}
 * @param width decoded frame width in pixels
 * @param height decoded frame height in pixels
 * @param fps frame rate reported by the decoder
 * @param framesDecoded number of frames a full sequential decode produced
 * @param durationSeconds derived duration, {@code framesDecoded / fps}
 * @param sizeBytes byte size of the local file
 * @param sha256 uppercase hex SHA-256 of the local file bytes
 */
public record RecordingSource(
        String sourceId,
        String fileName,
        String container,
        int width,
        int height,
        double fps,
        long framesDecoded,
        double durationSeconds,
        long sizeBytes,
        String sha256) {

    /** Repo-relative directory the private recordings are expected in (forward slashes). */
    public static final String LOCAL_DIRECTORY_REL = "local-data/stage6";

    public RecordingSource {
        requireId("sourceId", sourceId);
        Objects.requireNonNull(fileName, "fileName");
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("fileName must be a plain file name, got " + fileName);
        }
        Objects.requireNonNull(container, "container");
        if (container.isBlank()) {
            throw new IllegalArgumentException("container must not be blank");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive, got "
                    + width + "x" + height);
        }
        if (!Double.isFinite(fps) || fps <= 0.0) {
            throw new IllegalArgumentException("fps must be finite and positive, got " + fps);
        }
        if (framesDecoded <= 0) {
            throw new IllegalArgumentException("framesDecoded must be positive, got " + framesDecoded);
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0.0) {
            throw new IllegalArgumentException(
                    "durationSeconds must be finite and positive, got " + durationSeconds);
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive, got " + sizeBytes);
        }
        if (sha256 == null || !sha256.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException(
                    "sha256 must be 64 uppercase hex characters, got " + sha256);
        }
    }

    /** {@code WIDTHxHEIGHT}, for example {@code 2560x1440}. */
    public String resolution() {
        return width + "x" + height;
    }

    /** Repo-relative path of the private local recording (forward slashes). */
    public String localRelativePath() {
        return LOCAL_DIRECTORY_REL + "/" + fileName;
    }

    /** Absolute local path of the recording, resolved against the repository root. */
    public Path localPath(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        return projectRoot.resolve(localRelativePath().replace('/', File.separatorChar));
    }

    /** One-line description used by reports. */
    public String describe() {
        return String.format(Locale.ROOT,
                "%s %s %s %.6f fps %d frames %.3f s %d bytes",
                sourceId, fileName, resolution(), fps, framesDecoded, durationSeconds, sizeBytes);
    }

    private static void requireId(String name, String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    name + " must be a simple identifier, got " + value);
        }
    }
}
