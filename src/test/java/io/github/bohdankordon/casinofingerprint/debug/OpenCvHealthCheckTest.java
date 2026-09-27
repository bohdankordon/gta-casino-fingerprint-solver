package io.github.bohdankordon.casinofingerprint.debug;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class OpenCvHealthCheckTest {
    @Test
    void nativeOpenCvLoadsAndCountsPixels() {
        assertDoesNotThrow(OpenCvHealthCheck::verify);
    }
}
