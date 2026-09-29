package io.github.bohdankordon.casinofingerprint.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.2 production-regression pin: the characterization stage must not touch the
 * production {@code WindowsSendInputSink}, so its source is frozen byte-for-byte (modulo the
 * line-ending convention of the checkout). Any future intentional change to the production
 * backend must update this digest deliberately in the stage that promotes the change.
 */
class WindowsSendInputSinkUnchangedTest {
    /**
     * SHA-256 of the newline-normalized production source at the Stage 8C.2 base commit
     * {@code 0078fd1c2f5ff8d20038f6d00c0d41b9c1afc74a}.
     */
    private static final String PINNED_SHA256 =
            "f8de0a9b5393ee88fef16c6f291ca8566995da3aac85836b9a4b4b20bd38a7d2";

    @Test
    void theProductionSendInputBackendSourceIsUnchanged() throws IOException {
        Path source = Path.of(System.getProperty("user.dir")).resolve(
                "src/main/java/io/github/bohdankordon/casinofingerprint/input/win32/"
                        + "WindowsSendInputSink.java");
        assertTrue(Files.exists(source), "The production backend source must exist: " + source);
        String text = Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(PINNED_SHA256, sha256(text),
                "Stage 8C.2 must not change WindowsSendInputSink: the production batch backend"
                        + " stays byte-for-byte identical to the base commit");
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
