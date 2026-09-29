package io.github.bohdankordon.casinofingerprint.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlanner;
import io.github.bohdankordon.casinofingerprint.navigation.ProvenGridNavigationPolicy;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Consumption-gate checks: {@code consumeReadyRound} is reachable only after the independent
 * {@code PlanValidator} reports no violations. The gate helper is package-private so the
 * production fact is pinned without test-only dependency injection; the architecture is
 * unchanged.
 */
class DryRunValidationGateTest {
    private static DryRunPlan validPlan() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6));
        return DryRunPlanner.plan(identity, NavigationContext.characterized());
    }

    @Test
    void cleanPlansPassThroughUntouched() {
        DryRunPlan plan = validPlan();
        assertSame(plan, DryRunSolveOrchestrator.requireValid(plan,
                ProvenGridNavigationPolicy.characterized()),
                "valid plans reach consumption");
    }

    @Test
    void policyMismatchConvertsToBlockedWithReasons() {
        DryRunPlan plan = validPlan();
        DryRunPlan checked = DryRunSolveOrchestrator.requireValid(plan,
                ProvenGridNavigationPolicy.empty());
        assertFalse(checked.executable(), "invalid plans never reach consumption");
        assertEquals(plan.identity(), checked.identity(), "identity is preserved");
        assertTrue(checked.actions().isEmpty(), "no pretend executable action sequence");
        assertFalse(checked.reasons().isEmpty(), "exact reasons are exposed");
        assertTrue(checked.reasons().get(0).contains("planner validation failed"),
                "clear failure marker: " + checked.reasons());
    }

    @Test
    void alreadyBlockedPlansPassThrough() {
        DryRunPlan blocked = DryRunPlan.blocked(validPlan().identity(), List.of("missing start"));
        assertSame(blocked, DryRunSolveOrchestrator.requireValid(blocked,
                ProvenGridNavigationPolicy.characterized()));
    }

    @Test
    void gateRejectsNulls() {
        DryRunPlan plan = validPlan();
        assertThrows(NullPointerException.class, () -> DryRunSolveOrchestrator.requireValid(null,
                ProvenGridNavigationPolicy.characterized()));
        assertThrows(NullPointerException.class,
                () -> DryRunSolveOrchestrator.requireValid(plan, null));
    }
}
