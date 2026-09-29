package io.github.bohdankordon.casinofingerprint.input;

/**
 * Global emergency abort usable while GTA holds OS focus.
 *
 * <p>Checked before execution starts, before every gameplay input, while waiting for visual
 * confirmation and before PROCEED. Once active, no further gameplay input is sent and the
 * execution latches ABORTED with no automatic resume: only an explicit reset or an
 * application restart clears it.
 */
public interface AbortSignal {
    /** True when the emergency abort key is currently held down. */
    boolean isActive();
}
