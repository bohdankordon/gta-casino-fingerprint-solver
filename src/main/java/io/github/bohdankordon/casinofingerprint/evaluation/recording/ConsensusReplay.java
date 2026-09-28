package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Replays a chronological sequence of decisions through the UNMODIFIED production
 * {@link RecognitionConsensusTracker} and records every stable answer it produces.
 *
 * <p>Evaluation only: the tracker is the same class, with the same default requirement of three
 * consecutive identical recognized answers, that the live runtime uses. Nothing about consensus is
 * re-implemented or tuned here - the replay only observes it.
 *
 * <p>The production tracker keeps reporting a stable state for EVERY frame of an episode once the
 * streak reached the requirement, so a naive event log would repeat the same answer hundreds of
 * times. This replay records one event per stable EPISODE instead: the frame that first reached the
 * requirement, and again only after the answer was interrupted and re-established.
 */
public final class ConsensusReplay {
    private final RecognitionConsensusTracker tracker;
    private final List<StableEvent> stableEvents = new ArrayList<>();
    private long acceptedFrames;
    private long recognizedFrames;
    private long uncertainFrames;
    private boolean stableAnswerActive;
    private FingerprintId stableFingerprint;
    private List<Integer> stableSelection = List.of();

    /** Replay with the production default stability requirement. */
    public ConsensusReplay() {
        this(RecognitionConsensusTracker.DEFAULT_REQUIRED_CONSECUTIVE_FRAMES);
    }

    /** Replay with an explicit stability requirement. */
    public ConsensusReplay(int requiredConsecutiveFrames) {
        this.tracker = new RecognitionConsensusTracker(requiredConsecutiveFrames);
    }

    /**
     * One stable answer: the frame that completed the required streak and the answer it confirmed.
     */
    public record StableEvent(
            long frameIndex,
            double timestampMillis,
            int streak,
            FingerprintId fingerprint,
            List<Integer> candidates) {

        public StableEvent {
            Objects.requireNonNull(fingerprint, "fingerprint");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }

        /** True when this stable answer is exactly the annotated round answer. */
        public boolean matches(FingerprintId target, List<Integer> expectedCandidates) {
            return fingerprint == target && candidates.equals(expectedCandidates);
        }

        /** Timestamp in milliseconds, rounded to whole milliseconds for reporting. */
        public long timestampMs() {
            return Math.round(timestampMillis);
        }

        public String describe() {
            return String.format(Locale.ROOT, "%.3f s frame %d %s %s streak %d",
                    timestampMillis / 1000.0, frameIndex, fingerprint, candidates, streak);
        }
    }

    /**
     * Feeds one frame into the tracker.
     *
     * @return the stable answer this frame established, when this frame started a stable episode
     */
    public Optional<StableEvent> accept(long frameIndex, double timestampMillis,
            RecognitionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        acceptedFrames++;
        LiveRecognitionStatus status = tracker.accept(decision);
        if (decision.result().status() == RecognitionResult.Status.RECOGNIZED) {
            recognizedFrames++;
        } else {
            uncertainFrames++;
        }
        if (status.state() != LiveRecognitionState.STABLE_RECOGNIZED) {
            stableAnswerActive = false;
            stableFingerprint = null;
            stableSelection = List.of();
            return Optional.empty();
        }
        FingerprintId fingerprint = status.fingerprint().orElseThrow();
        List<Integer> selection = status.selectedCandidates();
        boolean continuing = stableAnswerActive && fingerprint == stableFingerprint
                && selection.equals(stableSelection);
        stableAnswerActive = true;
        stableFingerprint = fingerprint;
        stableSelection = selection;
        if (continuing) {
            return Optional.empty();
        }
        StableEvent event =
                new StableEvent(frameIndex, timestampMillis, status.streak(), fingerprint, selection);
        stableEvents.add(event);
        return Optional.of(event);
    }

    /** Ends the current streak, as the live runtime does for a frame that produced no decision. */
    public void reset() {
        tracker.reset();
        stableAnswerActive = false;
        stableFingerprint = null;
        stableSelection = List.of();
    }

    /** Every stable episode onset, in the order the replay produced them. */
    public List<StableEvent> stableEvents() {
        return List.copyOf(stableEvents);
    }

    /** Consecutive identical recognized frames required before an answer counts as stable. */
    public int requiredConsecutiveFrames() {
        return tracker.requiredConsecutiveFrames();
    }

    /** Frames fed into the replay. */
    public long acceptedFrames() {
        return acceptedFrames;
    }

    /** Frames that were recognized (not necessarily stable). */
    public long recognizedFrames() {
        return recognizedFrames;
    }

    /** Frames that stayed uncertain. */
    public long uncertainFrames() {
        return uncertainFrames;
    }
}
