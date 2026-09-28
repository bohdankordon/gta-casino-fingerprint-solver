package io.github.bohdankordon.casinofingerprint.navigation;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable directed graph of evidenced selector transitions.
 *
 * <p>Each entry answers one question that the Stage 7A real-recording analysis settled by
 * direct observation: with the selector focused on one candidate, one orthogonal step lands
 * on the orthogonally adjacent candidate. Thirty-three Enter presses held focus, selected
 * candidates stayed navigable, every round started with focus on C0, and every witnessed
 * single step moved between orthogonal neighbours. No outward edge step was ever attempted,
 * so no edge transition exists here: grid edges neither clamp nor wrap in this model, they
 * are simply absent, and any plan needing one is NON_EXECUTABLE by construction.
 *
 * <p>Use {@link #characterized()} for the production evidence-backed graph (all twenty
 * interior directed transitions, each witnessed at least once across the eight annotated
 * rounds). Use {@link #empty()} or {@link #builder()} for tests and for explicitly
 * assumption-free contexts. There is deliberately no clamp-by-default and no wrap-by-default.
 */
public final class ProvenGridNavigationPolicy implements GridNavigationPolicy {
    private final Map<GridPosition, EnumMap<Move, GridPosition>> transitions;

    private ProvenGridNavigationPolicy(Map<GridPosition, EnumMap<Move, GridPosition>> transitions) {
        this.transitions = transitions;
    }

    /** Starts an explicitly enumerated transition graph. */
    public static Builder builder() {
        return new Builder();
    }

    /** A policy with no proven transition at all: every plan using it is blocked. */
    public static ProvenGridNavigationPolicy empty() {
        return builder().build();
    }

    /**
     * The Stage 7A characterized interior graph: every directed transition between
     * orthogonally adjacent candidates, each witnessed as a single selector step in the
     * real recordings (eight rounds, two resolutions). Outward edge transitions are
     * absent: no edge step was ever attempted, so clamp-vs-wrap stays UNKNOWN and unmodelled.
     */
    public static ProvenGridNavigationPolicy characterized() {
        Builder graph = builder();
        graph.add(GridPosition.C0, Move.RIGHT, GridPosition.C1);
        graph.add(GridPosition.C1, Move.LEFT, GridPosition.C0);
        graph.add(GridPosition.C2, Move.RIGHT, GridPosition.C3);
        graph.add(GridPosition.C3, Move.LEFT, GridPosition.C2);
        graph.add(GridPosition.C4, Move.RIGHT, GridPosition.C5);
        graph.add(GridPosition.C5, Move.LEFT, GridPosition.C4);
        graph.add(GridPosition.C6, Move.RIGHT, GridPosition.C7);
        graph.add(GridPosition.C7, Move.LEFT, GridPosition.C6);
        graph.add(GridPosition.C0, Move.DOWN, GridPosition.C2);
        graph.add(GridPosition.C2, Move.UP, GridPosition.C0);
        graph.add(GridPosition.C2, Move.DOWN, GridPosition.C4);
        graph.add(GridPosition.C4, Move.UP, GridPosition.C2);
        graph.add(GridPosition.C4, Move.DOWN, GridPosition.C6);
        graph.add(GridPosition.C6, Move.UP, GridPosition.C4);
        graph.add(GridPosition.C1, Move.DOWN, GridPosition.C3);
        graph.add(GridPosition.C3, Move.UP, GridPosition.C1);
        graph.add(GridPosition.C3, Move.DOWN, GridPosition.C5);
        graph.add(GridPosition.C5, Move.UP, GridPosition.C3);
        graph.add(GridPosition.C5, Move.DOWN, GridPosition.C7);
        graph.add(GridPosition.C7, Move.UP, GridPosition.C5);
        return graph.build();
    }

    @Override
    public Optional<GridPosition> move(GridPosition from, Move move) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(move, "move");
        EnumMap<Move, GridPosition> exits = transitions.get(from);
        if (exits == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(exits.get(move));
    }

    /** Number of proven directed transitions in this graph. */
    public int transitionCount() {
        int total = 0;
        for (EnumMap<Move, GridPosition> exits : transitions.values()) {
            total += exits.size();
        }
        return total;
    }

    /** Explicit transition-graph builder. Every added transition must be a single
     * orthogonal step: the destination must neighbour the origin. */
    public static final class Builder {
        private final Map<GridPosition, EnumMap<Move, GridPosition>> transitions = new HashMap<>();

        /**
         * Records one evidenced directed transition.
         *
         * @throws IllegalArgumentException when the destination is not orthogonally
         *         adjacent to the origin (a single step always neighbours its origin)
         */
        public Builder add(GridPosition from, Move move, GridPosition to) {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(move, "move");
            Objects.requireNonNull(to, "to");
            int distance = Math.abs(to.row() - from.row()) + Math.abs(to.column() - from.column());
            if (distance != 1) {
                throw new IllegalArgumentException("Single steps land next door: " + from + " " + move + " to " + to);
            }
            transitions.computeIfAbsent(from, ignored -> new EnumMap<>(Move.class)).put(move, to);
            return this;
        }

        /** Freezes an immutable proven graph. */
        public ProvenGridNavigationPolicy build() {
            Map<GridPosition, EnumMap<Move, GridPosition>> frozen = new HashMap<>();
            for (Map.Entry<GridPosition, EnumMap<Move, GridPosition>> entry : transitions.entrySet()) {
                frozen.put(entry.getKey(), new EnumMap<>(entry.getValue()));
            }
            return new ProvenGridNavigationPolicy(Map.copyOf(frozen));
        }
    }
}
