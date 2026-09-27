package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.debug.OpenCvHealthCheck;

/** Stage 0 command-line health check; recognition is not implemented yet. */
public final class FingerprintApplication {
    private FingerprintApplication() {
    }

    public static void main(String[] args) {
        OpenCvHealthCheck.verify();
        System.out.println("OpenCV native health check passed. Recognition is not implemented yet.");
    }
}
