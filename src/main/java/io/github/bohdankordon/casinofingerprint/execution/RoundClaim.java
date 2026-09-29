package io.github.bohdankordon.casinofingerprint.execution;

/**
 * Lifecycle commitment of the exact pending round the executor preflighted.
 *
 * <p>Called exactly once per execution, after every preflight gate passed and before the
 * first gameplay input. Production claims the round through
 * {@code RoundLifecycleWitnessCoordinator.consumeReadyRound} with the SAME observation that
 * preflight inspected, which atomically claims the lifecycle round and re-arms the
 * structural baseline on the exact pre-input content. Once input begins, duplicate automatic
 * execution of the same round is more dangerous than losing automatic retry, so the claim
 * is never repeated and never rolled back here: any later failure latches instead.
 *
 * @throws RuntimeException when the round is no longer consumable; the executor latches
 *         FAULTED and sends no input
 */
public interface RoundClaim {
    /** Consumes the preflighted round; called at most once per execution. */
    void consumeSameFrame();
}
