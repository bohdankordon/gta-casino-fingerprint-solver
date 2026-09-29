package io.github.bohdankordon.casinofingerprint.navigation;

import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure dry-run solve planner: turns one validated ROUND_READY identity plus a navigation
 * context into an abstract action sequence, or into a fail-closed BLOCKED plan.
 *
 * <p>The planner never decides whether a round exists: it receives an already validated
 * {@link RecognitionIdentity} (exactly four distinct candidates 0..7, enforced by that type)
 * and only answers HOW the selector would visit them. Shortest selector paths come from
 * breadth-first search over the proven navigation graph, so every planned move was witnessed
 * in the real recordings; the selection order is the cheapest of all 4! = 24 candidate
 * permutations by navigation-move count, ties broken lexicographically by candidate-index
 * sequence. Timing plays no role: SELECT actions are never optimized away and no duration
 * enters the cost.
 *
 * <p>Fail-closed contract: an unknown selector start or any unreachable leg produces a BLOCKED
 * plan naming the exact missing precondition instead of guessing a move.
 */
public final class DryRunPlanner {
    private DryRunPlanner() {
    }

    /**
     * Plans the abstract dry-run sequence for one validated round identity.
     *
     * @param identity validated ROUND_READY identity; required
     * @param context selector start plus proven navigation graph; required
     * @return an executable READY plan, or a BLOCKED plan naming what is missing
     */
    public static DryRunPlan plan(RecognitionIdentity identity, NavigationContext context) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(context, "context");
        List<Integer> targets = identity.candidates();
        Optional<GridPosition> start = context.start();
        if (start.isEmpty()) {
            return DryRunPlan.blocked(identity, List.of("unknown selector start position: "
                    + "no validated round-start focus is available, so no first move can be planned"));
        }
        GridNavigationPolicy policy = context.policy();
        GridPosition origin = start.get();
        List<GridPosition> goals = new ArrayList<>();
        for (int candidate : targets) {
            goals.add(GridPosition.of(candidate));
        }
        List<String> missing = unreachableLegs(origin, goals, policy);
        if (!missing.isEmpty()) {
            return DryRunPlan.blocked(identity, missing);
        }
        List<GridPosition> best = bestOrder(origin, goals, policy);
        List<DryRunAction> actions = new ArrayList<>();
        GridPosition cursor = origin;
        int moves = 0;
        List<Integer> order = new ArrayList<>();
        for (GridPosition goal : best) {
            List<Move> path = shortestPath(policy, cursor, goal).orElseThrow();
            for (Move step : path) {
                actions.add(new DryRunAction.Navigate(step));
                moves++;
            }
            actions.add(new DryRunAction.Select(goal));
            order.add(goal.index());
            cursor = goal;
        }
        actions.add(new DryRunAction.Proceed());
        return DryRunPlan.ready(identity, origin, order, actions, moves);
    }

    /** Every start/candidate leg that the proven graph cannot route, in stable order. */
    static List<String> unreachableLegs(GridPosition origin, List<GridPosition> goals,
            GridNavigationPolicy policy) {
        List<String> missing = new ArrayList<>();
        List<GridPosition> stops = new ArrayList<>();
        stops.add(origin);
        stops.addAll(goals);
        for (int from = 0; from < stops.size(); from++) {
            for (int to = 0; to < stops.size(); to++) {
                if (from != to && shortestPath(policy, stops.get(from), stops.get(to)).isEmpty()) {
                    missing.add("no proven navigation from " + stops.get(from) + " to " + stops.get(to));
                }
            }
        }
        return missing;
    }

    /** Cheapest visit order of all 24 permutations; ties break lexicographically. */
    static List<GridPosition> bestOrder(GridPosition origin, List<GridPosition> goals,
            GridNavigationPolicy policy) {
        List<GridPosition> sorted = new ArrayList<>(goals);
        sorted.sort(null);
        List<GridPosition> best = null;
        int bestCost = Integer.MAX_VALUE;
        List<GridPosition> permutation = new ArrayList<>(sorted);
        do {
            int cost = tourCost(origin, permutation, policy);
            if (cost < bestCost) {
                bestCost = cost;
                best = new ArrayList<>(permutation);
            }
        } while (nextPermutation(permutation));
        return best;
    }

    /** Navigation-move cost of one visit order; every leg is reachable here. */
    static int tourCost(GridPosition origin, List<GridPosition> order, GridNavigationPolicy policy) {
        int cost = 0;
        GridPosition cursor = origin;
        for (GridPosition goal : order) {
            cost += shortestPath(policy, cursor, goal).orElseThrow().size();
            cursor = goal;
        }
        return cost;
    }

    /**
     * Deterministic shortest move sequence between two positions over the proven graph.
     *
     * <p>Breadth-first search on a 2x4 grid: tiny, robust to any future edge semantics, and
     * deterministic because neighbours expand in {@link Move} declaration order (UP, DOWN,
     * LEFT, RIGHT) with FIFO queue discipline.
     *
     * @return the move list, or empty when no proven route exists
     */
    public static Optional<List<Move>> shortestPath(GridNavigationPolicy policy,
            GridPosition from, GridPosition to) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) {
            return Optional.of(List.of());
        }
        Map<GridPosition, GridPosition> parent = new HashMap<>();
        Map<GridPosition, Move> via = new HashMap<>();
        Deque<GridPosition> queue = new ArrayDeque<>();
        parent.put(from, from);
        queue.add(from);
        while (!queue.isEmpty()) {
            GridPosition cursor = queue.removeFirst();
            for (Move step : Move.values()) {
                Optional<GridPosition> next = policy.move(cursor, step);
                if (next.isEmpty() || parent.containsKey(next.get())) {
                    continue;
                }
                parent.put(next.get(), cursor);
                via.put(next.get(), step);
                if (next.get().equals(to)) {
                    return Optional.of(reconstruct(via, parent, from, to));
                }
                queue.addLast(next.get());
            }
        }
        return Optional.empty();
    }

    private static List<Move> reconstruct(Map<GridPosition, Move> via,
            Map<GridPosition, GridPosition> parent, GridPosition from, GridPosition to) {
        List<Move> reversed = new ArrayList<>();
        GridPosition cursor = to;
        while (!cursor.equals(from)) {
            reversed.add(via.get(cursor));
            cursor = parent.get(cursor);
        }
        List<Move> path = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return List.copyOf(path);
    }

    /** In-place lexicographic next permutation; false when the last one was reached. */
    static boolean nextPermutation(List<GridPosition> order) {
        int pivot = order.size() - 2;
        while (pivot >= 0 && order.get(pivot).compareTo(order.get(pivot + 1)) >= 0) {
            pivot--;
        }
        if (pivot < 0) {
            return false;
        }
        int swap = order.size() - 1;
        while (order.get(pivot).compareTo(order.get(swap)) >= 0) {
            swap--;
        }
        GridPosition held = order.get(pivot);
        order.set(pivot, order.get(swap));
        order.set(swap, held);
        int left = pivot + 1;
        int right = order.size() - 1;
        while (left < right) {
            GridPosition other = order.get(left);
            order.set(left, order.get(right));
            order.set(right, other);
            left++;
            right--;
        }
        return true;
    }
}
