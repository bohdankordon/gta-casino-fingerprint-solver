package io.github.bohdankordon.casinofingerprint.app;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Automatic per-session log policy for armed operator sessions.
 *
 * <p>Session logs are user data, never installation content: they live under
 *
 * <pre>
 * LOCALAPPDATA/BohdanKordon/GTA Casino Fingerprint Solver/logs/
 * </pre>
 *
 * <p>When LOCALAPPDATA is unavailable the policy falls back to
 * user.home/AppData/Local with the same vendor and product segments, and only
 * when no home directory is known at all to the JVM temporary directory. The
 * policy never writes into Program Files or the installed application directory,
 * and normal uninstall preserves historical logs because no uninstall custom
 * action deletes them.
 *
 * <p>File names look like session-20251001-203045-p1234.log (UTC timestamp plus
 * process id); a numeric suffix disambiguates collisions so creation never
 * overwrites an existing log.
 */
public final class SessionLogPolicy {
    /** Vendor directory below the base user-data directory. */
    public static final String VENDOR_DIRECTORY = "BohdanKordon";
    /** Product directory below the vendor directory. */
    public static final String PRODUCT_DIRECTORY = "GTA Casino Fingerprint Solver";
    /** Log directory below the product directory. */
    public static final String LOG_DIRECTORY_NAME = "logs";

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private SessionLogPolicy() {
    }

    /** Resolves the product log directory from the real process environment. */
    public static Path productLogDirectory() {
        return productLogDirectory(System.getenv("LOCALAPPDATA"),
                System.getProperty("user.home"));
    }

    /**
     * Testable resolution with injected values.
     *
     * @param localAppData value of the LOCALAPPDATA environment variable, may be null
     * @param userHome value of the user.home system property, may be null
     */
    static Path productLogDirectory(String localAppData, String userHome) {
        Path base;
        if (localAppData != null && !localAppData.isBlank()) {
            base = Path.of(localAppData.strip());
        } else if (userHome != null && !userHome.isBlank()) {
            base = Path.of(userHome.strip()).resolve("AppData").resolve("Local");
        } else {
            base = Path.of(System.getProperty("java.io.tmpdir", "."));
        }
        return base.resolve(VENDOR_DIRECTORY).resolve(PRODUCT_DIRECTORY)
                .resolve(LOG_DIRECTORY_NAME);
    }

    /**
     * Builds the session file name for one timestamp and process id. The name uses
     * only file-safe characters.
     */
    public static String sessionFileName(Instant timestamp, long pid) {
        Objects.requireNonNull(timestamp, "timestamp");
        return "session-" + FILE_STAMP.format(timestamp) + "-p" + pid + ".log";
    }

    /**
     * Creates a new, empty session log file inside an existing or new log directory.
     * Retries with a numeric suffix on collision, so an existing log is never
     * overwritten.
     *
     * @return the created file
     */
    public static Path createNewLogFile(Path directory, Instant timestamp, long pid)
            throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(timestamp, "timestamp");
        Files.createDirectories(directory);
        String stem = sessionFileName(timestamp, pid);
        String base = stem.substring(0, stem.length() - ".log".length());
        Path candidate = directory.resolve(stem);
        int suffix = 1;
        while (true) {
            try {
                Files.createFile(candidate);
                return candidate;
            } catch (FileAlreadyExistsException alreadyExists) {
                suffix++;
                candidate = directory.resolve(base + "-" + suffix + ".log");
            }
        }
    }
}
