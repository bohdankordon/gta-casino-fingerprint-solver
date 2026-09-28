package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Production identity of one stable recognition answer: the target fingerprint PLUS the sorted
 * selected candidate set.
 *
 * <p>This is the only answer identity the {@link RoundLifecycleTracker} compares. A different
 * fingerprint alone is a different identity, and so is a different candidate set on the same
 * fingerprint: Stage 6C.1A measured real transitions that reuse the same candidate set with a
 * different target, so the fingerprint alone is not the contract.
 *
 * <p>The value is a small immutable plain object: it holds no {@code RecognitionDecision}, no
 * {@code Mat}, no evidence, no timestamps and no native resources. The candidate list is
 * defensively copied and sorted canonically, so equality never depends on the incoming order.
 * A production recognized answer always selects exactly four distinct candidates {@code 0..7},
 * and this type enforces that.
 */
public final class RecognitionIdentity implements Comparable<RecognitionIdentity> {
    private final FingerprintId fingerprint;
    private final List<Integer> candidates;

    /**
     * @param fingerprint recognized target; required
     * @param candidates selected candidate indices in any order; exactly four distinct values
     *        {@code 0..7} are required
     */
    public RecognitionIdentity(FingerprintId fingerprint, List<Integer> candidates) {
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.size() != 4) {
            throw new IllegalArgumentException(
                    "A recognition identity needs exactly four candidates, got "
                            + candidates.size());
        }
        Set<Integer> distinct = new HashSet<>();
        for (Integer candidate : candidates) {
            if (candidate == null || candidate < 0 || candidate > 7) {
                throw new IllegalArgumentException(
                        "Candidate indices must be between 0 and 7, got " + candidate);
            }
            if (!distinct.add(candidate)) {
                throw new IllegalArgumentException(
                        "Candidate indices must be distinct, duplicate " + candidate);
            }
        }
        List<Integer> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        this.candidates = List.copyOf(sorted);
    }

    /** Identity of {@code fingerprint} with the given (any order) candidate selection. */
    public static RecognitionIdentity of(FingerprintId fingerprint, List<Integer> candidates) {
        return new RecognitionIdentity(fingerprint, candidates);
    }

    /** Recognized target fingerprint. */
    public FingerprintId fingerprint() {
        return fingerprint;
    }

    /** Selected candidate indices, sorted ascending; unmodifiable. */
    public List<Integer> candidates() {
        return candidates;
    }

    /** {@code FP_4[1;4;5;6]}: compact and CSV safe (no comma, no space). */
    public String code() {
        return fingerprint.name() + "[" + candidates.stream().map(String::valueOf)
                .collect(Collectors.joining(";")) + "]";
    }

    /** {@code FP_4 [1, 4, 5, 6]}: report rendering. */
    public String describe() {
        return String.format(Locale.ROOT, "%s %s", fingerprint, candidates);
    }

    @Override
    public int compareTo(RecognitionIdentity other) {
        Objects.requireNonNull(other, "other");
        int byFingerprint = fingerprint.compareTo(other.fingerprint);
        if (byFingerprint != 0) {
            return byFingerprint;
        }
        for (int index = 0; index < candidates.size(); index++) {
            int byCandidate =
                    Integer.compare(candidates.get(index), other.candidates.get(index));
            if (byCandidate != 0) {
                return byCandidate;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RecognitionIdentity that)) {
            return false;
        }
        return fingerprint == that.fingerprint && candidates.equals(that.candidates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fingerprint, candidates);
    }

    @Override
    public String toString() {
        return describe();
    }
}

