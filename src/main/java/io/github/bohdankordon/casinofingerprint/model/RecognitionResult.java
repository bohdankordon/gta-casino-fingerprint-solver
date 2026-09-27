package io.github.bohdankordon.casinofingerprint.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable outcome of recognizing one eight-candidate puzzle. Candidate indices are zero-based. */
public final class RecognitionResult {
    public enum Status {
        RECOGNIZED,
        UNCERTAIN,
        FAILED
    }

    private final Status status;
    private final FingerprintId fingerprintId;
    private final List<Integer> selectedCandidateIndices;
    private final double confidence;

    private RecognitionResult(Status status, FingerprintId fingerprintId,
                              List<Integer> selectedCandidateIndices, double confidence) {
        this.status = status;
        this.fingerprintId = fingerprintId;
        this.selectedCandidateIndices = selectedCandidateIndices;
        this.confidence = confidence;
    }

    public static RecognitionResult recognized(FingerprintId fingerprintId,
                                               List<Integer> selectedCandidateIndices,
                                               double confidence) {
        Objects.requireNonNull(fingerprintId, "fingerprintId");
        Objects.requireNonNull(selectedCandidateIndices, "selectedCandidateIndices");
        validateConfidence(confidence);
        if (selectedCandidateIndices.size() != 4) {
            throw new IllegalArgumentException("A recognized puzzle requires exactly four candidates");
        }

        Set<Integer> distinct = new HashSet<>();
        for (Integer index : selectedCandidateIndices) {
            if (index == null || index < 0 || index >= 8) {
                throw new IllegalArgumentException("Candidate indices must be between 0 and 7");
            }
            if (!distinct.add(index)) {
                throw new IllegalArgumentException("Candidate indices must be distinct");
            }
        }

        return new RecognitionResult(Status.RECOGNIZED, fingerprintId,
                List.copyOf(selectedCandidateIndices), confidence);
    }

    public static RecognitionResult uncertain(double confidence) {
        validateConfidence(confidence);
        return new RecognitionResult(Status.UNCERTAIN, null, List.of(), confidence);
    }

    public static RecognitionResult failed() {
        return new RecognitionResult(Status.FAILED, null, List.of(), 0.0);
    }

    private static void validateConfidence(double confidence) {
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be finite and between 0 and 1");
        }
    }

    public Status status() {
        return status;
    }

    public Optional<FingerprintId> fingerprintId() {
        return Optional.ofNullable(fingerprintId);
    }

    public List<Integer> selectedCandidateIndices() {
        return selectedCandidateIndices;
    }

    public double confidence() {
        return confidence;
    }
}
