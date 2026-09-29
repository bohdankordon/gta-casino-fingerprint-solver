package io.github.bohdankordon.casinofingerprint.app;

import java.util.Objects;

/**
 * Result of one single-tap input diagnostic run: a status plus a human-readable sentence.
 *
 * <p>A result is terminal for its invocation: nothing is ever retried afterwards.
 * {@link #sent()} is true only for {@link InputDiagnosticStatus#SENT}, the one status under
 * which the backend confirmed a complete tap. A false {@code sent()} is NOT a claim of zero
 * input: {@link InputDiagnosticStatus#INPUT_ERROR} means the single tap invocation was reached
 * and complete delivery was not confirmed, so partial native input may already have been
 * delivered. Callers that need to say "nothing was sent" must check
 * {@link #inputAttempted()} (or {@link InputDiagnosticStatus#zeroInputGuaranteed()}) first.
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

    /**
     * True when the run reached the single tap invocation, whether or not complete delivery was
     * confirmed. False only for the pre-tap refusals, which guarantee zero input.
     */
    public boolean inputAttempted() {
        return status.inputAttempted();
    }
}
