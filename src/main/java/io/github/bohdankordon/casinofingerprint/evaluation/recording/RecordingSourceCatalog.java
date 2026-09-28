package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the committed Stage 6 recording source metadata
 * ({@code fixtures/gameplay/recordings/stage6-sources.csv}).
 *
 * <p>Evaluation only. The CSV holds generic source ids and technical container facts; the private
 * recordings themselves stay local and ignored.
 */
public final class RecordingSourceCatalog {
    /** Repo-relative source metadata path (forward slashes). */
    public static final String SOURCES_REL =
            "fixtures/gameplay/recordings/stage6-sources.csv";

    static final String HEADER = "source_id,file_name,container,width,height,fps,"
            + "frames_decoded,duration_seconds,size_bytes,sha256";

    private RecordingSourceCatalog() {
    }

    /** Reads and validates the source metadata CSV. */
    public static List<RecordingSource> read(Path csv) throws IOException {
        CsvTable table = CsvTable.read(csv, HEADER);
        List<RecordingSource> sources = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            sources.add(new RecordingSource(
                    row.text(0, "source_id"),
                    row.text(1, "file_name"),
                    row.text(2, "container"),
                    row.integer(3, "width"),
                    row.integer(4, "height"),
                    row.decimal(5, "fps"),
                    row.longInteger(6, "frames_decoded"),
                    row.decimal(7, "duration_seconds"),
                    row.longInteger(8, "size_bytes"),
                    row.text(9, "sha256").toUpperCase(Locale.ROOT)));
        }
        validate(sources);
        return List.copyOf(sources);
    }

    /** Reads the committed source metadata below {@code projectRoot}. */
    public static List<RecordingSource> readCommitted(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        return read(projectRoot.resolve(SOURCES_REL));
    }

    /** Rejects empty catalogs and duplicate source ids or file names. */
    public static void validate(List<RecordingSource> sources) {
        Objects.requireNonNull(sources, "sources");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Source catalog must not be empty");
        }
        Set<String> ids = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (RecordingSource source : sources) {
            Objects.requireNonNull(source, "source");
            if (!ids.add(source.sourceId())) {
                throw new IllegalArgumentException("Duplicate source_id: " + source.sourceId());
            }
            if (!files.add(source.fileName())) {
                throw new IllegalArgumentException("Duplicate file_name: " + source.fileName());
            }
        }
    }

    /** Source with {@code sourceId}, or an {@link IllegalArgumentException} naming the known ids. */
    public static RecordingSource require(List<RecordingSource> sources, String sourceId) {
        for (RecordingSource source : sources) {
            if (source.sourceId().equals(sourceId)) {
                return source;
            }
        }
        throw new IllegalArgumentException(
                "Unknown source_id " + sourceId + "; known ids: " + sources.stream()
                        .map(RecordingSource::sourceId).toList());
    }

    /** Uppercase hex SHA-256 of a local file. */
    public static String sha256(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString().toUpperCase(Locale.ROOT);
    }
}
