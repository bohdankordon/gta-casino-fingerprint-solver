package io.github.bohdankordon.casinofingerprint.matching.evaluation;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human-verified ground truth for one gameplay fixture, read from
 * {@code fixtures/gameplay/annotations/representative-2560x1440.csv}.
 *
 * <p>Evaluation only. The annotation is used to measure matcher quality in tests and diagnostics;
 * production matcher code never reads it, and no score is ever derived from it. Ground truth was
 * assigned from fingerprint ridge geometry, independently of the UI selection/brightness state.
 */
public record FixtureAnnotation(FingerprintId target, Map<Integer, Integer> candidateByFragmentId) {
    /** Repo-relative annotation path for the representative fixture (forward slashes). */
    public static final String REPRESENTATIVE_REL =
            "fixtures/gameplay/annotations/representative-2560x1440.csv";

    private static final String HEADER = "record_type,fingerprint_id,fragment_id,candidate_index";

    public FixtureAnnotation {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        candidateByFragmentId = Map.copyOf(candidateByFragmentId);
        for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
            Integer candidate = candidateByFragmentId.get(fragmentId);
            if (candidate == null) {
                throw new IllegalArgumentException("Annotation is missing reference fragment " + fragmentId);
            }
            if (candidate < 0 || candidate > 7) {
                throw new IllegalArgumentException(
                        "Candidate indices are 0..7, got " + candidate + " for fragment " + fragmentId);
            }
        }
    }

    /** Candidate index annotated as the correct match for {@code fragmentId} (1..4). */
    public int candidateFor(int fragmentId) {
        Integer candidate = candidateByFragmentId.get(fragmentId);
        if (candidate == null) {
            throw new IllegalArgumentException("No annotation for reference fragment " + fragmentId);
        }
        return candidate;
    }

    /** Reads and validates an annotation CSV. */
    public static FixtureAnnotation read(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Annotation is empty: " + csv);
        }
        if (!lines.get(0).trim().equals(HEADER)) {
            throw new IllegalArgumentException(
                    "Unexpected annotation header in " + csv + ": " + lines.get(0).trim());
        }
        FingerprintId target = null;
        Map<Integer, Integer> candidates = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                        "Annotation " + csv + ":" + (i + 1) + " needs 4 columns, got " + parts.length);
            }
            for (int column = 0; column < parts.length; column++) {
                parts[column] = parts[column].trim();
            }
            FingerprintId fingerprintId;
            try {
                fingerprintId = FingerprintId.valueOf(parts[1]);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Annotation " + csv + ":" + (i + 1)
                        + " unknown fingerprint_id: " + parts[1]);
            }
            switch (parts[0]) {
                case "target" -> {
                    if (!parts[2].isEmpty() || !parts[3].isEmpty()) {
                        throw new IllegalArgumentException("Annotation " + csv + ":" + (i + 1)
                                + " target records must leave fragment_id and candidate_index empty");
                    }
                    if (target != null) {
                        throw new IllegalArgumentException(
                                "Annotation " + csv + " declares more than one target");
                    }
                    target = fingerprintId;
                }
                case "fragment" -> {
                    int fragmentId = parseInt(parts[2], csv, i + 1, "fragment_id");
                    int candidateIndex = parseInt(parts[3], csv, i + 1, "candidate_index");
                    if (fragmentId < 1 || fragmentId > 4) {
                        throw new IllegalArgumentException("Annotation " + csv + ":" + (i + 1)
                                + " fragment_id must be 1..4, got " + fragmentId);
                    }
                    if (candidateIndex < 0 || candidateIndex > 7) {
                        throw new IllegalArgumentException("Annotation " + csv + ":" + (i + 1)
                                + " candidate_index must be 0..7, got " + candidateIndex);
                    }
                    if (candidates.put(fragmentId, candidateIndex) != null) {
                        throw new IllegalArgumentException("Annotation " + csv + ":" + (i + 1)
                                + " duplicate fragment_id " + fragmentId);
                    }
                    if (target != null && fingerprintId != target) {
                        throw new IllegalArgumentException("Annotation " + csv + ":" + (i + 1)
                                + " fingerprint_id disagrees with the target record");
                    }
                }
                default -> throw new IllegalArgumentException(
                        "Annotation " + csv + ":" + (i + 1) + " unknown record_type: " + parts[0]);
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("Annotation " + csv + " has no target record");
        }
        if (candidates.size() != 4) {
            throw new IllegalArgumentException(
                    "Annotation " + csv + " needs fragments 1..4, got " + candidates.keySet());
        }
        return new FixtureAnnotation(target, candidates);
    }

    private static int parseInt(String value, Path csv, int lineNumber, String column) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Annotation " + csv + ":" + lineNumber + " invalid " + column + ": " + value);
        }
    }
}
