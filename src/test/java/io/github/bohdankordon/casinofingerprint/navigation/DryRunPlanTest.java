package io.github.bohdankordon.casinofingerprint.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Malformed READY construction is rejected; the BLOCKED contract holds with no pretend data. */
class DryRunPlanTest {
    private static RecognitionIdentity identity1456() {
        return RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 4, 5, 6));
    }

    private static List<DryRunAction> tour1456() {
        return List.of(
                new DryRunAction.Navigate(Move.RIGHT),
                new DryRunAction.Select(GridPosition.C1),
                new DryRunAction.Navigate(Move.DOWN),
                new DryRunAction.Select(GridPosition.C4),
                new DryRunAction.Navigate(Move.DOWN),
                new DryRunAction.Select(GridPosition.C5),
                new DryRunAction.Navigate(Move.LEFT),
                new DryRunAction.Select(GridPosition.C6),
                new DryRunAction.Proceed());
    }

    @Test
    void handBuiltConsistentPlanConstructs() {
        DryRunPlan plan = DryRunPlan.ready(identity1456(), GridPosition.C0,
                List.of(1, 4, 5, 6), tour1456(), 4);
        assertTrue(plan.executable());
        assertEquals(List.of(1, 4, 5, 6), plan.order());
        assertEquals(4, plan.navigationMoveCount());
        assertEquals(9, plan.actionCount());
        assertTrue(plan.reasons().isEmpty());
    }

    @Test
    void orderDifferingFromIdentitySetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DryRunPlan.ready(identity1456(),
                GridPosition.C0, List.of(0, 1, 2, 3), List.of(), 0));
    }

    @Test
    void duplicatedOrderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DryRunPlan.ready(identity1456(),
                GridPosition.C0, List.of(1, 4, 4, 6), List.of(), 0));
    }

    @Test
    void selectSequenceDifferingFromOrderIsRejected() {
        List<DryRunAction> actions = List.of(
                new DryRunAction.Select(GridPosition.C1),
                new DryRunAction.Select(GridPosition.C4),
                new DryRunAction.Select(GridPosition.C5),
                new DryRunAction.Select(GridPosition.C5),
                new DryRunAction.Proceed());
        assertThrows(IllegalArgumentException.class, () -> DryRunPlan.ready(identity1456(),
                GridPosition.C0, List.of(1, 4, 5, 6), actions, 0));
    }

    @Test
    void wrongNavigateCountMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DryRunPlan.ready(identity1456(),
                GridPosition.C0, List.of(1, 4, 5, 6), tour1456(), 5));
    }

    @Test
    void negativeNavigateCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DryRunPlan.ready(identity1456(),
                GridPosition.C0, List.of(1, 4, 5, 6), tour1456(), -1));
    }

    @Test
    void multipleProceedIsRejected() {
        List<DryRunAction> actions = new java.util.ArrayList<>(tour1456());
        actions.add(new DryRunAction.Proceed());
        assertThrows(IllegalArgumentException.class, () -> DryRunPlan.ready(identity1456(),
                GridPosition.C0, List.of(1, 4, 5, 6), actions, 4));
    }

    @Test
    void proceedNotLastIsRejected() {
        List<DryRunAction> actions = new java.util.ArrayList<>(tour1456());
        actions.add(new DryRunAction.Navigate(Move.DOWN));
        assertThrows(IllegalArgumentException.class, () -> DryRunPlan.ready(identity1456(),
                GridPosition.C0, List.of(1, 4, 5, 6), actions, 5));
    }

    @Test
    void blockedPlansCarryReasonsButNoPretendSequence() {
        assertThrows(IllegalArgumentException.class,
                () -> DryRunPlan.blocked(identity1456(), List.of()));
        DryRunPlan blocked = DryRunPlan.blocked(identity1456(), List.of("missing start"));
        assertFalse(blocked.executable());
        assertTrue(blocked.actions().isEmpty(), "no pretend executable action sequence");
        assertTrue(blocked.order().isEmpty(), "no pretend order");
        assertEquals(0, blocked.navigationMoveCount());
        assertEquals(0, blocked.actionCount());
        assertEquals(List.of("missing start"), blocked.reasons());
    }
}
