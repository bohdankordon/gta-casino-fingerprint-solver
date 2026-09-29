package io.github.bohdankordon.casinofingerprint.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunAction;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlanner;
import io.github.bohdankordon.casinofingerprint.navigation.GridNavigationPolicy;
import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import io.github.bohdankordon.casinofingerprint.navigation.ProvenGridNavigationPolicy;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Synthetic deterministic matrix for the guarded action executor: exact control sequences,
 * every preflight refusal, every verification mismatch, latch semantics and the
 * never-retry-after-consumption rule. No OS input, no sleep, no recordings.
 */
class GuardedPlanExecutorTest {
    private static final String TARGET = "GTA5.exe";
    private static final VerificationPolicy FAST = new VerificationPolicy(10, 1_000, 1);

    private static DryRunPlan readyPlan() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6));
        DryRunPlan plan = DryRunPlanner.plan(identity, NavigationContext.characterized());
        assertTrue(plan.executable(), "characterized context plans every identity");
        return plan;
    }

    private static GridNavigationPolicy policy() {
        return NavigationContext.characterized().policy();
    }

    private static PuzzleControlState at(int focus, int... selected) {
        int[] scores = new int[8];
        Arrays.fill(scores, 10);
        scores[focus] = 346;
        int[] inners = new int[8];
        Arrays.fill(inners, 23);
        TreeSet<Integer> set = new TreeSet<>();
        for (int candidate : selected) {
            inners[candidate] = 76;
            set.add(candidate);
        }
        return PuzzleControlState.valid(GridPosition.of(focus), set, "test", scores, inners);
    }

    private static PuzzleControlState ambiguous() {
        return PuzzleControlState.invalid("MARGIN test", new int[8], new int[8]);
    }

    /** Exact success script for one plan: every poll the executor will serve, in order. */
    private static List<PuzzleControlState> successScript(DryRunPlan plan) {
        List<PuzzleControlState> script = new ArrayList<>();
        script.add(at(0));
        GridPosition cursor = GridPosition.C0;
        TreeSet<Integer> selected = new TreeSet<>();
        for (DryRunAction action : plan.actions()) {
            if (action instanceof DryRunAction.Navigate navigate) {
                GridPosition dest = policy().move(cursor, navigate.move()).orElseThrow();
                script.add(at(dest.index(), toArray(selected)));
                cursor = dest;
            } else if (action instanceof DryRunAction.Select select) {
                script.add(at(cursor.index(), toArray(selected)));
                TreeSet<Integer> after = new TreeSet<>(selected);
                after.add(select.candidate().index());
                script.add(at(cursor.index(), toArray(after)));
                selected = after;
            } else if (action instanceof DryRunAction.Proceed) {
                script.add(at(cursor.index(), toArray(selected)));
                script.add(at(cursor.index(), toArray(selected)));
                script.add(ambiguous());
            }
        }
        return script;
    }

    private static int[] toArray(TreeSet<Integer> selected) {
        return selected.stream().mapToInt(Integer::intValue).toArray();
    }

    private static List<GameControl> expectedTaps(DryRunPlan plan) {
        return plan.actions().stream().map(GuardedPlanExecutor::map).toList();
    }

    private static final class Fixture {
        final FakeGameInputSink sink = new FakeGameInputSink();
        final FakeForegroundTargetGuard foreground = FakeForegroundTargetGuard.pinned(TARGET);
        final FakeAbortSignal abort = FakeAbortSignal.calm();
        final ManualExecutionClock clock = new ManualExecutionClock();
        final CountingClaim claim = new CountingClaim();
        ScriptedControlStateSource states;
        GuardedPlanExecutor executor;

        Fixture script(PuzzleControlState... readings) {
            return script(FAST, readings);
        }

        Fixture script(VerificationPolicy policy, PuzzleControlState... readings) {
            this.states = new ScriptedControlStateSource(List.of(readings));
            this.executor = new GuardedPlanExecutor(policy(), sink, foreground, abort, states,
                    clock, policy);
            return this;
        }
    }

    private static final class CountingClaim implements RoundClaim {
        int claims;
        int tapsAtClaim = -1;
        FakeGameInputSink sink;

        @Override
        public void consumeSameFrame() {
            claims++;
            if (claims == 1) {
                tapsAtClaim = sink == null ? 0 : sink.taps().size();
            }
        }
    }

    private static Fixture successFixture(DryRunPlan plan) {
        Fixture fixture = new Fixture();
        fixture.script(successScript(plan).toArray(PuzzleControlState[]::new));
        fixture.claim.sink = fixture.sink;
        return fixture;
    }

    @Test
    void validPlanExecutesTheExactMappedControlSequence() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = successFixture(plan);
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.COMPLETED, report.state());
        assertEquals(expectedTaps(plan), fixture.sink.taps(), "exact control sequence");
        assertEquals(plan.actions().size(), report.tapsSent(), "one tap per action");
        assertEquals(1, fixture.claim.claims, "claimed exactly once");
        assertTrue(report.log().get(0).startsWith("EXECUTION START FP_4"), "start log");
        assertTrue(report.log().contains("EXECUTION COMPLETE"), "completion log");
        assertTrue(report.log().stream().anyMatch(line -> line.contains("-> verified ")),
                "per-action verification log");
        assertEquals(1, fixture.sink.taps().stream().filter(tap -> tap == GameControl.PROCEED)
                .count(), "PROCEED sent exactly once");
    }

    @Test
    void firstNavigateVerifiedBeforeNextAction() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = successFixture(plan);
        fixture.executor.execute(plan, TARGET, fixture.claim);
        List<GameControl> taps = fixture.sink.taps();
        assertTrue(taps.size() > 1, "more than one action executed");
        assertEquals(expectedTaps(plan).subList(0, 2), taps.subList(0, 2),
                "first two taps match the plan head");
    }

    @Test
    void startFocusOffC0SendsNothingAndConsumesNothing() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(at(3));
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.IDLE, report.state(), "preflight refusal stays IDLE");
        assertTrue(fixture.sink.taps().isEmpty(), "zero inputs");
        assertEquals(0, fixture.claim.claims, "no consumption");
        assertTrue(report.summary().contains("C3") && report.summary().contains("expected C0"),
                "diagnostic names the wrong start: " + report.summary());
    }

    @Test
    void nonEmptySelectedSetAtStartSendsNothingAndConsumesNothing() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(at(0, 1));
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.IDLE, report.state());
        assertTrue(fixture.sink.taps().isEmpty(), "zero inputs");
        assertEquals(0, fixture.claim.claims, "no consumption");
        assertTrue(report.summary().contains("[1]"), "diagnostic names the selection: "
                + report.summary());
    }

    @Test
    void ambiguousStartStateSendsNothingAndConsumesNothing() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(ambiguous());
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.IDLE, report.state());
        assertTrue(fixture.sink.taps().isEmpty(), "zero inputs");
        assertEquals(0, fixture.claim.claims, "no consumption");
    }

    @Test
    void targetProcessNotForegroundSendsNothingAndConsumesNothing() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture();
        FakeForegroundTargetGuard missing = FakeForegroundTargetGuard.missing();
        ScriptedControlStateSource states = new ScriptedControlStateSource(List.of(at(0)));
        GuardedPlanExecutor executor = new GuardedPlanExecutor(policy(), fixture.sink, missing,
                fixture.abort, states, fixture.clock, FAST);
        ExecutionReport report = executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.IDLE, report.state());
        assertTrue(fixture.sink.taps().isEmpty(), "zero inputs");
        assertEquals(0, fixture.claim.claims, "no consumption");
        assertTrue(report.summary().contains("foreground target"), "diagnostic: "
                + report.summary());
    }

    @Test
    void abortActiveBeforeStartLatchesAbortedWithNoInput() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(at(0));
        fixture.abort.fire();
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.ABORTED, report.state());
        assertTrue(fixture.sink.taps().isEmpty(), "zero inputs");
        assertEquals(0, fixture.claim.claims, "no consumption");
        assertEquals(ExecutionState.ABORTED, fixture.executor.state(), "latched");
    }

    @Test
    void navigateToWrongCandidateFaultsWithNoFurtherInput() {
        DryRunPlan plan = readyPlan();
        DryRunAction.Navigate first =
                (DryRunAction.Navigate) plan.actions().get(0);
        GridPosition dest = policy().move(GridPosition.C0, first.move()).orElseThrow();
        GridPosition wrong = dest.index() == 1 ? GridPosition.C3 : GridPosition.C1;
        Fixture fixture = new Fixture().script(at(0), at(wrong.index()));
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, report.state());
        assertEquals(1, fixture.sink.taps().size(), "only the first tap was sent");
        assertTrue(report.summary().contains("expected focus " + dest),
                "diagnostic names the mismatch: " + report.summary());
    }

    @Test
    void navigateChangingSelectedSetFaults() {
        DryRunPlan plan = readyPlan();
        DryRunAction.Navigate first =
                (DryRunAction.Navigate) plan.actions().get(0);
        GridPosition dest = policy().move(GridPosition.C0, first.move()).orElseThrow();
        Fixture fixture =
                new Fixture().script(at(0), at(dest.index(), dest.index()));
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, report.state());
        assertEquals(1, fixture.sink.taps().size(), "only the first tap was sent");
        assertTrue(report.summary().contains("selection unexpectedly changed"),
                "diagnostic: " + report.summary());
    }

    @Test
    void selectKeepingFocusAndAddingExactlyExpectedCandidateContinues() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = successFixture(plan);
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.COMPLETED, report.state());
        assertTrue(report.log().stream().anyMatch(line -> line.startsWith("SELECT ")
                && line.contains("verified selected=")), "select verification log");
    }

    @Test
    void selectNeverConfirmedBeforeTimeoutFaultsWithoutRetry() {
        DryRunPlan plan = readyPlan();
        int navigatesBeforeFirstSelect = 0;
        for (DryRunAction action : plan.actions()) {
            if (action instanceof DryRunAction.Select) {
                break;
            }
            navigatesBeforeFirstSelect++;
        }
        DryRunAction.Select firstSelect = null;
        GridPosition cursor = GridPosition.C0;
        for (DryRunAction action : plan.actions()) {
            if (action instanceof DryRunAction.Navigate navigate) {
                cursor = policy().move(cursor, navigate.move()).orElseThrow();
            } else if (action instanceof DryRunAction.Select select) {
                firstSelect = select;
                break;
            }
        }
        List<PuzzleControlState> script = new ArrayList<>();
        script.add(at(0));
        GridPosition walk = GridPosition.C0;
        for (int index = 0; index < navigatesBeforeFirstSelect; index++) {
            DryRunAction.Navigate navigate =
                    (DryRunAction.Navigate) plan.actions().get(index);
            walk = policy().move(walk, navigate.move()).orElseThrow();
            script.add(at(walk.index()));
        }
        script.add(at(cursor.index()));
        for (int repeat = 0; repeat < 30; repeat++) {
            script.add(at(cursor.index()));
        }
        Fixture fixture = new Fixture()
                .script(new VerificationPolicy(5, 60, 1), script.toArray(PuzzleControlState[]::new));
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, report.state());
        assertEquals(navigatesBeforeFirstSelect + 1, fixture.sink.taps().size(),
                "no tap retried after the timeout");
        assertTrue(report.summary().contains("not visually confirmed"),
                "diagnostic: " + report.summary());
    }

    @Test
    void selectChangingWrongCandidateFaults() {
        DryRunPlan plan = readyPlan();
        DryRunAction.Navigate first =
                (DryRunAction.Navigate) plan.actions().get(0);
        GridPosition dest = policy().move(GridPosition.C0, first.move()).orElseThrow();
        int wrong = dest.index() == 0 ? 1 : 0;
        Fixture fixture = new Fixture()
                .script(at(0), at(dest.index()), at(dest.index()), at(dest.index(), wrong));
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, report.state());
        assertEquals(2, fixture.sink.taps().size(), "navigate plus select taps only");
    }

    @Test
    void foregroundLossAfterFirstActionStopsWithNoSecondAction() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(successScript(plan).toArray(PuzzleControlState[]::new));
        fixture.states.onPoll(count -> {
            if (count == 2) {
                fixture.foreground.lose();
            }
        });
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, report.state());
        assertEquals(1, fixture.sink.taps().size(), "no second action");
        assertTrue(report.summary().contains("focus lost"), "diagnostic: " + report.summary());
    }

    @Test
    void abortAfterFirstActionStopsWithNoSecondAction() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(successScript(plan).toArray(PuzzleControlState[]::new));
        fixture.states.onPoll(count -> {
            if (count == 2) {
                fixture.abort.fire();
            }
        });
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.ABORTED, report.state());
        assertEquals(1, fixture.sink.taps().size(), "no second action");
    }

    @Test
    void backendRefusalAfterFirstActionFaultsWithNoRetry() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(successScript(plan).toArray(PuzzleControlState[]::new));
        fixture.sink.refuseAtIndex(1);
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, report.state());
        assertEquals(1, fixture.sink.taps().size(), "refused tap is not recorded");
        assertTrue(report.summary().contains("input backend failed"),
                "diagnostic: " + report.summary());
    }

    @Test
    void incompleteSelectionBeforeProceedNeverSendsProceed() {
        DryRunPlan plan = readyPlan();
        List<PuzzleControlState> script = new ArrayList<>(successScript(plan));
        int proceedReadyIndex = script.size() - 3;
        GridPosition cursor = GridPosition.C0;
        TreeSet<Integer> selected = new TreeSet<>();
        for (DryRunAction action : plan.actions()) {
            if (action instanceof DryRunAction.Navigate navigate) {
                cursor = policy().move(cursor, navigate.move()).orElseThrow();
            } else if (action instanceof DryRunAction.Select select) {
                selected.add(select.candidate().index());
            }
        }
        TreeSet<Integer> missing = new TreeSet<>(selected);
        missing.remove(missing.last());
        script.set(proceedReadyIndex, at(cursor.index(), toArray(missing)));
        Fixture fixture = new Fixture().script(script.toArray(PuzzleControlState[]::new));
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, report.state());
        assertFalse(fixture.sink.taps().contains(GameControl.PROCEED), "PROCEED never sent");
    }

    @Test
    void failureAfterConsumptionNeverRetriesTheFullPlan() {
        DryRunPlan plan = readyPlan();
        DryRunAction.Navigate first =
                (DryRunAction.Navigate) plan.actions().get(0);
        GridPosition dest = policy().move(GridPosition.C0, first.move()).orElseThrow();
        GridPosition wrong = dest.index() == 1 ? GridPosition.C3 : GridPosition.C1;
        Fixture fixture = new Fixture().script(at(0), at(wrong.index()));
        ExecutionReport faulted = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, faulted.state());
        int tapsAfterFault = fixture.sink.taps().size();
        assertThrows(IllegalStateException.class,
                () -> fixture.executor.execute(plan, TARGET, fixture.claim),
                "latched executions never run again without reset");
        assertEquals(tapsAfterFault, fixture.sink.taps().size(), "no retry taps");
        assertEquals(1, fixture.claim.claims, "claimed exactly once");
    }

    @Test
    void preflightFailureBeforeConsumptionLeavesTheRoundPending() {
        DryRunPlan plan = readyPlan();
        List<PuzzleControlState> script = new ArrayList<>();
        script.add(ambiguous());
        script.addAll(successScript(plan));
        Fixture fixture =
                new Fixture().script(script.toArray(PuzzleControlState[]::new));
        fixture.claim.sink = fixture.sink;
        ExecutionReport blocked = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.IDLE, blocked.state(), "first attempt stays IDLE");
        assertEquals(0, fixture.claim.claims, "nothing claimed before preflight passes");
        ExecutionReport done = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.COMPLETED, done.state(), "later stable attempt executes");
        assertEquals(expectedTaps(plan), fixture.sink.taps(), "full sequence after retry");
        assertEquals(1, fixture.claim.claims, "claimed exactly once, before the first input");
        assertEquals(0, fixture.claim.tapsAtClaim, "claim precedes the first tap");
    }

    @Test
    void consumeFailureBeforeFirstInputLatchesFaultWithNoInput() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(at(0));
        RoundClaim failing = () -> {
            throw new IllegalStateException("round went stale");
        };
        ExecutionReport report = fixture.executor.execute(plan, TARGET, failing);
        assertEquals(ExecutionState.FAULTED, report.state());
        assertTrue(fixture.sink.taps().isEmpty(), "zero inputs");
    }

    @Test
    void validatorViolationPlanNeverReachesInput() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(at(0));
        GuardedPlanExecutor strict = new GuardedPlanExecutor(
                ProvenGridNavigationPolicy.empty(), fixture.sink, fixture.foreground,
                fixture.abort, fixture.states, fixture.clock, FAST);
        assertThrows(IllegalArgumentException.class,
                () -> strict.execute(plan, TARGET, fixture.claim),
                "unproven moves under this policy are refused");
        assertTrue(fixture.sink.taps().isEmpty(), "zero inputs");
        assertEquals(0, fixture.claim.claims, "no consumption");
    }

    @Test
    void blockedPlanNeverReachesInput() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6));
        DryRunPlan blocked = DryRunPlan.blocked(identity, List.of("no proven start"));
        Fixture fixture = new Fixture().script(at(0));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.executor.execute(blocked, TARGET, fixture.claim),
                "BLOCKED plans are refused");
        assertTrue(fixture.sink.taps().isEmpty(), "zero inputs");
        assertEquals(0, fixture.claim.claims, "no consumption");
    }

    @Test
    void resetReleasesTerminalStatesBackToIdle() {
        DryRunPlan plan = readyPlan();
        List<PuzzleControlState> script = new ArrayList<>(successScript(plan));
        script.addAll(successScript(plan));
        Fixture fixture = new Fixture().script(script.toArray(PuzzleControlState[]::new));
        fixture.claim.sink = fixture.sink;
        ExecutionReport done = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.COMPLETED, done.state());
        assertThrows(IllegalStateException.class,
                () -> fixture.executor.execute(plan, TARGET, fixture.claim),
                "COMPLETED needs an explicit reset first");
        fixture.executor.reset();
        assertEquals(ExecutionState.IDLE, fixture.executor.state());
        ExecutionReport again = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.COMPLETED, again.state(), "executes again after reset");
        assertEquals(2, fixture.claim.claims, "one claim per execution");
    }

    @Test
    void resetAfterFaultReturnsToIdleForExplicitRecovery() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(at(0), at(3));
        ExecutionReport faulted = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, faulted.state());
        fixture.executor.reset();
        assertEquals(ExecutionState.IDLE, fixture.executor.state());
    }

    @Test
    void interruptionWhilePollingLatchesFault() {
        DryRunPlan plan = readyPlan();
        Fixture fixture = new Fixture().script(successScript(plan).toArray(PuzzleControlState[]::new));
        fixture.clock.refuseSleep();
        ExecutionReport report = fixture.executor.execute(plan, TARGET, fixture.claim);
        assertEquals(ExecutionState.FAULTED, report.state());
        assertTrue(report.summary().contains("interrupted"), "diagnostic: " + report.summary());
    }

    @Test
    void actionMappingFollowsTheGameControlContract() {
        assertEquals(GameControl.RIGHT,
                GuardedPlanExecutor.map(new DryRunAction.Navigate(
                        io.github.bohdankordon.casinofingerprint.navigation.Move.RIGHT)));
        assertEquals(GameControl.UP, GuardedPlanExecutor.map(
                new DryRunAction.Navigate(io.github.bohdankordon.casinofingerprint.navigation.Move.UP)));
        assertEquals(GameControl.SELECT,
                GuardedPlanExecutor.map(new DryRunAction.Select(GridPosition.C4)));
        assertEquals(GameControl.PROCEED, GuardedPlanExecutor.map(new DryRunAction.Proceed()));
    }
}
