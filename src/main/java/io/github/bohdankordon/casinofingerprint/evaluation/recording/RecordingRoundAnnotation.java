package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One human-verified round of the Stage 6 real gameplay recordings.
 *
 * <p>Evaluation only. The annotation records what the round shows (target fingerprint, the correct
 * candidate for each reference fragment, the sorted correct candidate set, and the approximate
 * active interval) and nothing about how it was recognized: it was established from the recordings
 * independently of the recognition system and production code never reads it.
 *
 * <p>The interval boundaries are approximate by roughly a few tenths of a second. They are NOT
 * frame-exact assertions, and every frame of the interval is benchmarked rather than one
 * hand-picked moment.
 *
 * @param sourceId generic recording id, for example {@code recording_1440p}
 * @param resolution {@code WIDTHxHEIGHT} of the source recording
 * @param hackId one-based hack index inside the source
 * @param roundId one-based round index inside the hack
 * @param startSeconds approximate start of the active round interval
 * @param endSeconds approximate end of the active round interval
 * @param target annotated target fingerprint
 * @param candidateByFragmentId correct candidate index per reference fragment {@code 1..4}
 * @param correctCandidatesSorted the four correct candidate indices in ascending order
 * @param containsWrongSelection true when the round contains a real wrong selection episode
 * @param notes free-form provenance and caveat text
 */
public record RecordingRoundAnnotation(
        String sourceId,
        String resolution,
        int hackId,
        int roundId,
        double startSeconds,
        double endSeconds,
        FingerprintId target,
        Map<Integer, Integer> candidateByFragmentId,
        List<Integer> correctCandidatesSorted,
        boolean containsWrongSelection,
        String notes) {

    public RecordingRoundAnnotation {
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        Objects.requireNonNull(resolution, "resolution");
        if (!resolution.matches("[0-9]{3,5}x[0-9]{3,5}")) {
            throw new IllegalArgumentException("resolution must look like 2560x1440, got " + resolution);
        }
        if (hackId < 1) {
            throw new IllegalArgumentException("hackId must be positive, got " + hackId);
        }
        if (roundId < 1) {
            throw new IllegalArgumentException("roundId must be positive, got " + roundId);
        }
        if (!Double.isFinite(startSeconds) || startSeconds < 0.0) {
            throw new IllegalArgumentException("startSeconds must be finite and non-negative");
        }
        if (!Double.isFinite(endSeconds) || endSeconds <= startSeconds) {
            throw new IllegalArgumentException("endSeconds must be finite and greater than startSeconds");
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(candidateByFragmentId, "candidateByFragmentId");
        Map<Integer, Integer> fragments = new LinkedHashMap<>();
        Set<Integer> candidates = new HashSet<>();
        for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
            Integer candidate = candidateByFragmentId.get(fragmentId);
            if (candidate == null) {
                throw new IllegalArgumentException(
                        "Round needs a candidate for reference fragment " + fragmentId);
            }
            if (candidate < 0 || candidate > 7) {
                throw new IllegalArgumentException("Candidate indices are 0..7, got " + candidate);
            }
            if (!candidates.add(candidate)) {
                throw new IllegalArgumentException(
                        "The four correct candidates must be unique, duplicate " + candidate);
            }
            fragments.put(fragmentId, candidate);
        }
        if (candidateByFragmentId.size() != 4) {
            throw new IllegalArgumentException(
                    "Exactly four reference fragments are annotated, got " + candidateByFragmentId.keySet());
        }
        List<Integer> expected = new ArrayList<>(candidates);
        Collections.sort(expected);
        List<Integer> sorted = List.copyOf(Objects.requireNonNull(
                correctCandidatesSorted, "correctCandidatesSorted"));
        if (!sorted.equals(expected)) {
            throw new IllegalArgumentException("Sorted correct set " + sorted
                    + " disagrees with the fragment mapping " + expected);
        }
        candidateByFragmentId = Map.copyOf(fragments);
        correctCandidatesSorted = sorted;
        notes = notes == null ? "" : notes;
    }

    /** Correct candidate index for reference fragment {@code fragmentId} ({@code 1..4}). */
    public int candidateForFragment(int fragmentId) {
        Integer candidate = candidateByFragmentId.get(fragmentId);
        if (candidate == null) {
            throw new IllegalArgumentException("No annotation for reference fragment " + fragmentId);
        }
        return candidate;
    }

    /** {@code H1R2}: hack and round index, unique inside one source. */
    public String scopeId() {
        return "H" + hackId + "R" + roundId;
    }

    /** Approximate interval as a window. */
    public TimeWindow window() {
        return new TimeWindow(startSeconds, endSeconds);
    }

    /** One-line description used by reports. */
    public String describe() {
        return String.format(Locale.ROOT, "%s %s %.3f-%.3f s %s F1->C%d F2->C%d F3->C%d F4->C%d %s",
                sourceId, scopeId(), startSeconds, endSeconds, target,
                candidateForFragment(1), candidateForFragment(2),
                candidateForFragment(3), candidateForFragment(4), correctCandidatesSorted);
    }
}
