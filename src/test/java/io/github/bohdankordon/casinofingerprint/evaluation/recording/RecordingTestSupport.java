package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared Stage 6 evaluation test material.
 *
 * <p>Everything here is deterministic and needs no recording: synthetic decisions are built through
 * the production Stage 4 decision path, and annotations are built in memory or in a temporary file.
 * The private recordings are never a test resource.
 */
final class RecordingTestSupport {
    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath();

    private RecordingTestSupport() {
    }

    static RecordingAnnotationCatalog committedCatalog() throws IOException {
        return RecordingAnnotationCatalog.readCommitted(PROJECT_ROOT);
    }

    static List<RecordingSource> committedSources() throws IOException {
        return RecordingSourceCatalog.readCommitted(PROJECT_ROOT);
    }

    static RecordingRoundAnnotation round(String sourceId, String resolution, int hackId,
            int roundId, double startSeconds, double endSeconds, FingerprintId target,
            int fragment1, int fragment2, int fragment3, int fragment4, boolean wrongSelection) {
        Map<Integer, Integer> fragments = new LinkedHashMap<>();
        fragments.put(1, fragment1);
        fragments.put(2, fragment2);
        fragments.put(3, fragment3);
        fragments.put(4, fragment4);
        List<Integer> sorted = List.of(fragment1, fragment2, fragment3, fragment4).stream()
                .sorted().toList();
        return new RecordingRoundAnnotation(sourceId, resolution, hackId, roundId, startSeconds,
                endSeconds, target, fragments, sorted, wrongSelection, "synthetic test round");
    }

    /** Strong recognized decision for the given answer, built through the production engine. */
    static RecognitionDecision recognized(FingerprintId fingerprint, List<Integer> candidates) {
        return Stage5TestSupport.syntheticRecognized(fingerprint, candidates);
    }

    /** Weak decision that stays uncertain, built through the production engine. */
    static RecognitionDecision uncertain() {
        return Stage5TestSupport.syntheticUncertain();
    }
}
