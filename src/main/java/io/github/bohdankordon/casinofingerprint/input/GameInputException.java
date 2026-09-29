package io.github.bohdankordon.casinofingerprint.input;

/** Unchecked failure of a gameplay input backend: nothing about delivery can be assumed. */
public final class GameInputException extends RuntimeException {
    public GameInputException(String message) {
        super(message);
    }

    public GameInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
