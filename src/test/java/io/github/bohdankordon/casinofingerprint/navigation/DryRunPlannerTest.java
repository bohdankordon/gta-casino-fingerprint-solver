package io.github.bohdankordon.casinofingerprint.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Synthetic Stage 7A planner checks: exact optimum over all 24 orders, deterministic
 * tie-breaks, preserved selection sets, fail-closed blocked plans and rejected inputs.
 */
class DryRunPlannerTest {
    private static final NavigationContext CHARACTERIZED = NavigationContext.characterized();

    private static RecognitionIdentity identity(FingerprintId fingerprint, Integer... candidates) {
        return RecognitionIdentity.of(fingerprint, List.of(candidates));
    }

    /** Independent oracle: Manhattan clamp distance, no planner code involved. */
    private static int manhattan(GridPosition from, GridPosition to) {
        return Math.abs(to.row() - from.row()) + Math.abs(to.column() - from.column());
    }

    /** Minimum tour cost over all 24 permutations using only the Manhattan oracle. */
    private static int oracleMinimum(GridPosition start, List<Integer> targets) {
        List<List<Integer>> permutations = new ArrayList<>();
        permute(new ArrayList<>(targets), 0, permutations);
        int best = Integer.MAX_VALUE;
        for (List<Integer> order : permutations) {
            int cost = 0;
            GridPosition cursor = start;
            for (int candidate : order) {
                GridPosition goal = GridPosition.of(candidate);
                cost += manhattan(cursor, goal);
                cursor = goal;
            }
            best = Math.min(best, cost);
        }
        return best;
    }

    private static void permute(List<Integer> order, int fixed, List<List<Integer>> output) {
        if (fixed == order.size()) {
            output.add(new ArrayList<>(order));
            return;
        }
        for (int index = fixed; index < order.size(); index++) {
            Collections.swap(order, fixed, index);
            permute(order, fixed + 1, output);
            Collections.swap(order, fixed, index);
        }
    }

    @Test
    void plannerReachesTheIndependentOptimumOnManyInputs() {
        int[][] targetSets = {
                {1, 4, 5, 6}, {0, 1, 3, 6}, {0, 4, 6, 7}, {1, 3, 6, 7},
                {1, 2, 6, 7}, {0, 3, 6, 7}, {2, 4, 6, 7}, {0, 1, 2, 3}
        };
        GridPosition[] starts = {GridPosition.C0, GridPosition.C1, GridPosition.C5, GridPosition.C7};
        for (GridPosition start : starts) {
            for (int[] targets : targetSets) {
                List<Integer> sorted = new ArrayList<>();
                for (int candidate : targets) {
                    sorted.add(candidate);
                }
                Collections.sort(sorted);
                RecognitionIdentity identity = identity(FingerprintId.FP_3,
                        sorted.toArray(new Integer[0]));
                NavigationContext context = new NavigationContext(
                        java.util.Optional.of(start), ProvenGridNavigationPolicy.characterized());
                DryRunPlan plan = DryRunPlanner.plan(identity, context);
                assertTrue(plan.executable(), "executable " + start + " " + sorted);
                assertEquals(oracleMinimum(start, sorted), plan.navigationMoveCount(),
                        "globally move-optimal " + start + " " + sorted);
            }
        }
    }

    @Test
    void symmetricTiesBreakLexicographically() {
        // Orders [0,1,3,2] and [0,2,3,1] both cost three moves from C0 and beat every
        // other permutation; the lexicographically smaller one must win deterministically.
        DryRunPlan plan = DryRunPlanner.plan(identity(FingerprintId.FP_1, 0, 1, 2, 3), CHARACTERIZED);
        assertTrue(plan.executable());
        assertEquals(3, plan.navigationMoveCount(), "tied optimum costs three moves");
        assertEquals(List.of(0, 1, 3, 2), plan.order(),
                "lexicographically smallest optimal order");
    }

    @Test
    void selectedSetIsPreservedExactlyWithOneSelectEach() {
        DryRunPlan plan =
                DryRunPlanner.plan(identity(FingerprintId.FP_4, 1, 4, 5, 6), CHARACTERIZED);
        assertTrue(plan.executable());
        List<Integer> selected = new ArrayList<>();
        int selects = 0;
        for (DryRunAction action : plan.actions()) {
            if (action instanceof DryRunAction.Select select) {
                selects++;
                selected.add(select.candidate().index());
            }
        }
        assertEquals(4, selects, "SELECT actions are never optimized away");
        List<Integer> sorted = new ArrayList<>(selected);
        Collections.sort(sorted);
        assertEquals(List.of(1, 4, 5, 6), sorted, "selected set preserved exactly");
        assertEquals(plan.order(), selected, "order matches SELECT sequence");
        assertTrue(plan.actions().get(plan.actions().size() - 1) instanceof DryRunAction.Proceed,
                "PROCEED only after all four selections, and last");
        assertEquals(plan.navigationMoveCount() + 5, plan.actionCount(),
                "navigation plus four SELECT plus one PROCEED");
    }

    @Test
    void startingOnATargetNeedsNoMoveForIt() {
        NavigationContext context = new NavigationContext(java.util.Optional.of(GridPosition.C1),
                ProvenGridNavigationPolicy.characterized());
        DryRunPlan plan = DryRunPlanner.plan(identity(FingerprintId.FP_1, 0, 1, 6, 7), context);
        assertTrue(plan.executable());
        assertEquals(1, (int) plan.order().get(0), "nearest target first");
        assertTrue(plan.actions().get(0) instanceof DryRunAction.Select,
                "no navigation before selecting the starting tile");
    }

    @Test
    void unknownStartProducesABlockedPlanWithReasons() {
        DryRunPlan plan = DryRunPlanner.plan(identity(FingerprintId.FP_3, 1, 4, 5, 6),
                NavigationContext.unknown());
        assertFalse(plan.executable(), "unknown navigation context never executes");
        assertEquals(DryRunPlan.Status.BLOCKED, plan.status());
        assertFalse(plan.reasons().isEmpty(), "blocked names the missing precondition");
        assertTrue(plan.actions().isEmpty(), "no pretend executable action sequence");
        assertEquals(0, plan.navigationMoveCount());
        assertEquals(0, plan.actionCount());
    }

    @Test
    void unreachableLegsProduceABlockedPlan() {
        GridNavigationPolicy single = ProvenGridNavigationPolicy.builder()
                .add(GridPosition.C0, Move.RIGHT, GridPosition.C1).build();
        NavigationContext context =
                new NavigationContext(java.util.Optional.of(GridPosition.C0), single);
        DryRunPlan plan = DryRunPlanner.plan(identity(FingerprintId.FP_3, 1, 4, 5, 6), context);
        assertFalse(plan.executable(), "unproven legs block the plan");
        assertFalse(plan.reasons().isEmpty());
        assertTrue(plan.actions().isEmpty());
    }

    @Test
    void malformedInputsAreRejected() {
        RecognitionIdentity identity = identity(FingerprintId.FP_3, 1, 4, 5, 6);
        assertThrows(NullPointerException.class, () -> DryRunPlanner.plan(null, CHARACTERIZED));
        assertThrows(NullPointerException.class, () -> DryRunPlanner.plan(identity, null));
        assertThrows(IllegalArgumentException.class,
                () -> RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 4, 5)));
        assertThrows(IllegalArgumentException.class,
                () -> RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 4, 4, 6)));
        assertThrows(IllegalArgumentException.class,
                () -> RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 4, 5, 8)));
        assertThrows(IllegalArgumentException.class, () -> GridPosition.of(9));
    }
}
