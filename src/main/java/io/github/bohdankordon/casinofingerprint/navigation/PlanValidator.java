package io.github.bohdankordon.casinofingerprint.navigation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Independent dry-run plan checker for tests and evaluation: replays an abstract action
 * sequence against the proven navigation graph and the expected candidate set.
 *
 * <p>Proves, for a READY plan: every Navigate transition is legal in the graph; every Select
 * confirms the currently focused candidate; no candidate is selected twice; the selected set
 * is exactly the expected four; PROCEED occurs only after all four selections, exactly once,
 * and last; and nothing follows PROCEED. Pure Java, no Mats, no timers, no input.
 */
public final class PlanValidator {
    private PlanValidator() {
    }

    /**
     * Validates one abstract action sequence.
     *
     * @param start selector position before the first action; required
     * @param expectedCandidates the four expected candidate indices, any order; required
     * @param actions abstract sequence to check; required
     * @param policy proven navigation graph the Navigate steps must follow; required
     * @return violations, empty when the plan is valid
     */
    public static List<String> validate(GridPosition start, List<Integer> expectedCandidates,
            List<DryRunAction> actions, GridNavigationPolicy policy) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(expectedCandidates, "expectedCandidates");
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(policy, "policy");
        List<String> violations = new ArrayList<>();
        Set<Integer> expected = new TreeSet<>(expectedCandidates);
        if (expected.size() != 4) {
            violations.add("expected set must hold exactly four distinct candidates 0..7");
        }
        GridPosition cursor = start;
        Set<Integer> selected = new HashSet<>();
        boolean proceeded = false;
        int proceeds = 0;
        for (DryRunAction action : actions) {
            if (action instanceof DryRunAction.Navigate navigate) {
                if (proceeded) {
                    violations.add("action after PROCEED: " + navigate.render());
                    continue;
                }
                Optional<GridPosition> next = policy.move(cursor, navigate.move());
                if (next.isEmpty()) {
                    violations.add("illegal move " + navigate.move() + " from " + cursor);
                } else {
                    cursor = next.get();
                }
            } else if (action instanceof DryRunAction.Select select) {
                if (proceeded) {
                    violations.add("action after PROCEED: " + select.render());
                    continue;
                }
                if (select.candidate().index() != cursor.index()) {
                    violations.add("selecting unfocused " + select.candidate()
                            + " while focused on " + cursor);
                }
                if (!selected.add(select.candidate().index())) {
                    violations.add("duplicate selection of " + select.candidate());
                }
            } else if (action instanceof DryRunAction.Proceed) {
                proceeds++;
                if (proceeded) {
                    violations.add("duplicate PROCEED");
                }
                proceeded = true;
                if (selected.size() != 4) {
                    violations.add("early PROCEED after " + selected.size() + " of 4 selections");
                }
            }
        }
        if (proceeds == 0) {
            violations.add("missing final PROCEED");
        }
        if (!selected.equals(expected)) {
            violations.add("selected set " + new TreeSet<>(selected)
                    + " differs from expected " + expected);
        }
        return List.copyOf(violations);
    }

    /** True when {@link #validate} reports no violation. */
    public static boolean isValid(GridPosition start, List<Integer> expectedCandidates,
            List<DryRunAction> actions, GridNavigationPolicy policy) {
        return validate(start, expectedCandidates, actions, policy).isEmpty();
    }

    /**
     * Validates a whole dry-run plan as one unit: its READY metadata plus its action
     * sequence against the proven navigation graph.
     *
     * <p>For a READY plan this proves, at minimum: a start is present; the order holds
     * exactly four distinct candidate indices whose set equals the identity candidates; exactly
     * four SELECTs whose candidate sequence equals the order; every SELECT targets the currently
     * focused candidate; every Navigate transition exists in the policy; no duplicate selection;
     * the selected set exactly equals the identity candidates; exactly one PROCEED, only after
     * all four selections, last with nothing after it; the Navigate count equals
     * {@code navigationMoveCount} (which must be non-negative); and {@code actionCount} equals
     * the action list size. A BLOCKED plan has nothing executable to check and reports one
     * violation naming that fact.
     *
     * @param plan dry-run plan to check; required
     * @param policy proven navigation graph the Navigate steps must follow; required
     * @return violations, empty when the whole plan is valid
     */
    public static List<String> validate(DryRunPlan plan, GridNavigationPolicy policy) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(policy, "policy");
        List<String> violations = new ArrayList<>();
        if (!plan.executable()) {
            violations.add("plan is BLOCKED with no executable sequence: "
                    + String.join("; ", plan.reasons()));
            return List.copyOf(violations);
        }
        if (plan.start() == null) {
            violations.add("READY plan has no start position");
        }
        List<Integer> order = plan.order();
        Set<Integer> orderSet = new HashSet<>(order);
        if (order.size() != 4 || orderSet.size() != 4) {
            violations.add("READY plan order must hold exactly four distinct candidates, got " + order);
        }
        for (Integer candidate : order) {
            if (candidate == null || candidate < 0 || candidate > 7) {
                violations.add("READY plan order holds an out-of-range candidate: " + candidate);
            }
        }
        Set<Integer> identitySet = new HashSet<>(plan.identity().candidates());
        if (!orderSet.equals(identitySet)) {
            violations.add("READY plan order " + order + " differs from identity "
                    + plan.identity().code());
        }
        List<Integer> selected = new ArrayList<>();
        int navigates = 0;
        int proceeds = 0;
        for (DryRunAction action : plan.actions()) {
            if (action instanceof DryRunAction.Navigate) {
                navigates++;
            } else if (action instanceof DryRunAction.Select select) {
                selected.add(select.candidate().index());
            } else if (action instanceof DryRunAction.Proceed) {
                proceeds++;
            }
        }
        if (selected.size() != 4) {
            violations.add("READY plan holds " + selected.size() + " SELECT actions instead of four");
        }
        if (!selected.equals(order)) {
            violations.add("READY plan SELECT sequence " + selected + " differs from order " + order);
        }
        if (plan.navigationMoveCount() < 0) {
            violations.add("READY plan navigation move count is negative: "
                    + plan.navigationMoveCount());
        }
        if (navigates != plan.navigationMoveCount()) {
            violations.add("READY plan navigates " + navigates + " times but reports "
                    + plan.navigationMoveCount() + " moves");
        }
        if (plan.actionCount() != plan.actions().size()) {
            violations.add("READY plan action count metadata is inconsistent");
        }
        if (proceeds != 1) {
            violations.add("READY plan holds " + proceeds + " PROCEED markers instead of exactly one");
        }
        if (plan.start() != null) {
            violations.addAll(validate(plan.start(), plan.identity().candidates(),
                    plan.actions(), policy));
        }
        return List.copyOf(violations);
    }

    /** True when {@link #validate(DryRunPlan, GridNavigationPolicy)} reports no violation. */
    public static boolean isValid(DryRunPlan plan, GridNavigationPolicy policy) {
        return validate(plan, policy).isEmpty();
    }
}
