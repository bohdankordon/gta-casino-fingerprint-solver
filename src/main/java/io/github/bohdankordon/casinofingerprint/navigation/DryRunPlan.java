package io.github.bohdankordon.casinofingerprint.navigation;

import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable dry-run outcome for one validated ROUND_READY identity: either an executable
 * abstract action sequence (READY) or a fail-closed refusal (BLOCKED).
 *
 * <p>A READY plan exposes the recognized identity, the starting selector position, the chosen
 * selection order, the abstract actions (navigation moves, one SELECT per correct candidate,
 * one final PROCEED) and the navigation move count. A BLOCKED plan exposes the identity plus
 * the exact missing navigation preconditions and NO executable action sequence: its action
 * list is always empty, so a blocked plan can never be mistaken for something to execute.
 *
 * <p>SELECT and PROCEED are domain intentions only. This type holds no key codes, no native
 * resources and no Mats.
 */
public final class DryRunPlan {
    /** Plan outcome: executable abstract sequence or fail-closed refusal. */
    public enum Status {
        /** The plan is fully executable under the proven navigation graph. */
        READY,
        /** A navigation precondition is unknown; there is nothing to execute. */
        BLOCKED
    }

    private final Status status;
    private final RecognitionIdentity identity;
    private final GridPosition start;
    private final List<Integer> order;
    private final List<DryRunAction> actions;
    private final int navigationMoveCount;
    private final List<String> reasons;

    private DryRunPlan(Status status, RecognitionIdentity identity, GridPosition start,
            List<Integer> order, List<DryRunAction> actions, int navigationMoveCount,
            List<String> reasons) {
        this.status = status;
        this.identity = identity;
        this.start = start;
        this.order = order;
        this.actions = actions;
        this.navigationMoveCount = navigationMoveCount;
        this.reasons = reasons;
    }

    /**
     * Builds an executable plan. The caller (the planner) guarantees: exactly four ordered
     * selections matching the identity set, one SELECT each, proven navigation between them,
     * one final PROCEED, and a move count equal to the number of Navigate actions.
     */
    public static DryRunPlan ready(RecognitionIdentity identity, GridPosition start,
            List<Integer> order, List<DryRunAction> actions, int navigationMoveCount) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(actions, "actions");
        if (navigationMoveCount < 0) {
            throw new IllegalArgumentException(
                    "A ready plan navigation move count must be non-negative, got "
                            + navigationMoveCount);
        }
        if (order.size() != 4 || new HashSet<>(order).size() != 4) {
            throw new IllegalArgumentException(
                    "A ready plan order holds exactly four distinct candidates, got " + order);
        }
        Set<Integer> identitySet = new HashSet<>(identity.candidates());
        if (!new HashSet<>(order).equals(identitySet)) {
            throw new IllegalArgumentException("A ready plan order " + order
                    + " must equal the identity candidates " + identity.code());
        }
        List<Integer> selected = new ArrayList<>();
        int navigates = 0;
        int proceeds = 0;
        for (DryRunAction action : actions) {
            if (action instanceof DryRunAction.Navigate) {
                navigates++;
            } else if (action instanceof DryRunAction.Select select) {
                selected.add(select.candidate().index());
            } else if (action instanceof DryRunAction.Proceed) {
                proceeds++;
            }
        }
        if (!selected.equals(order)) {
            throw new IllegalArgumentException("A ready plan SELECT sequence " + selected
                    + " must equal the order " + order);
        }
        if (navigates != navigationMoveCount) {
            throw new IllegalArgumentException("A ready plan navigates " + navigates
                    + " times but reports " + navigationMoveCount + " moves");
        }
        if (proceeds != 1 || actions.isEmpty()
                || !(actions.get(actions.size() - 1) instanceof DryRunAction.Proceed)) {
            throw new IllegalArgumentException(
                    "A ready plan holds exactly one PROCEED marker, last and alone at the end");
        }
        return new DryRunPlan(Status.READY, identity, start, List.copyOf(order),
                List.copyOf(actions), navigationMoveCount, List.of());
    }

    /** Builds a fail-closed refusal with the exact missing preconditions. */
    public static DryRunPlan blocked(RecognitionIdentity identity, List<String> reasons) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(reasons, "reasons");
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("A blocked plan must name its missing preconditions");
        }
        return new DryRunPlan(Status.BLOCKED, identity, null, List.of(), List.of(), 0,
                List.copyOf(reasons));
    }

    /** READY when the abstract sequence is fully executable, BLOCKED otherwise. */
    public Status status() {
        return status;
    }

    /** True when the abstract sequence may be reported as the dry-run solution path. */
    public boolean executable() {
        return status == Status.READY;
    }

    /** Validated round identity this plan was built for. */
    public RecognitionIdentity identity() {
        return identity;
    }

    /** Starting selector position; null when blocked on an unknown start. */
    public GridPosition start() {
        return start;
    }

    /** Chosen candidate-selection order; empty when blocked. */
    public List<Integer> order() {
        return order;
    }

    /** Abstract actions; always empty when blocked: no pretend sequence. */
    public List<DryRunAction> actions() {
        return actions;
    }

    /** Number of Navigate actions; zero when blocked. */
    public int navigationMoveCount() {
        return navigationMoveCount;
    }

    /** Total abstract actions (navigation plus four SELECT plus one PROCEED); zero when blocked. */
    public int actionCount() {
        return actions.size();
    }

    /** Missing preconditions; empty when ready. */
    public List<String> reasons() {
        return reasons;
    }

    /** One-line plan rendering used by the dry-run CLI and by replay artifacts. */
    public String describe() {
        if (!executable()) {
            return "BLOCKED " + identity.code() + " (" + String.join("; ", reasons) + ")";
        }
        List<String> rendered = new ArrayList<>();
        rendered.add("START " + start);
        for (DryRunAction action : actions) {
            rendered.add(action.render());
        }
        return String.join(" ", rendered);
    }

    /** CLI-facing summary line. */
    public String summary() {
        if (!executable()) {
            return String.format(Locale.ROOT, "DRY_RUN %s blocked: %s", identity.code(),
                    String.join("; ", reasons));
        }
        return String.format(Locale.ROOT,
                "DRY_RUN ROUND %s start=%s order=%s navigationMoves=%d actionCount=%d",
                identity.code(), start, order, navigationMoveCount, actions.size());
    }
}
