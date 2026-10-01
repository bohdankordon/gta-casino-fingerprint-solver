package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The installer upgrade identity is generated once and committed: future
 * releases must keep reusing the same Windows upgrade UUID instead of
 * minting a new one per build.
 */
class WindowsInstallerIdentityTest {
    private static final Path UUID_FILE =
            Path.of(System.getProperty("user.dir")).toAbsolutePath()
                    .resolve("packaging").resolve("windows")
                    .resolve("win-upgrade-uuid.txt");

    @Test
    void permanentUpgradeUuidIsSingleAndWellFormed() throws IOException {
        assertTrue(Files.isRegularFile(UUID_FILE),
                "The committed upgrade UUID is missing: " + UUID_FILE);
        List<String> meaningful = Files.readAllLines(UUID_FILE, StandardCharsets.UTF_8)
                .stream().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        assertEquals(1, meaningful.size(),
                "Exactly one UUID must be committed: " + meaningful);
        UUID parsed = UUID.fromString(meaningful.get(0));
        assertEquals(meaningful.get(0), parsed.toString().toUpperCase(),
                "The UUID is stored canonically");
    }
}
