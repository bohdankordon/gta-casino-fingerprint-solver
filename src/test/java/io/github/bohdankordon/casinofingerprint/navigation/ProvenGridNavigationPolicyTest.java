package io.github.bohdankordon.casinofingerprint.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Synthetic Stage 7A checks for the proven navigation graph: every legal interior transition,
 * absent edge behaviour, deterministic minimal shortest paths.
 */
class ProvenGridNavigationPolicyTest {
    private static final ProvenGridNavigationPolicy CHARACTERIZED =
            ProvenGridNavigationPolicy.characterized();

    @Test
    void characterizedGraphHoldsAllTwentyInteriorDirectedTransitions() {
        assertEquals(20, CHARACTERIZED.transitionCount(),
                "twenty witnessed interior directed transitions");
    }

    @Test
    void everyInteriorMoveSwitchesExactlyOneRowOrColumn() {
        GridPosition[] all = {GridPosition.C0, GridPosition.C1, GridPosition.C2, GridPosition.C3,
                GridPosition.C4, GridPosition.C5, GridPosition.C6, GridPosition.C7};
        int interiorTransitions = 0;
        for (GridPosition from : all) {
            for (Move move : Move.values()) {
                Optional<GridPosition> to = CHARACTERIZED.move(from, move);
                if (to.isEmpty()) {
                    continue;
                }
                interiorTransitions++;
                int distance = Math.abs(to.get().row() - from.row())
                        + Math.abs(to.get().column() - from.column());
                assertEquals(1, distance, "single orthogonal step " + from + " " + move);
            }
        }
        assertEquals(20, interiorTransitions, "all and only the interior transitions");
    }

    @Test
    void edgeBehaviourIsAbsentNeverInvented() {
        // Left column has no LEFT, right column has no RIGHT, top row has no UP,
        // bottom row has no DOWN: clamp-vs-wrap was never observed, so it stays unmodelled.
        assertTrue(CHARACTERIZED.move(GridPosition.C0, Move.LEFT).isEmpty(), "C0 LEFT absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C2, Move.LEFT).isEmpty(), "C2 LEFT absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C4, Move.LEFT).isEmpty(), "C4 LEFT absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C6, Move.LEFT).isEmpty(), "C6 LEFT absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C1, Move.RIGHT).isEmpty(), "C1 RIGHT absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C3, Move.RIGHT).isEmpty(), "C3 RIGHT absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C5, Move.RIGHT).isEmpty(), "C5 RIGHT absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C7, Move.RIGHT).isEmpty(), "C7 RIGHT absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C0, Move.UP).isEmpty(), "C0 UP absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C1, Move.UP).isEmpty(), "C1 UP absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C6, Move.DOWN).isEmpty(), "C6 DOWN absent");
        assertTrue(CHARACTERIZED.move(GridPosition.C7, Move.DOWN).isEmpty(), "C7 DOWN absent");
    }

    @Test
    void leftRightSwitchColumnsAndUpDownSwitchRows() {
        assertEquals(GridPosition.C1, CHARACTERIZED.move(GridPosition.C0, Move.RIGHT).orElseThrow());
        assertEquals(GridPosition.C0, CHARACTERIZED.move(GridPosition.C1, Move.LEFT).orElseThrow());
        assertEquals(GridPosition.C2, CHARACTERIZED.move(GridPosition.C0, Move.DOWN).orElseThrow());
        assertEquals(GridPosition.C0, CHARACTERIZED.move(GridPosition.C2, Move.UP).orElseThrow());
    }

    @Test
    void emptyPolicyBlocksEveryMove() {
        GridNavigationPolicy empty = ProvenGridNavigationPolicy.empty();
        assertFalse(empty.move(GridPosition.C0, Move.RIGHT).isPresent());
        assertFalse(empty.move(GridPosition.C5, Move.DOWN).isPresent());
    }

    @Test
    void builderRejectsNonAdjacentTransitions() {
        assertThrows(IllegalArgumentException.class,
                () -> ProvenGridNavigationPolicy.builder()
                        .add(GridPosition.C0, Move.RIGHT, GridPosition.C3));
        assertThrows(IllegalArgumentException.class,
                () -> ProvenGridNavigationPolicy.builder()
                        .add(GridPosition.C0, Move.UP, GridPosition.C0));
    }

    @Test
    void shortestPathsAreDeterministicAndMinimal() {
        // Two four-move routes link C0 to C7; declaration order (UP, DOWN, LEFT, RIGHT)
        // explores DOWN first, so the deterministic winner runs down column zero.
        assertEquals(List.of(Move.DOWN, Move.DOWN, Move.DOWN, Move.RIGHT),
                DryRunPlanner.shortestPath(CHARACTERIZED, GridPosition.C0, GridPosition.C7)
                        .orElseThrow(),
                "deterministic C0 to C7 path");
        GridPosition[] all = {GridPosition.C0, GridPosition.C1, GridPosition.C2, GridPosition.C3,
                GridPosition.C4, GridPosition.C5, GridPosition.C6, GridPosition.C7};
        for (GridPosition from : all) {
            for (GridPosition to : all) {
                List<Move> path = DryRunPlanner.shortestPath(CHARACTERIZED, from, to).orElseThrow();
                int manhattan = Math.abs(to.row() - from.row()) + Math.abs(to.column() - from.column());
                assertEquals(manhattan, path.size(),
                        "pairwise shortest path is truly minimal " + from + " to " + to);
                // Determinism: run twice, same answer object content.
                assertEquals(path,
                        DryRunPlanner.shortestPath(CHARACTERIZED, from, to).orElseThrow(),
                        "deterministic " + from + " to " + to);
            }
        }
    }

    @Test
    void unreachablePairsStayEmptyOnPartialGraphs() {
        GridNavigationPolicy single = ProvenGridNavigationPolicy.builder()
                .add(GridPosition.C0, Move.RIGHT, GridPosition.C1).build();
        assertTrue(DryRunPlanner.shortestPath(single, GridPosition.C0, GridPosition.C7).isEmpty(),
                "no proven route means no path, never a guess");
        assertEquals(List.of(),
                DryRunPlanner.shortestPath(single, GridPosition.C1, GridPosition.C1).orElseThrow(),
                "staying put costs nothing");
    }
}
