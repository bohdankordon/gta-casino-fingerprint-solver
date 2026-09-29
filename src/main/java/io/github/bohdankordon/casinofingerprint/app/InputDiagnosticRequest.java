package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import java.util.Objects;

/**
 * One explicitly requested single-tap diagnostic: the target executable, the one allowed
 * gameplay control, the opt-in flag and the countdown length.
 *
 * <p>The request carries no plan, no recognition result and no repetition count: one request may
 * lead to at most one complete tap. {@link GameControl#PROCEED} is accepted here so the core can
 * refuse it explicitly with {@link InputDiagnosticStatus#UNSUPPORTED_CONTROL} (Tab is never sent
 * in Stage 8C); the CLI refuses it before a request is ever built.
 *
 * @param targetExecutable exact foreground executable file name, for example {@code GTA5.exe}
 * @param control the single Stage 8C control to tap; required
 * @param inputEnabled explicit input opt-in; false refuses with zero input
 * @param countdownSeconds seconds to switch to GTA before the foreground gates
 */
public record InputDiagnosticRequest(String targetExecutable, GameControl control,
        boolean inputEnabled, int countdownSeconds) {

    /** A reasonable default countdown: enough time to Alt+Tab into GTA. */
    public static final int DEFAULT_COUNTDOWN_SECONDS = 5;
    /** Lower bound: any smaller countdown is a mistyped invocation. */
    public static final int MIN_COUNTDOWN_SECONDS = 1;
    /** Upper bound keeps a mistyped countdown from looking like a hang. */
    public static final int MAX_COUNTDOWN_SECONDS = 30;

    public InputDiagnosticRequest {
        Objects.requireNonNull(targetExecutable, "targetExecutable");
        Objects.requireNonNull(control, "control");
        if (targetExecutable.isBlank()) {
            throw new IllegalArgumentException(
                    "--target-exe <exact executable name> is required");
        }
        if (countdownSeconds < MIN_COUNTDOWN_SECONDS
                || countdownSeconds > MAX_COUNTDOWN_SECONDS) {
            throw new IllegalArgumentException("--countdown-seconds must be between "
                    + MIN_COUNTDOWN_SECONDS + " and " + MAX_COUNTDOWN_SECONDS + ", got "
                    + countdownSeconds);
        }
    }
}
