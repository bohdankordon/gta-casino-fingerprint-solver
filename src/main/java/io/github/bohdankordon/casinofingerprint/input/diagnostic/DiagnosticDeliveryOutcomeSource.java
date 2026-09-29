package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import java.util.Optional;

/**
 * Read-only view of the last characterization delivery, for truthful operator reporting. Both
 * the pure delivery core and the Windows diagnostic sink expose it, so the CLI can print what
 * actually happened -- including a failed attempt -- instead of inferring it from the exit
 * code.
 */
public interface DiagnosticDeliveryOutcomeSource {
    /** The outcome of the last delivery attempt, or empty when nothing was delivered yet. */
    Optional<DiagnosticDeliveryOutcome> lastOutcome();
}
