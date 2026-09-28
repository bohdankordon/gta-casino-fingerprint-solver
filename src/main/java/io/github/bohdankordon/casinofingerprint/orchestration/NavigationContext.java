package io.github.bohdankordon.casinofingerprint.orchestration;

import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import io.github.bohdankordon.casinofingerprint.navigation.GridNavigationPolicy;
import io.github.bohdankordon.casinofingerprint.navigation.ProvenGridNavigationPolicy;
import java.util.Objects;
import java.util.Optional;

/**
 * What the dry-run planner may assume about selector navigation.
 *
 * <p>The production context is {@link #characterized()}: the selector starts on C0 (every
 * one of the eight annotated real rounds did, across both resolutions, and every round
 * transition re-armed C0) and moves only along the twenty witnessed interior transitions.
 * Grid edges stay unmodelled because no outward edge step was ever attempted: clamp-vs-wrap
 * is UNKNOWN, and any plan needing such a step is BLOCKED rather than guessed.
 *
 * <p>An unknown start (or an empty graph) makes every plan BLOCKED. The planner never fills
 * the gap: fail-closed, not guessing.
 */
public record NavigationContext(Optional<GridPosition> start, GridNavigationPolicy policy) {
    /** @param start validated round-start focus, when one is established; required wrapper */
    public NavigationContext {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(policy, "policy");
    }

    /** Production Stage 7A context: start C0 plus the characterized interior graph. */
    public static NavigationContext characterized() {
        return new NavigationContext(
                Optional.of(GridPosition.C0), ProvenGridNavigationPolicy.characterized());
    }

    /** Assumption-free context: no start and no transition are established. */
    public static NavigationContext unknown() {
        return new NavigationContext(Optional.empty(), ProvenGridNavigationPolicy.empty());
    }
}
