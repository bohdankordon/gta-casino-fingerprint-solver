package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Session logs resolve below user data (never the installation), use safe
 * unique names and round-trip UTF-8.
 */
class SessionLogPolicyTest {
    @TempDir
    Path temporary;

    @Test
    void localAppDataSelectsVendorProductLogs() {
        Path logs = SessionLogPolicy.productLogDirectory(
                temporary.toString(), "C:/unused-home");
        assertEquals(temporary.resolve("BohdanKordon")
                .resolve("GTA Casino Fingerprint Solver").resolve("logs"), logs);
    }

    @Test
    void missingLocalAppDataFallsBackBelowUserHome() {
        Path logs = SessionLogPolicy.productLogDirectory(null, temporary.toString());
        assertEquals(temporary.resolve("AppData").resolve("Local")
                .resolve("BohdanKordon").resolve("GTA Casino Fingerprint Solver")
                .resolve("logs"), logs);
        assertTrue(logs.toString().contains("BohdanKordon"));
    }

    @Test
    void userDataStaysOutsideTheInstallation() {
        Path installRoot = temporary.resolve("install");
        Path logs = SessionLogPolicy.productLogDirectory(
                temporary.resolve("user-data").toString(), temporary.toString());
        assertFalse(logs.startsWith(installRoot),
                "Session logs must never live inside the installation: " + logs);
    }

    @Test
    void fileNameIsTimestampedSafeAndUnique() {
        String name =
                SessionLogPolicy.sessionFileName(Instant.parse("2025-10-01T20:30:45Z"), 1234);
        assertEquals("session-20251001-203045-p1234.log", name);
        assertTrue(name.matches("[A-Za-z0-9._-]+"), name);
    }

    @Test
    void creationNeverOverwritesAnExistingLog() throws IOException {
        Path directory = temporary.resolve("logs");
        Instant timestamp = Instant.parse("2025-10-01T20:30:45Z");
        Path first = SessionLogPolicy.createNewLogFile(directory, timestamp, 42);
        Path second = SessionLogPolicy.createNewLogFile(directory, timestamp, 42);
        assertTrue(Files.isRegularFile(first));
        assertTrue(Files.isRegularFile(second));
        assertTrue(!first.equals(second), "Collision must create a suffixed file");
        assertTrue(second.getFileName().toString().endsWith("-2.log"),
                second.getFileName().toString());
    }

    @Test
    void logContentRoundTripsUtf8() throws IOException {
        Path directory = temporary.resolve("logs");
        Path log = SessionLogPolicy.createNewLogFile(directory, Instant.now(), 7);
        List<String> lines = List.of("OPERATOR SESSION START",
                "Zażółć gęślą jaźń - UTF-8 check: ąęłśćńóżź");
        Files.write(log, lines, StandardCharsets.UTF_8);
        assertEquals(lines, Files.readAllLines(log, StandardCharsets.UTF_8));
    }
}
