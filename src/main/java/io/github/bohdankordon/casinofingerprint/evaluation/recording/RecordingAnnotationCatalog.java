package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The committed Stage 6 annotation catalog: every human-verified round of every recording plus the
 * user-provided approximate hack windows.
 *
 * <p>Evaluation only. This catalog is the ground truth the benchmark compares recognition against;
 * production code never reads it and no recognition output was used to produce it. {@link #read}
 * validates the whole contract on load, so a hand-edited annotation file cannot silently drop a
 * round, reorder intervals or disagree with itself.
 */
public final class RecordingAnnotationCatalog {
    /** Repo-relative round annotation path (forward slashes). */
    public static final String ROUNDS_REL =
            "fixtures/gameplay/recordings/stage6-rounds.csv";
    /** Repo-relative hack window path (forward slashes). */
    public static final String HACK_WINDOWS_REL =
            "fixtures/gameplay/recordings/stage6-hack-windows.csv";
    /** Rounds the dataset is required to hold. */
    public static final int EXPECTED_ROUND_COUNT = 8;

    static final String ROUNDS_HEADER = "source_id,resolution,hack_id,round_id,start_seconds,"
            + "end_seconds,target_fingerprint,fragment_1_candidate,fragment_2_candidate,"
            + "fragment_3_candidate,fragment_4_candidate,correct_candidates,"
            + "contains_wrong_selection,notes";
    static final String HACK_WINDOWS_HEADER =
            "source_id,hack_id,start_seconds,end_seconds,notes";

    private final List<RecordingRoundAnnotation> rounds;
    private final List<RecordingHackWindow> hackWindows;

    private RecordingAnnotationCatalog(List<RecordingRoundAnnotation> rounds,
            List<RecordingHackWindow> hackWindows) {
        this.rounds = List.copyOf(rounds);
        this.hackWindows = List.copyOf(hackWindows);
    }

    /** Reads both annotation files and validates every dataset invariant. */
    public static RecordingAnnotationCatalog read(Path roundsCsv, Path hackWindowsCsv)
            throws IOException {
        List<RecordingRoundAnnotation> rounds = readRounds(roundsCsv);
        List<RecordingHackWindow> hacks = readHackWindows(hackWindowsCsv);
        RecordingAnnotationCatalog catalog = new RecordingAnnotationCatalog(rounds, hacks);
        catalog.validate();
        return catalog;
    }

    /** Reads the committed annotations below {@code projectRoot}. */
    public static RecordingAnnotationCatalog readCommitted(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        return read(projectRoot.resolve(ROUNDS_REL), projectRoot.resolve(HACK_WINDOWS_REL));
    }

    private static List<RecordingRoundAnnotation> readRounds(Path csv) throws IOException {
        CsvTable table = CsvTable.read(csv, ROUNDS_HEADER);
        List<RecordingRoundAnnotation> rounds = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            Map<Integer, Integer> fragments = new LinkedHashMap<>();
            for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
                fragments.put(fragmentId,
                        row.integer(6 + fragmentId, "fragment_" + fragmentId + "_candidate"));
            }
            rounds.add(new RecordingRoundAnnotation(
                    row.text(0, "source_id"),
                    row.text(1, "resolution"),
                    row.integer(2, "hack_id"),
                    row.integer(3, "round_id"),
                    row.decimal(4, "start_seconds"),
                    row.decimal(5, "end_seconds"),
                    fingerprint(row.text(6, "target_fingerprint"), table.source(), row.lineNumber()),
                    fragments,
                    candidateSet(row.text(11, "correct_candidates"), table.source(), row.lineNumber()),
                    row.flag(12, "contains_wrong_selection"),
                    row.optional(13)));
        }
        return rounds;
    }

    private static List<RecordingHackWindow> readHackWindows(Path csv) throws IOException {
        CsvTable table = CsvTable.read(csv, HACK_WINDOWS_HEADER);
        List<RecordingHackWindow> windows = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            windows.add(new RecordingHackWindow(
                    row.text(0, "source_id"),
                    row.integer(1, "hack_id"),
                    row.decimal(2, "start_seconds"),
                    row.decimal(3, "end_seconds"),
                    row.optional(4)));
        }
        return windows;
    }

    private static FingerprintId fingerprint(String value, Path csv, int lineNumber) {
        try {
            return FingerprintId.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("CSV " + csv + ":" + lineNumber
                    + " unknown target_fingerprint: " + value);
        }
    }

    private static List<Integer> candidateSet(String value, Path csv, int lineNumber) {
        List<Integer> candidates = new ArrayList<>();
        for (String part : value.split(";", -1)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                candidates.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("CSV " + csv + ":" + lineNumber
                        + " invalid correct_candidates entry: " + trimmed);
            }
        }
        return candidates;
    }

    /** Every annotated round in file order. */
    public List<RecordingRoundAnnotation> rounds() {
        return rounds;
    }

    /** Every user-provided hack window in file order. */
    public List<RecordingHackWindow> hackWindows() {
        return hackWindows;
    }

    /** Rounds of one source, in file order. */
    public List<RecordingRoundAnnotation> roundsFor(String sourceId) {
        return rounds.stream().filter(r -> r.sourceId().equals(sourceId)).toList();
    }

    /** Rounds of one hack, in file order. */
    public List<RecordingRoundAnnotation> roundsFor(String sourceId, int hackId) {
        return rounds.stream()
                .filter(r -> r.sourceId().equals(sourceId) && r.hackId() == hackId)
                .toList();
    }

    /** Hack windows of one source, in file order. */
    public List<RecordingHackWindow> hackWindowsFor(String sourceId) {
        return hackWindows.stream().filter(w -> w.sourceId().equals(sourceId)).toList();
    }

    /**
     * Strict negative windows of one source: every hack window grown by {@code paddingSeconds},
     * merged where the padding makes them touch, in ascending order. Everything outside these
     * windows is strict negative gameplay.
     */
    public List<TimeWindow> negativeWindows(String sourceId, double paddingSeconds) {
        List<TimeWindow> padded = new ArrayList<>();
        for (RecordingHackWindow window : hackWindowsFor(sourceId)) {
            padded.add(new TimeWindow(window.startSeconds(), window.endSeconds())
                    .padded(paddingSeconds));
        }
        padded.sort(Comparator.comparingDouble(TimeWindow::startSeconds));
        List<TimeWindow> merged = new ArrayList<>();
        for (TimeWindow window : padded) {
            if (!merged.isEmpty() && merged.get(merged.size() - 1).overlaps(window)) {
                TimeWindow previous = merged.remove(merged.size() - 1);
                merged.add(new TimeWindow(previous.startSeconds(),
                        Math.max(previous.endSeconds(), window.endSeconds())));
            } else {
                merged.add(window);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * Validates the dataset contract: exactly {@value #EXPECTED_ROUND_COUNT} rounds, one entry per
     * source/hack/round, intervals ordered and non-overlapping inside a source, and a hack window
     * for every annotated hack.
     */
    public void validate() {
        if (rounds.size() != EXPECTED_ROUND_COUNT) {
            throw new IllegalArgumentException("Annotation must hold exactly "
                    + EXPECTED_ROUND_COUNT + " rounds, got " + rounds.size());
        }
        if (hackWindows.isEmpty()) {
            throw new IllegalArgumentException("Annotation must hold the hack windows");
        }
        Set<String> scopeIds = new HashSet<>();
        for (RecordingRoundAnnotation round : rounds) {
            if (!scopeIds.add(round.sourceId() + " " + round.scopeId())) {
                throw new IllegalArgumentException("Duplicate round scope: "
                        + round.sourceId() + " " + round.scopeId());
            }
            boolean covered = hackWindows.stream().anyMatch(w ->
                    w.sourceId().equals(round.sourceId()) && w.hackId() == round.hackId());
            if (!covered) {
                throw new IllegalArgumentException("Round " + round.sourceId() + " "
                        + round.scopeId() + " has no hack window");
            }
        }
        for (String sourceId : distinct(rounds.stream().map(RecordingRoundAnnotation::sourceId).toList())) {
            List<RecordingRoundAnnotation> sourceRounds = roundsFor(sourceId);
            String resolution = sourceRounds.get(0).resolution();
            for (RecordingRoundAnnotation round : sourceRounds) {
                if (!round.resolution().equals(resolution)) {
                    throw new IllegalArgumentException("Source " + sourceId
                            + " mixes resolutions " + resolution + " and " + round.resolution());
                }
            }
            requireOrdered(sourceId, sourceRounds.stream()
                    .map(r -> new TimeWindow(r.startSeconds(), r.endSeconds())).toList());
        }
        for (String sourceId : distinct(hackWindows.stream()
                .map(RecordingHackWindow::sourceId).toList())) {
            List<RecordingHackWindow> windows = hackWindowsFor(sourceId);
            requireOrdered(sourceId + " hack windows", windows.stream()
                    .map(w -> new TimeWindow(w.startSeconds(), w.endSeconds())).toList());
            Set<Integer> hackIds = new HashSet<>();
            for (RecordingHackWindow window : windows) {
                if (!hackIds.add(window.hackId())) {
                    throw new IllegalArgumentException("Source " + sourceId
                            + " declares hack " + window.hackId() + " twice");
                }
            }
        }
    }

    /** Source ids referenced by the annotation, in first-seen order. */
    public List<String> sourceIds() {
        return distinct(rounds.stream().map(RecordingRoundAnnotation::sourceId).toList());
    }

    /** Resolution of {@code sourceId} according to the annotation. */
    public String resolutionOf(String sourceId) {
        return roundsFor(sourceId).get(0).resolution();
    }

    /** Rounds whose interval leaves their own hack window, as report-ready notes. */
    public List<String> hackWindowCoverageNotes() {
        List<String> notes = new ArrayList<>();
        for (RecordingRoundAnnotation round : rounds) {
            for (RecordingHackWindow window : hackWindowsFor(round.sourceId())) {
                if (window.hackId() != round.hackId()) {
                    continue;
                }
                if (round.startSeconds() < window.startSeconds()) {
                    notes.add(String.format(Locale.ROOT,
                            "%s %s starts %.3f s before its approximate hack window",
                            round.sourceId(), round.scopeId(),
                            window.startSeconds() - round.startSeconds()));
                }
                if (round.endSeconds() > window.endSeconds()) {
                    notes.add(String.format(Locale.ROOT,
                            "%s %s ends %.3f s after its approximate hack window",
                            round.sourceId(), round.scopeId(),
                            round.endSeconds() - window.endSeconds()));
                }
            }
        }
        return List.copyOf(notes);
    }

    /** Rounds per annotated fingerprint, in {@link FingerprintId} declaration order. */
    public Map<FingerprintId, Integer> coverageByFingerprint() {
        Map<FingerprintId, Integer> coverage = new LinkedHashMap<>();
        for (FingerprintId id : FingerprintId.values()) {
            coverage.put(id, 0);
        }
        for (RecordingRoundAnnotation round : rounds) {
            coverage.merge(round.target(), 1, Integer::sum);
        }
        return coverage;
    }

    private static void requireOrdered(String label, List<TimeWindow> windows) {
        for (int i = 1; i < windows.size(); i++) {
            TimeWindow previous = windows.get(i - 1);
            TimeWindow current = windows.get(i);
            if (current.startSeconds() < previous.startSeconds()) {
                throw new IllegalArgumentException(label + " intervals are not ordered: "
                        + previous + " then " + current);
            }
            if (current.startSeconds() <= previous.endSeconds()) {
                throw new IllegalArgumentException(label + " intervals overlap: "
                        + previous + " and " + current);
            }
        }
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(values));
    }
}
