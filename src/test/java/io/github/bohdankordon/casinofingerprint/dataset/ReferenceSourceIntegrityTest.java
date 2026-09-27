package io.github.bohdankordon.casinofingerprint.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Guards the immutable source collage against silent re-encoding or replacement. */
class ReferenceSourceIntegrityTest {
    @Test
    void sourceFileBytesMatchDocumentedSha256() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path source = projectRoot.resolve(ReferenceDatasetGenerator.SOURCE_REL);
        assertTrue(Files.isRegularFile(source), "Source image must exist: " + source);
        byte[] bytes = Files.readAllBytes(source);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha256.digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        assertEquals(ReferenceDatasetGenerator.EXPECTED_SOURCE_SHA256.toLowerCase(Locale.ROOT),
                hex.toString(),
                "Source collage bytes changed; provenance requires byte-for-byte preservation");
    }
}
