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
}
