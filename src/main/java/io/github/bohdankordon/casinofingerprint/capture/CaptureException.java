package io.github.bohdankordon.casinofingerprint.capture;

/**
 * A live desktop capture could not produce a usable frame: no desktop session, no attached
 * monitor, a backend failure, or a capture that cannot serve the required physical resolution.
 *
 * <p>This is a RUNTIME condition, not a programmer error: live runtimes report it as a
 * {@code CAPTURE_ERROR} state and keep running instead of crashing with a stack trace. Callers
 * that construct capture objects with impossible arguments still get the usual
 * {@link IllegalArgumentException} / {@link NullPointerException} contracts.
 */
public class CaptureException extends RuntimeException {
    public CaptureException(String message) {
        super(message);
    }

    public CaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
