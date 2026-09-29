package io.github.bohdankordon.casinofingerprint.app;

import java.util.Objects;

/**
 * Result of one single-tap input diagnostic run: a status plus a human-readable sentence.
 *
 * <p>A result is terminal for its invocation. {@link #sent()} is true only for
 * {@link InputDiagnosticStatus#SENT}; every other status means the run refused and emitted zero
 * input, and nothing is ever retried afterwards.
 *
 * @param status terminal status of the run; required
 * @param message concise explanation for the human operator; required
 */
public record InputDiagnosticResult(InputDiagnosticStatus status, String message) {
    public InputDiagnosticResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }

    /** True only when exactly one complete tap was submitted to the input sink. */
    public boolean sent() {
        return status == InputDiagnosticStatus.SENT;
    }
}
