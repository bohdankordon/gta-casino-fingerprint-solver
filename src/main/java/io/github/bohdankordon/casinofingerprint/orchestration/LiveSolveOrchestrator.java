package io.github.bohdankordon.casinofingerprint.orchestration;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.execution.ControlStateSource;
import io.github.bohdankordon.casinofingerprint.execution.ExecutionClock;
import io.github.bohdankordon.casinofingerprint.execution.ExecutionReport;
import io.github.bohdankordon.casinofingerprint.execution.ExecutionState;
import io.github.bohdankordon.casinofingerprint.execution.GuardedPlanExecutor;
import io.github.bohdankordon.casinofingerprint.execution.VerificationPolicy;
import io.github.bohdankordon.casinofingerprint.input.AbortSignal;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlanner;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionObservation;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleState;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleWitnessCoordinator;
import io.github.bohdankordon.casinofingerprint.runtime.UnsupportedFrameSizeException;
import java.util.Objects;
import java.util.Optional;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Production guarded live orchestration: Stage 7A recognition-to-plan chain plus the first
 * gameplay input capability, behind every Stage 7B safety gate.
 *
 * <pre>
 * captured frame
 *     -&gt; FrameRecognitionPipeline.observe (SAME-frame owned observation)
 *     -&gt; RecognitionConsensusTracker
 *     -&gt; RoundLifecycleWitnessCoordinator
 *     -&gt; on ROUND_READY: validated DryRunPlan (no consumption yet)
 *     -&gt; GuardedPlanExecutor: visual preflight on the SAME frame, lifecycle claim of the
 *        SAME observation, then one verified tap per abstract action
 * </pre>
 *
 * <p>Commitment order is the whole point: a ROUND_READY plan alone never sends input. The
 * executor preflights the same frame (foreground pin, abort, visual start C0 with an empty
 * selected set) and only then claims the round through the coordinator with the same
 * observation, before the first tap. A failed preflight consumes nothing and sends nothing:
 * the lifecycle round stays ROUND_READY, and later stable frames of the same pending identity
 * re-present the plan for a new preflight with the latest same-frame observation (the
 * coordinator keeps the latest stable frame consumable while the identity stays ready).
 * After a claim, any failure latches the executor FAULTED or ABORTED: no auto-reset, no
 * auto-retry, only an explicit reset or an application restart.
 *
 * <p>Execution runs synchronously inside {@link #onFrame}: the executor verification polls
 * capture fresh frames through the injected capture while this call blocks, so recognition
 * observes nothing mid-round. That gap is safe: the round identity cannot change without the
 * executor own taps, and the post-round frames resume the chain afterwards. No clock, sleep
 * or duration here feeds recognition, consensus, lifecycle, witness or planning: the injected
 * clock serves input verification polling only. State is deterministic and single-threaded.
 */
public final class LiveSolveOrchestrator implements AutoCloseable {
    private final FrameRecognitionPipeline pipeline;
    private final RecognitionConsensusTracker consensus;
    private final RoundLifecycleWitnessCoordinator coordinator;
    private final NavigationContext navigation;
    private final FrameControlReader controls;
    private final PollChain polls;
    private final GuardedPlanExecutor executor;
    private final String targetExecutable;
    private boolean closed;
    private LiveRecognitionStatus lastStatus = LiveRecognitionStatus.waiting();

    /**
     * @param pipeline full-frame recognition pipeline; borrowed, never closed here
     * @param navigation selector start plus proven navigation graph; required
     * @param controls raw control-UI reader for the borrowed frame; required
     * @param capture fresh-frame capture for post-claim verification polls; required
     * @param targetExecutable exact foreground executable file name; required, non-blank
     * @param sink gameplay tap backend; required
     * @param foreground foreground-target guard; required
     * @param abort emergency abort signal; required
     * @param clock monotonic clock plus poll sleeper; required
     * @param verification poll bounds; required
     * @param requiredConsecutiveFrames consecutive identical answers before stability; positive
     */
    public LiveSolveOrchestrator(FrameRecognitionPipeline pipeline, NavigationContext navigation,
            FrameControlReader controls, FrameCapture capture, String targetExecutable,
            GameInputSink sink, ForegroundTargetGuard foreground, AbortSignal abort,
            ExecutionClock clock, VerificationPolicy verification, int requiredConsecutiveFrames) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.controls = Objects.requireNonNull(controls, "controls");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(foreground, "foreground");
        Objects.requireNonNull(abort, "abort");
        Objects.requireNonNull(targetExecutable, "targetExecutable");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(verification, "verification");
        if (targetExecutable.isBlank()) {
            throw new IllegalArgumentException("targetExecutable must name the GTA executable");
        }
        this.targetExecutable = targetExecutable;
        this.consensus = new RecognitionConsensusTracker(requiredConsecutiveFrames);
        this.coordinator = new RoundLifecycleWitnessCoordinator();
        this.polls = new PollChain(controls, capture);
        this.executor =
                new GuardedPlanExecutor(navigation.policy(), sink, foreground, abort, polls,
                        clock, verification);
    }

    /**
     * @param pipeline full-frame recognition pipeline; borrowed, never closed here
     * @param navigation selector start plus proven navigation graph; required
     * @param controls raw control-UI reader for the borrowed frame; required
     * @param capture fresh-frame capture for post-claim verification polls; required
     * @param targetExecutable exact foreground executable file name; required, non-blank
     * @param sink gameplay tap backend; required
     * @param foreground foreground-target guard; required
     * @param abort emergency abort signal; required
     * @param clock monotonic clock plus poll sleeper; required
     * @param verification poll bounds; required
     */
    public LiveSolveOrchestrator(FrameRecognitionPipeline pipeline, NavigationContext navigation,
            FrameControlReader controls, FrameCapture capture, String targetExecutable,
            GameInputSink sink, ForegroundTargetGuard foreground, AbortSignal abort,
            ExecutionClock clock, VerificationPolicy verification) {
        this(pipeline, navigation, controls, capture, targetExecutable, sink, foreground, abort,
                clock, verification,
                RecognitionConsensusTracker.DEFAULT_REQUIRED_CONSECUTIVE_FRAMES);
    }

    /** Current lifecycle state. */
    public RoundLifecycleState state() {
        return coordinator.state();
    }

    /** Round waiting for downstream acknowledgement; empty unless ROUND_READY. */
    public Optional<RecognitionIdentity> readyIdentity() {
        return coordinator.readyIdentity();
    }

    /** Last acknowledged round identity; empty until the first consumption. */
    public Optional<RecognitionIdentity> consumedIdentity() {
        return coordinator.consumedIdentity();
    }

    /** True when a consumed-round witness baseline is frozen. */
    public boolean isWitnessArmed() {
        return coordinator.isWitnessArmed();
    }

    /** Consensus output of the latest accepted frame; WAITING before the first one. */
    public LiveRecognitionStatus lastConsensusStatus() {
        return lastStatus;
    }

    /** Current input-execution ownership. */
    public ExecutionState executionState() {
        return executor.state();
    }

    /** True once {@link #close()} released the witness baseline. */
    public boolean closed() {
        return closed;
    }

    /**
     * Recognizes one captured frame and advances the guarded live orchestration.
     *
     * <p>The frame is borrowed and never closed here. When a pending ROUND_READY round
     * passes the same-frame preflight, this call blocks while the executor sends verified
     * taps, then returns the execution report.
     *
     * @param frame captured screen matching the pipeline layout; borrowed, not modified
     * @return lifecycle snapshot plus any pending-round plan, consumption and execution
     */
    public LiveFrameResult onFrame(Mat frame) {
        requireOpen();
        Objects.requireNonNull(frame, "frame");
        FrameRecognitionObservation observation;
        try {
            observation = pipeline.observe(frame);
        } catch (UnsupportedFrameSizeException e) {
            consensus.reset();
            LiveRecognitionStatus failure = LiveRecognitionStatus.unsupportedFrame(e.getMessage());
            lastStatus = failure;
            RoundLifecycleStatus lifecycle = coordinator.accept(failure);
            return new LiveFrameResult(lifecycle, null, null, null, null);
        }
        try (FrameRecognitionObservation owned = observation) {
            LiveRecognitionStatus status = consensus.accept(owned.decision());
            lastStatus = status;
            RoundLifecycleStatus lifecycle = coordinator.accept(status, owned);
            if (lifecycle.state() != RoundLifecycleState.ROUND_READY) {
                return new LiveFrameResult(lifecycle, null, null, null, null);
            }
            Optional<RecognitionIdentity> ready = lifecycle.ready();
            if (ready.isEmpty()) {
                return new LiveFrameResult(lifecycle, null, null, null,
                        "lifecycle reported ROUND_READY without a ready identity");
            }
            DryRunPlan checked = DryRunSolveOrchestrator.requireValid(
                    DryRunPlanner.plan(ready.get(), navigation), navigation.policy());
            if (!checked.executable()) {
                return new LiveFrameResult(lifecycle, checked, null, null, null);
            }
            if (executor.state() == ExecutionState.FAULTED
                    || executor.state() == ExecutionState.ABORTED) {
                return new LiveFrameResult(lifecycle, checked, null, null,
                        "execution latched " + executor.state()
                                + ": explicit reset or restart is required");
            }
            if (executor.state() != ExecutionState.IDLE) {
                return new LiveFrameResult(lifecycle, checked, null, null,
                        "executor is " + executor.state() + ": unexpected mid-round state");
            }
            PuzzleControlState preflight;
            try {
                preflight = controls.read(frame);
            } catch (RuntimeException e) {
                return new LiveFrameResult(lifecycle, checked, null, null,
                        "control read failed, round stays pending: " + e.getMessage());
            }
            polls.arm(preflight);
            try {
                RecognitionIdentity[] claimed = {null};
                ExecutionReport report = executor.execute(checked, targetExecutable, () -> {
                    claimed[0] = coordinator.consumeReadyRound(owned);
                });
                if (report.state() == ExecutionState.COMPLETED) {
                    executor.reset();
                }
                return new LiveFrameResult(lifecycle, checked, claimed[0], report, null);
            } finally {
                polls.disarm();
            }
        }
    }

    /**
     * Feeds a capture failure for one frame: ends the consensus streak through the
     * decision-free coordinator path. Never a plan, never an execution, never a reset.
     *
     * @param message capture backend diagnostic; required
     */
    public LiveFrameResult onCaptureError(String message) {
        requireOpen();
        Objects.requireNonNull(message, "message");
        consensus.reset();
        LiveRecognitionStatus failure = LiveRecognitionStatus.captureError(message);
        lastStatus = failure;
        RoundLifecycleStatus lifecycle = coordinator.accept(failure);
        return new LiveFrameResult(lifecycle, null, null, null, null);
    }

    /** Explicit full reset of consensus, lifecycle, witness baseline and execution state. */
    public void reset() {
        requireOpen();
        consensus.reset();
        coordinator.reset();
        executor.reset();
    }

    /** Releases the witness baseline. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            coordinator.close();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("LiveSolveOrchestrator is closed");
        }
    }

    /**
     * Verification poll chain: the first poll replays the same-frame preflight reading the
     * executor must verify before claiming, later polls capture and read fresh frames.
     */
    private static final class PollChain implements ControlStateSource {
        private final FrameControlReader controls;
        private final FrameCapture capture;
        private PuzzleControlState armed;

        PollChain(FrameControlReader controls, FrameCapture capture) {
            this.controls = controls;
            this.capture = capture;
        }

        void arm(PuzzleControlState preflight) {
            this.armed = Objects.requireNonNull(preflight, "preflight");
        }

        void disarm() {
            this.armed = null;
        }

        @Override
        public PuzzleControlState poll() {
            if (armed != null) {
                PuzzleControlState preflight = armed;
                armed = null;
                return preflight;
            }
            Mat fresh;
            try {
                fresh = capture.capture();
            } catch (RuntimeException e) {
                return PuzzleControlState.invalid("CAPTURE_ERROR polling: " + e.getMessage(),
                        new int[8], new int[8]);
            }
            try (Mat owned = fresh) {
                return controls.read(owned);
            } catch (RuntimeException e) {
                return PuzzleControlState.invalid("CONTROL_READ_ERROR polling: "
                        + e.getMessage(), new int[8], new int[8]);
            }
        }
    }
}
