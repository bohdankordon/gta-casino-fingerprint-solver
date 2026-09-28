package io.github.bohdankordon.casinofingerprint.navigation;

import java.util.Objects;
import java.util.Optional;

/**
 * Proven selector transitions on the 2x4 fingerprint grid.
 *
 * <p>Given a focused candidate and an abstract navigation intention, answers where the
 * selector would be afterwards. An empty answer means there is NO proven transition: either
 * the move would leave the grid (edge behaviour was never observed, so it stays absent) or
 * the transition was simply never witnessed. Callers must treat an empty answer as
 * NON_EXECUTABLE, never as permission to guess.
 *
 * <p>The policy is a pure directed graph of evidenced transitions. It contains no timers, no
 * input APIs and no key codes.
 */
public interface GridNavigationPolicy {
    /**
     * Follows one abstract move from the given focused position.
     *
     * @param from focused candidate before the move; required
     * @param move abstract navigation intention; required
     * @return the focused candidate after the move, or empty when no transition is proven
     */
    Optional<GridPosition> move(GridPosition from, Move move);

    /**
     * True when this exact directed transition is part of the proven graph.
     *
     * @param from focused candidate before the move; required
     * @param move abstract navigation intention; required
     */
    default boolean hasTransition(GridPosition from, Move move) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(move, "move");
        return move(from, move).isPresent();
    }
}
