package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Decision-level stability gate for the live runtime: a recognition is reported as stable only
 * after the required number of CONSECUTIVE frames produced the same answer.
 *
 * <p>This is decision consensus, not pixel stability: frames are compared through the two things
 * that matter downstream, the fingerprint and the sorted set of selected candidates. Evidence and
 * confidence may drift between frames without breaking a streak, because they measure the same
 * answer on slightly different pixels.
 *
 * <p>Reset rules are fail-closed:
 *
 * <ul>
 *   <li>an UNCERTAIN frame ends the streak;</li>
 *   <li>a frame that produced no decision at all (unsupported frame, capture error) ends the
 *       streak as well, because the required frames must be consecutive;</li>
 *   <li>a different recognized answer starts a new streak of one for that answer.</li>
 * </ul>
 *
 * <p>Decision consensus was chosen over a raw pixel-difference threshold because such a threshold
 * would need calibration data from real live gameplay that does not exist yet; consecutive
 * independent recognitions need no such constant.
 *
 * <p>State is deterministic and single-threaded: one tracker belongs to one capture loop.
 */
public final class RecognitionConsensusTracker {
    /** Conservative default stability requirement of the live runtime. */
    public static final int DEFAULT_REQUIRED_CONSECUTIVE_FRAMES = 3;

    private final int requiredConsecutiveFrames;
    private int streak;
    private FingerprintId streakFingerprint;
    private List<Integer> streakSelection = List.of();

    /**
     * @param requiredConsecutiveFrames consecutive identical recognized frames required before a
     *        result counts as stable; must be positive
     */
    public RecognitionConsensusTracker(int requiredConsecutiveFrames) {
        if (requiredConsecutiveFrames < 1) {
            throw new IllegalArgumentException(
                    "Required consecutive frames must be positive, got " + requiredConsecutiveFrames);
        }
        this.requiredConsecutiveFrames = requiredConsecutiveFrames;
    }

    /** Default tracker: {@value #DEFAULT_REQUIRED_CONSECUTIVE_FRAMES} consecutive frames. */
    public static RecognitionConsensusTracker conservative() {
        return new RecognitionConsensusTracker(DEFAULT_REQUIRED_CONSECUTIVE_FRAMES);
    }

    /** Consecutive identical recognized frames required before a result is stable. */
    public int requiredConsecutiveFrames() {
        return requiredConsecutiveFrames;
    }

    /** Current streak length of identical recognized answers; zero when there is none. */
    public int streak() {
        return streak;
    }

    /**
     * Feeds one frame decision into the streak and reports the resulting consensus state.
     *
     * @param decision conservative decision of one frame
     * @return UNCERTAIN, CANDIDATE_RECOGNITION or STABLE_RECOGNIZED
     */
    public LiveRecognitionStatus accept(RecognitionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (decision.result().status() != RecognitionResult.Status.RECOGNIZED) {
            reset();
            return LiveRecognitionStatus.uncertain(decision);
        }
        FingerprintId fingerprint = decision.result().fingerprintId()
                .orElseThrow(() -> new IllegalStateException(
                        "A RECOGNIZED decision must carry a fingerprint"));
        List<Integer> selection = sorted(decision.result().selectedCandidateIndices());
        if (streak > 0 && fingerprint == streakFingerprint && selection.equals(streakSelection)) {
            streak++;
        } else {
            streak = 1;
            streakFingerprint = fingerprint;
            streakSelection = selection;
        }
        return streak >= requiredConsecutiveFrames
                ? LiveRecognitionStatus.stable(decision, streak, requiredConsecutiveFrames)
                : LiveRecognitionStatus.candidate(decision, streak, requiredConsecutiveFrames);
    }

    /**
     * Ends the current streak. Used for frames that produced no decision at all, so unsupported
     * frames and capture errors cannot be hidden between recognized ones.
     */
    public void reset() {
        streak = 0;
        streakFingerprint = null;
        streakSelection = List.of();
    }

    private static List<Integer> sorted(List<Integer> selection) {
        List<Integer> copy = new ArrayList<>(selection);
        Collections.sort(copy);
        return List.copyOf(copy);
    }
}
