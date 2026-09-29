package io.github.bohdankordon.casinofingerprint.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic Stage 7A validator checks: valid plans pass, each failure class is rejected. */
class PlanValidatorTest {
    private static final ProvenGridNavigationPolicy GRAPH = ProvenGridNavigationPolicy.characterized();

    private static DryRunPlan realPlan() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6));
        return DryRunPlanner.plan(identity, NavigationContext.characterized());
    }

    @Test
    void plannerOutputValidatesCleanly() {
        DryRunPlan plan = realPlan();
        assertTrue(PlanValidator.isValid(plan.start(), plan.identity().candidates(),
                plan.actions(), GRAPH),
                "planner output must validate: " + PlanValidator.validate(plan.start(),
                        plan.identity().candidates(), plan.actions(), GRAPH));
    }

    @Test
    void illegalMoveIsRejected() {
        List<DryRunAction> actions = List.of(
                new DryRunAction.Navigate(Move.UP),
                new DryRunAction.Select(GridPosition.C0),
                new DryRunAction.Select(GridPosition.C1),
                new DryRunAction.Select(GridPosition.C2),
                new DryRunAction.Select(GridPosition.C3),
                new DryRunAction.Proceed());
        List<String> violations = PlanValidator.validate(
                GridPosition.C0, List.of(0, 1, 2, 3), actions, GRAPH);
        assertTrue(violations.stream().anyMatch(v -> v.contains("illegal move")),
                "UP from C0 leaves the grid: " + violations);
    }

    @Test
    void selectingTheUnfocusedCandidateIsRejected() {
        List<DryRunAction> actions = List.of(
                new DryRunAction.Select(GridPosition.C1),
                new DryRunAction.Select(GridPosition.C0),
                new DryRunAction.Select(GridPosition.C2),
                new DryRunAction.Select(GridPosition.C3),
                new DryRunAction.Proceed());
        List<String> violations = PlanValidator.validate(
                GridPosition.C0, List.of(0, 1, 2, 3), actions, GRAPH);
        assertTrue(violations.stream().anyMatch(v -> v.contains("unfocused")),
                "first SELECT names C1 while focused on C0: " + violations);
    }

    @Test
    void duplicateSelectionIsRejected() {
        List<DryRunAction> actions = List.of(
                new DryRunAction.Select(GridPosition.C0),
                new DryRunAction.Navigate(Move.RIGHT),
                new DryRunAction.Select(GridPosition.C1),
                new DryRunAction.Navigate(Move.LEFT),
                new DryRunAction.Select(GridPosition.C0),
                new DryRunAction.Select(GridPosition.C2),
                new DryRunAction.Proceed());
        List<String> violations = PlanValidator.validate(
                GridPosition.C0, List.of(0, 1, 2, 0), actions, GRAPH);
        assertTrue(violations.stream().anyMatch(v -> v.contains("duplicate")),
                "C0 selected twice: " + violations);
    }

    @Test
    void wrongSelectedSetIsRejected() {
        DryRunPlan plan = realPlan();
        List<String> violations = PlanValidator.validate(
                plan.start(), List.of(0, 1, 2, 3), plan.actions(), GRAPH);
        assertTrue(violations.stream().anyMatch(v -> v.contains("differs from expected")),
                "plan selects 1,4,5,6 instead of 0,1,2,3: " + violations);
    }

    @Test
    void earlyProceedIsRejected() {
        List<DryRunAction> actions = List.of(
                new DryRunAction.Select(GridPosition.C0),
                new DryRunAction.Navigate(Move.RIGHT),
                new DryRunAction.Select(GridPosition.C1),
                new DryRunAction.Proceed(),
                new DryRunAction.Navigate(Move.DOWN),
                new DryRunAction.Select(GridPosition.C3),
                new DryRunAction.Select(GridPosition.C2));
        List<String> violations = PlanValidator.validate(
                GridPosition.C0, List.of(0, 1, 2, 3), actions, GRAPH);
        assertTrue(violations.stream().anyMatch(v -> v.contains("early PROCEED")),
                "PROCEED after two selections: " + violations);
    }

    @Test
    void actionAfterProceedIsRejected() {
        DryRunPlan plan = realPlan();
        List<DryRunAction> extended = new java.util.ArrayList<>(plan.actions());
        extended.add(new DryRunAction.Navigate(Move.DOWN));
        List<String> violations = PlanValidator.validate(
                plan.start(), plan.identity().candidates(), extended, GRAPH);
        assertTrue(violations.stream().anyMatch(v -> v.contains("after PROCEED")),
                "trailing navigation: " + violations);
    }

    @Test
    void missingProceedIsRejected() {
        DryRunPlan plan = realPlan();
        List<DryRunAction> truncated =
                plan.actions().subList(0, plan.actions().size() - 1);
        List<String> violations = PlanValidator.validate(
                plan.start(), plan.identity().candidates(), truncated, GRAPH);
        assertTrue(violations.stream().anyMatch(v -> v.contains("missing final PROCEED")),
                "dropped PROCEED: " + violations);
    }

    @Test
    void violationCountIsReported() {
        assertEquals(0, PlanValidator.validate(GridPosition.C0, List.of(0, 1, 6, 7),
                List.of(new DryRunAction.Select(GridPosition.C0),
                        new DryRunAction.Navigate(Move.DOWN),
                        new DryRunAction.Navigate(Move.DOWN),
                        new DryRunAction.Navigate(Move.DOWN),
                        new DryRunAction.Select(GridPosition.C6),
                        new DryRunAction.Navigate(Move.RIGHT),
                        new DryRunAction.Select(GridPosition.C7),
                        new DryRunAction.Navigate(Move.UP),
                        new DryRunAction.Navigate(Move.UP),
                        new DryRunAction.Navigate(Move.UP),
                        new DryRunAction.Select(GridPosition.C1),
                        new DryRunAction.Proceed()),
                GRAPH).size(),
                "hand-built C0,C6,C7,C1 tour validates");
    }

    @Test
    void planLevelValidationAcceptsPlannerOutput() {
        DryRunPlan plan = realPlan();
        assertTrue(PlanValidator.isValid(plan, GRAPH),
                "whole valid plans validate: " + PlanValidator.validate(plan, GRAPH));
    }

    @Test
    void planLevelValidationRejectsMovesOutsideTheGraph() {
        DryRunPlan plan = realPlan();
        List<String> violations =
                PlanValidator.validate(plan, ProvenGridNavigationPolicy.empty());
        assertTrue(violations.stream().anyMatch(v -> v.contains("illegal move")),
                "every Navigate is unproven against an empty graph: " + violations);
    }

    @Test
    void planLevelValidationRejectsBlockedPlans() {
        DryRunPlan blocked = DryRunPlan.blocked(
                RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 4, 5, 6)),
                List.of("unknown selector start position"));
        List<String> violations = PlanValidator.validate(blocked, GRAPH);
        assertTrue(violations.stream().anyMatch(v -> v.contains("BLOCKED")),
                "blocked plans have nothing executable to check: " + violations);
    }
}
