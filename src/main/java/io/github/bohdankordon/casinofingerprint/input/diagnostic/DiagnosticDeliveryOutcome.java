package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import java.util.Objects;

/**
 * What one characterization delivery actually did, recorded after every attempt, successful or
 * not, so the CLI can state the result truthfully instead of guessing.
 *
 * @param mode the delivery mode that was used
 * @param requestedHoldMillis the explicit hold that was requested; zero for the BATCH modes
 * @param actualHoldMillis the hold that actually elapsed before the key-up
 * @param keyDownConfirmed true when a key-down submission reported the expected inserted event
 * @param keyUpConfirmed true when some key-up submission reported the expected inserted event
 * @param keyUpCleanupAttempted true when this delivery path had to attempt an extra key-up
 *        release after a failed hold-mode delivery (never a second key-down)
 * @param abortedDuringHold true when the emergency abort key released a HOLD-mode key early
 */
public record DiagnosticDeliveryOutcome(DiagnosticInputDeliveryMode mode, long requestedHoldMillis,
        long actualHoldMillis, boolean keyDownConfirmed, boolean keyUpConfirmed,
        boolean keyUpCleanupAttempted, boolean abortedDuringHold) {
    public DiagnosticDeliveryOutcome {
        Objects.requireNonNull(mode, "mode");
    }

    /** True when both the key-down and a key-up submission were confirmed delivered. */
    public boolean complete() {
        return keyDownConfirmed && keyUpConfirmed;
    }
}
