package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Derived identity of a recognition answer: the target fingerprint PLUS the sorted selected
 * candidate set.
 *
 * <p>Evaluation only. A different fingerprint alone is not a different lifecycle answer, and a
 * different candidate set on the same fingerprint is not the same answer either: this record is
 * the only identity the transition analysis compares. Candidate order never matters, so the set is
 * sorted on construction and two identities built from the same candidates in a different order
 * are equal.
 *
 * <p>The record is an immutable plain value: it holds no {@code Mat}, no decision and no native
 * memory.
 */
public record AnswerIdentity(FingerprintId fingerprint, List<Integer> candidates)
        implements Comparable<AnswerIdentity> {

    public AnswerIdentity {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(candidates, "candidates");
        List<Integer> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        candidates = List.copyOf(sorted);
    }

    /** Identity of the answer {@code fingerprint} with the given (any order) candidate selection. */
    public static AnswerIdentity of(FingerprintId fingerprint, List<Integer> candidates) {
        return new AnswerIdentity(fingerprint, candidates);
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
    public int compareTo(AnswerIdentity other) {
        Objects.requireNonNull(other, "other");
        int byFingerprint = fingerprint.compareTo(other.fingerprint);
        if (byFingerprint != 0) {
            return byFingerprint;
        }
        int shared = Math.min(candidates.size(), other.candidates.size());
        for (int index = 0; index < shared; index++) {
            int byCandidate = Integer.compare(candidates.get(index), other.candidates.get(index));
            if (byCandidate != 0) {
                return byCandidate;
            }
        }
        return Integer.compare(candidates.size(), other.candidates.size());
    }

    @Override
    public String toString() {
        return describe();
    }
}
