package io.github.bohdankordon.casinofingerprint.orchestration;

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
 * Production dry-run solve orchestration: the first end-to-end solver layer, with zero
 * gameplay input.
 *
 * <pre>
 * captured frame
 *     -> FrameRecognitionPipeline.observe (SAME-frame owned observation)
 *     -> RecognitionConsensusTracker
 *     -> RoundLifecycleWitnessCoordinator
 *     -> on NEW_ROUND_READY: DryRunPlanner dry-run plan plus report
 *     -> lifecycle consume plus witness baseline re-arm (executable plans only)
 * </pre>
 *
 * <p>This class owns no recognition, consensus, lifecycle or witness logic: it only calls the
 * existing production pieces in the mandated same-frame order and adds planning plus
 * ownership bookkeeping. It creates no second lifecycle state machine and changes no
 * consensus, lifecycle or witness semantics.
 *
 * <p>Consumption semantics: once a ROUND_READY plan has been successfully BUILT and accepted
 * as executable, the round is lifecycle-consumed through the coordinator with the SAME
 * observation, so a replay can continue to the next round and the witness baseline re-arms on
 * the consumed content. Consumed means orchestrator ownership of the dry-run round, NOT GTA
 * input and NOT puzzle success. A BLOCKED plan is never consumed: the round stays pending
 * and visible instead of being silently skipped. No cross-frame consumption ever happens:
 * the observation accepted by the coordinator is the observation consumed.
 *
 * <p>Capture problems and unsupported frames feed the existing decision-free coordinator path:
 * they end the consensus streak, never produce a plan, never end the hack and never clear
 * lifecycle or witness state. There is no clock, no sleep, no duration, no input and no
 * automation anywhere in this class. State is deterministic and single-threaded.
 */
public final class DryRunSolveOrchestrator implements AutoCloseable {
    private final FrameRecognitionPipeline pipeline;
    private final RecognitionConsensusTracker consensus;
    private final RoundLifecycleWitnessCoordinator coordinator;
    private final NavigationContext navigation;
    private boolean closed;
    private LiveRecognitionStatus lastStatus = LiveRecognitionStatus.waiting();

    /**
     * @param pipeline full-frame recognition pipeline; borrowed, never closed here
     * @param navigation selector start plus proven navigation graph; required
     * @param requiredConsecutiveFrames consecutive identical answers before stability; positive
     */
    public DryRunSolveOrchestrator(FrameRecognitionPipeline pipeline, NavigationContext navigation,
            int requiredConsecutiveFrames) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.consensus = new RecognitionConsensusTracker(requiredConsecutiveFrames);
        this.coordinator = new RoundLifecycleWitnessCoordinator();
    }

    /**
     * @param pipeline full-frame recognition pipeline; borrowed, never closed here
     * @param navigation selector start plus proven navigation graph; required
     */
    public DryRunSolveOrchestrator(FrameRecognitionPipeline pipeline, NavigationContext navigation) {
        this(pipeline, navigation, RecognitionConsensusTracker.DEFAULT_REQUIRED_CONSECUTIVE_FRAMES);
    }

    /** Current lifecycle state. */
    public RoundLifecycleState state() {
        return coordinator.state();
    }

    /** Round waiting for downstream acknowledgement; empty unless ROUND_READY. */
    public Optional<RecognitionIdentity> readyIdentity() {
        return coordinator.readyIdentity();
    }

    /** Last acknowledged dry-run round; empty until the first consumption. */
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

    /** True once {@link #close()} released the witness baseline. */
    public boolean closed() {
        return closed;
    }

    /**
     * Recognizes one captured frame and advances the dry-run orchestration.
     *
     * <p>The frame is borrowed and never closed here. The owned observation is always
     * closed before returning, after any executable plan consumed it through the
     * coordinator with the same reference.
     *
     * @param frame captured screen matching the pipeline layout; borrowed, not modified
     * @return lifecycle snapshot plus any NEW_ROUND_READY plan and consumption outcome
     */
    public DryRunFrameResult onFrame(Mat frame) {
        requireOpen();
        Objects.requireNonNull(frame, "frame");
        FrameRecognitionObservation observation;
        try {
            observation = pipeline.observe(frame);
        } catch (UnsupportedFrameSizeException e) {
            consensus.reset();
            LiveRecognitionStatus failure =
                    LiveRecognitionStatus.unsupportedFrame(e.getMessage());
            lastStatus = failure;
            RoundLifecycleStatus lifecycle = coordinator.accept(failure);
            return new DryRunFrameResult(lifecycle, null, null, null);
        }
        try (FrameRecognitionObservation owned = observation) {
            LiveRecognitionStatus status = consensus.accept(owned.decision());
            lastStatus = status;
            RoundLifecycleStatus lifecycle = coordinator.accept(status, owned);
            if (!lifecycle.newRoundReady()) {
                return new DryRunFrameResult(lifecycle, null, null, null);
            }
            Optional<RecognitionIdentity> ready = lifecycle.ready();
            if (ready.isEmpty()) {
                return new DryRunFrameResult(lifecycle, null, null,
                        "lifecycle reported NEW_ROUND_READY without a ready identity");
            }
            DryRunPlan plan = DryRunPlanner.plan(ready.get(), navigation);
            if (!plan.executable()) {
                return new DryRunFrameResult(lifecycle, plan, null, null);
            }
            try {
                RecognitionIdentity consumed = coordinator.consumeReadyRound(owned);
                return new DryRunFrameResult(lifecycle, plan, consumed, null);
            } catch (RuntimeException e) {
                return new DryRunFrameResult(lifecycle, plan, null,
                        "dry-run consume failed: " + e.getMessage());
            }
        }
    }

    /**
     * Feeds a capture failure for one frame: ends the consensus streak through the
     * decision-free coordinator path. Never a plan, never a hack ending, never a reset.
     *
     * @param message capture backend diagnostic; required
     */
    public DryRunFrameResult onCaptureError(String message) {
        requireOpen();
        Objects.requireNonNull(message, "message");
        consensus.reset();
        LiveRecognitionStatus failure = LiveRecognitionStatus.captureError(message);
        lastStatus = failure;
        RoundLifecycleStatus lifecycle = coordinator.accept(failure);
        return new DryRunFrameResult(lifecycle, null, null, null);
    }

    /** Explicit full reset of consensus streak, lifecycle, witness baseline and references. */
    public void reset() {
        requireOpen();
        consensus.reset();
        coordinator.reset();
        lastStatus = LiveRecognitionStatus.waiting();
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
            throw new IllegalStateException("DryRunSolveOrchestrator is closed");
        }
    }
}
