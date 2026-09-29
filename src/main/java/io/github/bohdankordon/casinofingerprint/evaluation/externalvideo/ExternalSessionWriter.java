package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;

/**
 * Local artifact writer for one external-video validation session.
 *
 * <p>Session directory layout (always below ignored {@code target/}, never committed):
 *
 * <pre>
 * target/stage8a/&lt;session&gt;/
 *   screenshots/   full frames at prediction and final-four moments
 *   crops/         panel, target and candidate crops for every key moment
 *   rounds.csv     one benchmark row per observed round
 *   events.csv     changed-only event history for debugging
 *   report.txt     concise human report
 *   session.json   session metadata plus outcome counts
 * </pre>
 *
 * <p>Path safety: session names are restricted to {@code [A-Za-z0-9][A-Za-z0-9_-]{0,63}} so a
 * hostile or mistyped name can never traverse out of the session root. An existing session
 * directory is never overwritten: creation refuses with {@link FileAlreadyExistsException}.
 * This class writes text artifacts only; image bytes are written by the session runner
 * through the exposed {@code screenshotsDir}/{@code cropsDir} paths before borrowed Mats are
 * released.
 */
public final class ExternalSessionWriter {
    /** Session root below the project root (forward slashes). */
    public static final String SESSIONS_REL = "target/stage8a";
    public static final String SCREENSHOTS_DIR = "screenshots";
    public static final String CROPS_DIR = "crops";
    public static final String ROUNDS_CSV = "rounds.csv";
    public static final String EVENTS_CSV = "events.csv";
    public static final String REPORT_TXT = "report.txt";
    public static final String SESSION_JSON = "session.json";

    static final String ROUNDS_HEADER = "session,round,first_seen_ms,prediction_ms,"
            + "first_selection_ms,success_or_end_ms,predicted_fingerprint,predicted_candidates,"
            + "planned_order,navigation_moves,observed_success_candidates,attempts,result,"
            + "prediction_timing,transition_witness_used,notes";
    static final String EVENTS_HEADER =
            "timestamp_ms,round,event_type,focus,selected,prediction,detail";

    private ExternalSessionWriter() {
    }

    /**
     * Validates a session name for safe use as a single directory segment.
     *
     * @throws IllegalArgumentException for null, empty, over-long or unsafe names
     */
    public static String validateSessionName(String session) {
        if (session == null || session.isEmpty()) {
            throw new IllegalArgumentException("Session name must not be empty");
        }
        if (session.length() > 64) {
            throw new IllegalArgumentException(
                    "Session name must be at most 64 characters, got " + session.length());
        }
        if (!session.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Unsafe session name " + session
                    + ": use only letters, digits, dash and underscore, starting alphanumeric");
        }
        return session;
    }

    /** Session directory for {@code session} below {@code projectRoot}. */
    public static Path sessionDir(Path projectRoot, String session) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        return projectRoot.resolve(SESSIONS_REL).resolve(validateSessionName(session));
    }

    /**
     * Creates the session directory with {@code screenshots/} and {@code crops/}.
     *
     * @throws FileAlreadyExistsException when the session directory already exists: earlier
     *         validation sessions are never silently overwritten
     */
    public static Path createSessionDirs(Path projectRoot, String session) throws IOException {
        Path dir = sessionDir(projectRoot, session);
        if (Files.exists(dir)) {
            throw new FileAlreadyExistsException("Session " + session
                    + " already exists at " + dir + ": choose a new --session, never overwrite");
        }
        Files.createDirectories(dir.resolve(SCREENSHOTS_DIR));
        Files.createDirectories(dir.resolve(CROPS_DIR));
        return dir;
    }

    /** Screenshot directory of an existing session directory. */
    public static Path screenshotsDir(Path sessionDir) {
        return sessionDir.resolve(SCREENSHOTS_DIR);
    }

    /** Crop directory of an existing session directory. */
    public static Path cropsDir(Path sessionDir) {
        return sessionDir.resolve(CROPS_DIR);
    }

    /** Writes {@code rounds.csv} for the finalized rounds. */
    public static void writeRoundsCsv(Path sessionDir, String session,
            List<ExternalObservedRound> rounds) throws IOException {
        StringBuilder text = new StringBuilder(ROUNDS_HEADER).append('\n');
        for (ExternalObservedRound round : rounds) {
            text.append(csvCell(session)).append(',')
                    .append(round.roundNumber()).append(',')
                    .append(round.firstSeenMs()).append(',')
                    .append(cell(round.predictionMs())).append(',')
                    .append(cell(round.firstSelectionMs())).append(',')
                    .append(cell(round.endMs())).append(',')
                    .append(round.hasPrediction()
                            ? round.predictedIdentity().fingerprint().name() : "").append(',')
                    .append(csvCell(round.hasPrediction()
                            ? round.predictedIdentity().candidates().toString() : "")).append(',')
                    .append(csvCell(round.plannedOrder().toString())).append(',')
                    .append(round.navigationMoves()).append(',')
                    .append(csvCell(round.hasConfirmedSuccess()
                            ? round.observedSuccess().toString() : "")).append(',')
                    .append(round.attempts()).append(',')
                    .append(round.result().name()).append(',')
                    .append(round.timing().name()).append(',')
                    .append(round.transitionWitnessUsed()).append(',')
                    .append(csvCell(round.notes())).append('\n');
        }
        Files.writeString(sessionDir.resolve(ROUNDS_CSV), text.toString(),
                StandardCharsets.UTF_8);
    }

    /** Writes {@code events.csv} for the session event history. */
    public static void writeEventsCsv(Path sessionDir, List<ExternalSessionEvent> events)
            throws IOException {
        StringBuilder text = new StringBuilder(EVENTS_HEADER).append('\n');
        for (ExternalSessionEvent event : events) {
            text.append(event.timestampMs()).append(',')
                    .append(event.round()).append(',')
                    .append(event.type().name()).append(',')
                    .append(csvCell(event.focus())).append(',')
                    .append(csvCell(event.selected())).append(',')
                    .append(csvCell(event.prediction())).append(',')
                    .append(csvCell(event.detail())).append('\n');
        }
        Files.writeString(sessionDir.resolve(EVENTS_CSV), text.toString(),
                StandardCharsets.UTF_8);
    }

    /** Writes {@code report.txt} and returns its text. */
    public static String writeReportTxt(Path sessionDir, String session, String sourceLabel,
            List<ExternalObservedRound> rounds) throws IOException {
        String report = buildReport(session, sourceLabel, sessionDir, rounds);
        Files.writeString(sessionDir.resolve(REPORT_TXT), report, StandardCharsets.UTF_8);
        return report;
    }

    /** Writes {@code session.json} with session metadata plus outcome counts. */
    public static void writeSessionJson(Path sessionDir, String session, String sourceLabel,
            String geometry, long startedUtcMillis, long endedUtcMillis,
            List<ExternalObservedRound> rounds) throws IOException {
        Counts counts = Counts.of(rounds);
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"session\": \"").append(escapeJson(session)).append("\",\n");
        json.append("  \"sourceLabel\": \"").append(escapeJson(sourceLabel)).append("\",\n");
        json.append("  \"geometry\": \"").append(escapeJson(geometry)).append("\",\n");
        json.append("  \"startedUtcMillis\": ").append(startedUtcMillis).append(",\n");
        json.append("  \"endedUtcMillis\": ").append(endedUtcMillis).append(",\n");
        json.append("  \"observedRounds\": ").append(counts.observedRounds).append(",\n");
        json.append("  \"onTimePredictions\": ").append(counts.onTime).append(",\n");
        json.append("  \"matches\": ").append(counts.matches).append(",\n");
        json.append("  \"mismatches\": ").append(counts.mismatches).append(",\n");
        json.append("  \"noPrediction\": ").append(counts.noPrediction).append(",\n");
        json.append("  \"latePrediction\": ").append(counts.late).append(",\n");
        json.append("  \"needsReview\": ").append(counts.needsReview).append(",\n");
        json.append("  \"incomplete\": ").append(counts.incomplete).append(",\n");
        json.append("  \"orphanPredictions\": ").append(counts.orphans).append("\n");
        json.append("}\n");
        Files.writeString(sessionDir.resolve(SESSION_JSON), json.toString(),
                StandardCharsets.UTF_8);
    }

    /**
     * Builds the concise human session report.
     *
     * @param sessionDir used only to render output locations; the directory need not exist
     */
    public static String buildReport(String session, String sourceLabel, Path sessionDir,
            List<ExternalObservedRound> rounds) {
        Counts counts = Counts.of(rounds);
        Map<FingerprintId, Integer> distribution = predictedDistribution(rounds);
        String separator = System.lineSeparator();
        StringBuilder report = new StringBuilder();
        report.append("EXTERNAL VIDEO VALIDATION").append(separator);
        report.append("session ").append(session).append(separator);
        if (sourceLabel != null && !sourceLabel.isBlank()) {
            report.append("source ").append(sourceLabel).append(separator);
        }
        report.append(separator);
        if (counts.observedRounds == 0) {
            report.append("observed rounds = 0").append(separator);
            report.append("No fingerprint round was observed. This is NOT a solver failure by")
                    .append(separator);
            report.append("itself: check the source segment (wrong part of the video, cropped or")
                    .append(separator);
            report.append("edited source, facecam or overlay covering the panel, video not in true")
                    .append(separator);
            report.append("fullscreen, or an unsupported layout). See")
                    .append(separator);
            report.append("docs/external-video-validation.md for source requirements.")
                    .append(separator);
            report.append(separator);
        }
        report.append("observed rounds: ").append(counts.observedRounds).append(separator);
        report.append("on-time predictions: ").append(counts.onTime).append(separator);
        report.append("matches: ").append(counts.matches).append(separator);
        report.append("mismatches: ").append(counts.mismatches).append(separator);
        report.append("no prediction: ").append(counts.noPrediction).append(separator);
        report.append("late prediction: ").append(counts.late).append(separator);
        report.append("needs review: ").append(counts.needsReview).append(separator);
        report.append("incomplete: ").append(counts.incomplete).append(separator);
        report.append("orphan predictions: ").append(counts.orphans).append(separator);
        report.append(separator);
        report.append("predicted FP distribution:").append(separator);
        for (FingerprintId fingerprint : FingerprintId.values()) {
            report.append("    ").append(fingerprint.name().replace("_", "")).append(' ')
                    .append(distribution.getOrDefault(fingerprint, 0)).append(separator);
        }
        report.append("NOTE: predicted FP labels are solver predictions, not independent ground")
                .append(separator);
        report.append("truth. Count actual FP_1..FP_4 coverage only after a manual review of the")
                .append(separator);
        report.append("saved target screenshot against the canonical targets.").append(separator);
        for (ExternalObservedRound round : rounds) {
            report.append(separator);
            report.append("ROUND ").append(round.roundNumber()).append(separator);
            report.append("  prediction: ")
                    .append(round.hasPrediction() ? round.predictedIdentity().code() : "none")
                    .append(round.hasPrediction() ? " order=" + round.plannedOrder() : "")
                    .append(separator);
            report.append("  human success: ")
                    .append(round.hasConfirmedSuccess() ? round.observedSuccess().toString()
                            : "unconfirmed")
                    .append(separator);
            report.append("  result: ").append(round.result().name()).append(separator);
            report.append("  timing: ").append(round.timing().name()).append(separator);
            if (!round.notes().isBlank()) {
                report.append("  notes: ").append(singleLine(round.notes())).append(separator);
            }
        }
        report.append(separator);
        report.append("outputs:").append(separator);
        report.append("  ").append(sessionDir.resolve(REPORT_TXT)).append(separator);
        report.append("  ").append(sessionDir.resolve(ROUNDS_CSV)).append(separator);
        report.append("  ").append(sessionDir.resolve(EVENTS_CSV)).append(separator);
        report.append("  ").append(sessionDir.resolve(SCREENSHOTS_DIR + "/")).append(separator);
        return report.toString();
    }

    /** Outcome counts over the finalized rounds; orphans never count as observed rounds. */
    public static final class Counts {
        public final int observedRounds;
        public final int onTime;
        public final int matches;
        public final int mismatches;
        public final int noPrediction;
        public final int late;
        public final int needsReview;
        public final int incomplete;
        public final int orphans;

        private Counts(int observedRounds, int onTime, int matches, int mismatches,
                int noPrediction, int late, int needsReview, int incomplete, int orphans) {
            this.observedRounds = observedRounds;
            this.onTime = onTime;
            this.matches = matches;
            this.mismatches = mismatches;
            this.noPrediction = noPrediction;
            this.late = late;
            this.needsReview = needsReview;
            this.incomplete = incomplete;
            this.orphans = orphans;
        }

        /** Counts the finalized rounds. */
        public static Counts of(List<ExternalObservedRound> rounds) {
            int observed = 0;
            int onTime = 0;
            int matches = 0;
            int mismatches = 0;
            int noPrediction = 0;
            int late = 0;
            int needsReview = 0;
            int incomplete = 0;
            int orphans = 0;
            for (ExternalObservedRound round : rounds) {
                switch (round.result()) {
                    case ORPHAN_PREDICTION -> orphans++;
                    case MATCH -> {
                        observed++;
                        matches++;
                    }
                    case MISMATCH -> {
                        observed++;
                        mismatches++;
                    }
                    case NO_PREDICTION -> {
                        observed++;
                        noPrediction++;
                    }
                    case LATE_PREDICTION -> {
                        observed++;
                        late++;
                    }
                    case NEEDS_REVIEW_FINAL_EXIT, NEEDS_REVIEW_AMBIGUOUS -> {
                        observed++;
                        needsReview++;
                    }
                    case INCOMPLETE -> {
                        observed++;
                        incomplete++;
                    }
                }
                if (round.timing() == ExternalPredictionTiming.ON_TIME) {
                    onTime++;
                }
            }
            return new Counts(observed, onTime, matches, mismatches, noPrediction, late,
                    needsReview, incomplete, orphans);
        }
    }

    /** Predicted-fingerprint distribution over rounds with a prediction. */
    public static Map<FingerprintId, Integer> predictedDistribution(
            List<ExternalObservedRound> rounds) {
        Map<FingerprintId, Integer> distribution = new EnumMap<>(FingerprintId.class);
        for (ExternalObservedRound round : rounds) {
            if (round.hasPrediction()) {
                FingerprintId fingerprint = round.predictedIdentity().fingerprint();
                distribution.put(fingerprint,
                        distribution.getOrDefault(fingerprint, 0) + 1);
            }
        }
        return distribution;
    }

    /** Formats one failed attempt for CSV notes. */
    public static String formatAttempts(List<SortedSet<Integer>> failedAttempts) {
        List<String> rendered = new ArrayList<>(failedAttempts.size());
        for (SortedSet<Integer> attempt : failedAttempts) {
            rendered.add(attempt.toString());
        }
        return rendered.toString();
    }

    private static String cell(Long value) {
        return value == null ? "" : Long.toString(value);
    }

    private static String csvCell(String value) {
        Objects.requireNonNull(value, "value");
        if (value.contains(",") || value.contains("\"") || value.contains("\n")
                || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').trim().replaceAll(" +", " ");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
