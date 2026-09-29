package io.github.bohdankordon.casinofingerprint.execution;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.input.AbortSignal;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTarget;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunAction;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.navigation.GridNavigationPolicy;
import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import io.github.bohdankordon.casinofingerprint.navigation.PlanValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Production executor of one validated Stage 7A dry-run plan with guarded live input.
 *
 * <pre>
 * validated READY plan
 *     -&gt; live preflight (plan, abort, foreground pin, visual start C0 + empty selection)
 *     -&gt; lifecycle claim of the SAME preflighted round
 *     -&gt; one abstract action = one key tap + visual verification against fresh frames
 *     -&gt; PROCEED exactly once, then a bounded acknowledgement read (never retried)
 * </pre>
 *
 * <p>Safety contract, enforced in this order:
 *
 * <ul>
 *   <li>an invalid or BLOCKED plan never reaches input: it is rejected with
 *       {@link IllegalArgumentException} before anything is touched;</li>
 *   <li>preflight sends no input and consumes nothing: abort active, a foreground window
 *       outside the configured executable, an ambiguous control reading, a start focus
 *       other than C0 or a non-empty selected set all return a BLOCKED report with the
 *       executor still IDLE, so the pending round survives for a later stable re-attempt;</li>
 *   <li>the lifecycle claim happens after every preflight gate passed and before the first
 *       gameplay input, exactly once;</li>
 *   <li>every NAVIGATE and SELECT is visually confirmed on fresh frames (configurable
 *       consecutive confirmations) before the next action: a clearly wrong focus, an
 *       unexpected selection change, a verification timeout, focus loss, abort or a send
 *       failure latches FAULTED or ABORTED, sends no further input, never auto-resets the
 *       lifecycle and never retries the round;</li>
 *   <li>PROCEED is sent exactly once and never retried; its acknowledgement only decides
 *       the completion note, never a second Tab.</li>
 * </ul>
 *
 * <p>Single-threaded by construction: one executor belongs to one recognition stream, and
 * {@link #execute} must not be re-entered while EXECUTING. Polling uses the injected
 * {@link ExecutionClock} only.
 */
public final class GuardedPlanExecutor {
    private final GridNavigationPolicy policy;
    private final GameInputSink sink;
    private final ForegroundTargetGuard foreground;
    private final AbortSignal abort;
    private final ControlStateSource states;
    private final ExecutionClock clock;
    private final VerificationPolicy verification;
    private ExecutionState state = ExecutionState.IDLE;

    /**
     * @param policy proven navigation graph destinations are simulated with; required
     * @param sink gameplay tap backend; required
     * @param foreground foreground-target guard; required
     * @param abort emergency abort signal; required
     * @param states fresh-frame control-state readings; required
     * @param clock monotonic clock plus poll sleeper; required
     * @param verification poll bounds; required
     */
    public GuardedPlanExecutor(GridNavigationPolicy policy, GameInputSink sink,
            ForegroundTargetGuard foreground, AbortSignal abort, ControlStateSource states,
            ExecutionClock clock, VerificationPolicy verification) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.foreground = Objects.requireNonNull(foreground, "foreground");
        this.abort = Objects.requireNonNull(abort, "abort");
        this.states = Objects.requireNonNull(states, "states");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.verification = Objects.requireNonNull(verification, "verification");
    }

    /** Current input-execution ownership; latched FAULTED/ABORTED need {@link #reset()}. */
    public ExecutionState state() {
        return state;
    }

    /**
     * Maps one abstract Stage 7A action to its gameplay input intention (the project
     * game-control contract: arrows, Enter, Tab). Pure data, no input sent.
     */
    public static GameControl map(DryRunAction action) {
        Objects.requireNonNull(action, "action");
        if (action instanceof DryRunAction.Navigate navigate) {
            return switch (navigate.move()) {
                case UP -> GameControl.UP;
                case DOWN -> GameControl.DOWN;
                case LEFT -> GameControl.LEFT;
                case RIGHT -> GameControl.RIGHT;
            };
        }
        if (action instanceof DryRunAction.Select) {
            return GameControl.SELECT;
        }
        if (action instanceof DryRunAction.Proceed) {
            return GameControl.PROCEED;
        }
        throw new IllegalArgumentException("Unknown abstract action: " + action);
    }

    /**
     * Executes one validated READY plan behind every safety gate.
     *
     * @param plan validated Stage 7A plan; must be READY with zero validator violations
     * @param targetExecutable exact foreground executable file name; required
     * @param claim lifecycle commitment of the preflighted round; consumed exactly once
     *        after preflight and before the first input; required
     * @return the outcome plus the diagnostic log
     * @throws IllegalStateException when called while EXECUTING or while a FAULTED/ABORTED
     *         latch is held (call {@link #reset()} first)
     * @throws IllegalArgumentException when the plan is invalid or BLOCKED: nothing is
     *         consumed and nothing is sent
     */
    public ExecutionReport execute(DryRunPlan plan, String targetExecutable, RoundClaim claim) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(targetExecutable, "targetExecutable");
        Objects.requireNonNull(claim, "claim");
        if (state == ExecutionState.EXECUTING) {
            throw new IllegalStateException("GuardedPlanExecutor is already EXECUTING");
        }
        if (state == ExecutionState.FAULTED || state == ExecutionState.ABORTED) {
            throw new IllegalStateException("GuardedPlanExecutor latched " + state
                    + ": explicit reset (or an application restart) is required");
        }
        if (state != ExecutionState.IDLE) {
            throw new IllegalStateException(
                    "GuardedPlanExecutor must be IDLE (call reset() after COMPLETED), got "
                            + state);
        }
        List<String> violations = PlanValidator.validate(plan, policy);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Refusing to execute an invalid plan: " + violations);
        }
        List<String> log = new ArrayList<>();
        log.add("EXECUTION START " + plan.identity().code() + " order=" + plan.order());
        if (abort.isActive()) {
            return latched(ExecutionState.ABORTED, "ABORTED: emergency abort active before start",
                    log, 0);
        }
        Optional<ForegroundTarget> pin = foreground.pin(targetExecutable);
        if (pin.isEmpty()) {
            return blocked("BLOCKED: GTA is not the foreground target (required "
                    + targetExecutable + ")", log);
        }
        PuzzleControlState start = states.poll();
        if (!start.valid()) {
            return blocked("BLOCKED: control state is ambiguous (" + start.detail() + ")", log);
        }
        if (!start.focus().orElseThrow().equals(GridPosition.C0)) {
            return blocked("BLOCKED: selector start is " + start.focus().orElseThrow()
                    + ", expected C0", log);
        }
        if (!start.selected().isEmpty()) {
            return blocked("BLOCKED: candidates already selected " + start.selected(), log);
        }
        try {
            claim.consumeSameFrame();
        } catch (RuntimeException e) {
            return latched(ExecutionState.FAULTED,
                    "FAULTED: lifecycle consume failed before first input: " + e.getMessage(),
                    log, 0);
        }
        state = ExecutionState.EXECUTING;
        log.add("verified start=C0 selected=[]");
        int[] taps = {0};
        try {
            return runActions(plan, pin.get(), log, taps);
        } catch (ExecutionHalt halt) {
            return latched(halt.state, halt.detail, log, taps[0]);
        }
    }

    /**
     * Releases a terminal state (COMPLETED, FAULTED or ABORTED) back to IDLE. Never callable
     * while EXECUTING, and never called automatically: a latched failure needs this explicit
     * reset (or an application restart) before any new execution.
     */
    public void reset() {
        if (state == ExecutionState.EXECUTING) {
            throw new IllegalStateException("Cannot reset while EXECUTING");
        }
        state = ExecutionState.IDLE;
    }

    private ExecutionReport blocked(String line, List<String> log) {
        log.add(line);
        return new ExecutionReport(ExecutionState.IDLE, 0, log, line);
    }

    private ExecutionReport latched(ExecutionState latched, String line, List<String> log,
            int taps) {
        state = latched;
        log.add(line);
        log.add("NO FURTHER INPUT WILL BE SENT until explicit reset");
        return new ExecutionReport(latched, taps, log, line);
    }

    private ExecutionReport runActions(DryRunPlan plan, ForegroundTarget pin, List<String> log,
            int[] taps) {
        GridPosition cursor = GridPosition.C0;
        TreeSet<Integer> expectedSelected = new TreeSet<>();
        List<DryRunAction> actions = plan.actions();
        for (DryRunAction action : actions) {
            if (action instanceof DryRunAction.Navigate navigate) {
                GridPosition dest = policy.move(cursor, navigate.move()).orElseThrow(
                        () -> new IllegalStateException("Validated plan holds an unproven move: "
                                + navigate));
                Gate gate = checkGo(pin);
                if (gate != null) {
                    return latched(gate.state, gate.line + " before " + navigate.render(),
                            log, taps[0]);
                }
                tapOnce(map(action), navigate.render(), taps);
                Poll poll = pollNavigate(pin, dest, cursor, expectedSelected);
                if (!poll.ok()) {
                    return latched(poll.state(), poll.detail(), log, taps[0]);
                }
                cursor = dest;
                log.add(navigate.render() + " -> verified " + dest);
            } else if (action instanceof DryRunAction.Select select) {
                Gate gate = checkGo(pin);
                if (gate != null) {
                    return latched(gate.state, gate.line + " before " + select.render(),
                            log, taps[0]);
                }
                Poll ready = pollSelectReady(pin, select.candidate(), expectedSelected);
                if (!ready.ok()) {
                    return latched(ready.state(), ready.detail(), log, taps[0]);
                }
                tapOnce(map(action), select.render(), taps);
                Poll poll = pollSelectDone(pin, select.candidate(), expectedSelected);
                if (!poll.ok()) {
                    return latched(poll.state(), poll.detail(), log, taps[0]);
                }
                expectedSelected.add(select.candidate().index());
                log.add("SELECT " + select.candidate() + " -> verified selected="
                        + expectedSelected);
            } else if (action instanceof DryRunAction.Proceed) {
                return runProceed(plan, pin, log, taps, expectedSelected);
            }
        }
        throw new IllegalStateException("Validated READY plans always end with PROCEED");
    }

    /** Sends one tap, counting it; a backend refusal halts the execution as FAULTED. */
    private void tapOnce(GameControl control, String rendered, int[] taps) {
        try {
            sink.tap(control);
            taps[0]++;
        } catch (GameInputException e) {
            throw new ExecutionHalt(ExecutionState.FAULTED,
                    "FAULTED: input backend failed on " + rendered + ": " + e.getMessage());
        }
    }

    /** Internal carrier turning a mid-execution halt into a latched report at the boundary. */
    private static final class ExecutionHalt extends RuntimeException {
        final ExecutionState state;
        final String detail;

        ExecutionHalt(ExecutionState state, String detail) {
            super(detail);
            this.state = state;
            this.detail = detail;
        }
    }

    private record Gate(ExecutionState state, String line) {
    }

    /** Abort/foreground gate before a tap: null when the tap may proceed. */
    private Gate checkGo(ForegroundTarget pin) {
        if (abort.isActive()) {
            return new Gate(ExecutionState.ABORTED, "ABORTED: emergency key detected");
        }
        if (!foreground.isPinned(pin)) {
            return new Gate(ExecutionState.FAULTED, "FAULTED: focus lost (" +
                    "foreground window changed)");
        }
        return null;
    }

    private record Poll(boolean ok, ExecutionState state, String detail) {
    }

    private Poll pollNavigate(ForegroundTarget pin, GridPosition dest, GridPosition prev,
            TreeSet<Integer> expectedSelected) {
        long deadline = clock.nanos() + verification.actionTimeoutMillis() * 1_000_000L;
        int streak = 0;
        while (true) {
            if (abort.isActive()) {
                return new Poll(false, ExecutionState.ABORTED,
                        "ABORTED: emergency key detected while verifying " + dest);
            }
            if (!foreground.isPinned(pin)) {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: focus lost while verifying " + dest);
            }
            if (clock.nanos() >= deadline) {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: verification timeout waiting for focus " + dest);
            }
            PuzzleControlState seen = states.poll();
            if (seen.valid() && seen.focus().orElseThrow().equals(dest)
                    && seen.selected().equals(expectedSelected)) {
                streak++;
                if (streak >= verification.confirmations()) {
                    return new Poll(true, ExecutionState.EXECUTING, "verified " + dest);
                }
            } else if (seen.valid() && seen.focus().orElseThrow().equals(prev)
                    && seen.selected().equals(expectedSelected)) {
                streak = 0;
            } else if (!seen.valid()) {
                streak = 0;
            } else if (!seen.selected().equals(expectedSelected)) {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: selection unexpectedly changed while navigating to " + dest
                                + ": expected " + expectedSelected + " observed "
                                + seen.selected());
            } else {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: expected focus " + dest + ", observed "
                                + seen.focus().orElseThrow());
            }
            sleepPoll();
        }
    }

    private Poll pollSelectReady(ForegroundTarget pin, GridPosition candidate,
            TreeSet<Integer> expectedSelected) {
        long deadline = clock.nanos() + verification.actionTimeoutMillis() * 1_000_000L;
        while (true) {
            if (abort.isActive()) {
                return new Poll(false, ExecutionState.ABORTED,
                        "ABORTED: emergency key detected before SELECT " + candidate);
            }
            if (!foreground.isPinned(pin)) {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: focus lost before SELECT " + candidate);
            }
            if (clock.nanos() >= deadline) {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: start state of SELECT " + candidate
                                + " was not visually confirmed");
            }
            PuzzleControlState seen = states.poll();
            if (seen.valid() && seen.focus().orElseThrow().equals(candidate)
                    && seen.selected().equals(expectedSelected)) {
                return new Poll(true, ExecutionState.EXECUTING, "ready " + candidate);
            }
            if (seen.valid()) {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: unexpected UI state before SELECT " + candidate + ": " + seen);
            }
            sleepPoll();
        }
    }

    private Poll pollSelectDone(ForegroundTarget pin, GridPosition candidate,
            TreeSet<Integer> expectedSelected) {
        TreeSet<Integer> after = new TreeSet<>(expectedSelected);
        after.add(candidate.index());
        long deadline = clock.nanos() + verification.actionTimeoutMillis() * 1_000_000L;
        int streak = 0;
        while (true) {
            if (abort.isActive()) {
                return new Poll(false, ExecutionState.ABORTED,
                        "ABORTED: emergency key detected while verifying SELECT " + candidate);
            }
            if (!foreground.isPinned(pin)) {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: focus lost while verifying SELECT " + candidate);
            }
            if (clock.nanos() >= deadline) {
                return new Poll(false, ExecutionState.FAULTED, "FAULTED: selection of "
                        + candidate + " was not visually confirmed");
            }
            PuzzleControlState seen = states.poll();
            if (seen.valid() && seen.focus().orElseThrow().equals(candidate)
                    && seen.selected().equals(after)) {
                streak++;
                if (streak >= verification.confirmations()) {
                    return new Poll(true, ExecutionState.EXECUTING, "selected " + after);
                }
            } else if (seen.valid() && seen.focus().orElseThrow().equals(candidate)
                    && seen.selected().equals(expectedSelected)) {
                streak = 0;
            } else if (!seen.valid()) {
                streak = 0;
            } else if (!seen.focus().orElseThrow().equals(candidate)) {
                return new Poll(false, ExecutionState.FAULTED, "FAULTED: focus left "
                        + candidate + " during SELECT, observed "
                        + seen.focus().orElseThrow());
            } else {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: wrong selection change for SELECT " + candidate
                                + ": expected " + after + " observed " + seen.selected());
            }
            sleepPoll();
        }
    }

    private ExecutionReport runProceed(DryRunPlan plan, ForegroundTarget pin, List<String> log,
            int[] taps, TreeSet<Integer> expectedSelected) {
        TreeSet<Integer> planned = new TreeSet<>(plan.identity().candidates());
        if (!expectedSelected.equals(planned)) {
            throw new IllegalStateException("PROCEED with " + expectedSelected.size()
                    + " of 4 selections");
        }
        Poll ready = pollProceedReady(pin, planned);
        if (!ready.ok()) {
            return latched(ready.state(), ready.detail(), log, taps[0]);
        }
        Gate gate = checkGo(pin);
        if (gate != null) {
            return latched(gate.state, gate.line + " before PROCEED", log, taps[0]);
        }
        tapOnce(GameControl.PROCEED, "PROCEED", taps);
        log.add("PROCEED -> sent");
        boolean acknowledged = pollProceedAck(pin, planned);
        state = ExecutionState.COMPLETED;
        if (acknowledged) {
            log.add("round advance visually acknowledged");
        } else {
            log.add("PROCEED_SENT: round advance not visually confirmed within bounds; "
                    + "control returns to the recognition loop without a second Tab");
        }
        log.add("EXECUTION COMPLETE");
        return new ExecutionReport(ExecutionState.COMPLETED, taps[0], log,
                acknowledged ? "EXECUTION COMPLETE " + plan.identity().code()
                        : "EXECUTION COMPLETE " + plan.identity().code() + " PROCEED_SENT");
    }

    private Poll pollProceedReady(ForegroundTarget pin, TreeSet<Integer> planned) {
        long deadline = clock.nanos() + verification.actionTimeoutMillis() * 1_000_000L;
        int streak = 0;
        while (true) {
            if (abort.isActive()) {
                return new Poll(false, ExecutionState.ABORTED,
                        "ABORTED: emergency key detected before PROCEED");
            }
            if (!foreground.isPinned(pin)) {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: focus lost before PROCEED");
            }
            if (clock.nanos() >= deadline) {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: four selections were not visually confirmed before PROCEED");
            }
            PuzzleControlState seen = states.poll();
            if (seen.valid() && seen.selected().equals(planned)) {
                streak++;
                if (streak >= verification.confirmations()) {
                    return new Poll(true, ExecutionState.EXECUTING, "four selected");
                }
            } else if (!seen.valid()) {
                streak = 0;
            } else {
                return new Poll(false, ExecutionState.FAULTED,
                        "FAULTED: selection changed before PROCEED: expected " + planned
                                + " observed " + seen.selected());
            }
            sleepPoll();
        }
    }

    /**
     * Bounded acknowledgement read after PROCEED: true once the executed control state no
     * longer persists (selection cleared, focus moved on, or the puzzle left). Never fails,
     * never retries input: a missing acknowledgement only changes the completion note.
     */
    private boolean pollProceedAck(ForegroundTarget pin, TreeSet<Integer> planned) {
        GridPosition lastFocus = null;
        long deadline = clock.nanos() + verification.actionTimeoutMillis() * 1_000_000L;
        while (true) {
            if (abort.isActive() || !foreground.isPinned(pin)) {
                return false;
            }
            if (clock.nanos() >= deadline) {
                return false;
            }
            PuzzleControlState seen = states.poll();
            if (!seen.valid() || !seen.selected().equals(planned)) {
                return true;
            }
            if (lastFocus == null) {
                lastFocus = seen.focus().orElse(null);
            } else if (!seen.focus().orElse(null).equals(lastFocus)) {
                return true;
            }
            sleepPoll();
        }
    }

    private void sleepPoll() {
        try {
            clock.sleepMillis(verification.pollIntervalMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionHalt(ExecutionState.FAULTED,
                    "FAULTED: execution interrupted while polling");
        }
    }
}
