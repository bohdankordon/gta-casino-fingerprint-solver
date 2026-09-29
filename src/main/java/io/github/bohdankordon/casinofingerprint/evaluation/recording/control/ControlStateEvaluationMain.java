package io.github.bohdankordon.casinofingerprint.evaluation.recording.control;

import io.github.bohdankordon.casinofingerprint.control.ControlThresholds;
import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.control.PuzzleControlStateDetector;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingFrameDecoder;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegion;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegionType;
import org.bytedeco.opencv.global.opencv_videoio;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Stage 7B control-state evaluation over the two private recordings (never in CI).
 *
 * <p>Decodes each recording once, samples the committed annotation-only timestamps in
 * {@code fixtures/gameplay/recordings/stage7b-control-states.csv} by exact frame index, runs
 * the PRODUCTION detector on every sample, and reports focus exact-match, selected-set
 * exact-match and validity agreement. Review outputs land ignored below {@code target/}:
 * {@code stage7b-control-state-frames.csv}, {@code stage7b-control-state-summary.csv} and
 * {@code stage7b-report.txt}. No image or video is committed; the annotation carries only
 * source ids, timestamps and expected UI states.
 *
 * <p>Production resolution is 2560x1440 only. The 1920x1080 rows run the same detector code
 * over evaluation-only geometry (the Stage 6 derived candidate rectangles, transcribed
 * below) with band-area-scaled bounds, and stay evaluation-only.
 */
public final class ControlStateEvaluationMain {
    static final String ANNOTATION_REL = "fixtures/gameplay/recordings/stage7b-control-states.csv";
    static final String VIDEO_1440P_REL = "local-data/stage6/casino-heist-1440p.mkv";
    static final String VIDEO_1080P_REL = "local-data/stage6/casino-heist-1080p.mp4";
    static final String FRAMES_CSV = "target/stage7b-control-state-frames.csv";
    static final String SUMMARY_CSV = "target/stage7b-control-state-summary.csv";
    static final String REPORT_TXT = "target/stage7b-report.txt";

    /**
     * Evaluation-only 1920x1080 candidate rectangles, transcribed from the ignored Stage 6
     * derivation ({@code target/stage6-derived-layout-1920x1080.csv}): the pure 0.75 scale of
     * the representative layout drifts 2..3 px on the bottom rows, which thins the
     * bottom-row focus margins, so the evaluation uses the derived rectangles exactly.
     */
    static final int[][] CANDIDATES_1080P = {
        {477, 273}, {621, 273}, {477, 416}, {621, 416},
        {477, 561}, {621, 561}, {477, 705}, {621, 705},
    };
    static final int TILE_1080P = 114;

    private ControlStateEvaluationMain() {
    }

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err, Path.of(System.getProperty("user.dir")));
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err, Path projectRoot) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            out.print(usage());
            return 0;
        }
        if (args.length != 0) {
            err.println("error: unknown option " + args[0]);
            err.print(usage());
            return 2;
        }
        List<Annotation> annotations;
        try {
            annotations = readAnnotations(projectRoot.resolve(ANNOTATION_REL));
        } catch (IOException | IllegalArgumentException e) {
            err.println("EVAL: SETUP_ERROR: " + e.getMessage());
            return 3;
        }
        GameplayLayout layout1440;
        try {
            layout1440 = GameplayLayout
                    .representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        } catch (IOException | IllegalArgumentException e) {
            err.println("EVAL: SETUP_ERROR: could not read the gameplay layout: " + e.getMessage());
            return 3;
        }
        List<GameplayRegion> tiles1080 = new ArrayList<>();
        for (int tile = 0; tile < 8; tile++) {
            tiles1080.add(new GameplayRegion(GameplayRegionType.CANDIDATE, tile,
                    CANDIDATES_1080P[tile][0], CANDIDATES_1080P[tile][1], TILE_1080P,
                    TILE_1080P));
        }
        double areaRatio = (double) PuzzleControlStateDetector.bandPixelCount(TILE_1080P)
                / PuzzleControlStateDetector.bandPixelCount(layout1440.candidatesRowMajor()
                        .get(0).width());
        ControlThresholds limits1080 = ControlThresholds.PRODUCTION_1440P.scaled(areaRatio);
        out.println("EVAL: 1080p band-area ratio " + String.format(Locale.ROOT, "%.4f", areaRatio)
                + " -> floor=" + limits1080.focusFloor() + " margin="
                + limits1080.focusMargin() + " ceiling=" + limits1080.focusCeiling());
        Map<String, List<Annotation>> bySource = new TreeMap<>();
        for (Annotation annotation : annotations) {
            bySource.computeIfAbsent(annotation.sourceId, ignored -> new ArrayList<>())
                    .add(annotation);
        }
        List<FrameRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<Annotation>> entry : bySource.entrySet()) {
            String sourceId = entry.getKey();
            List<Annotation> sourceRows = entry.getValue();
            sourceRows.sort(Comparator.comparingDouble(annotation -> annotation.tSeconds));
            boolean wide = sourceId.endsWith("1080p");
            Path video = projectRoot.resolve(wide ? VIDEO_1080P_REL : VIDEO_1440P_REL);
            if (!Files.isRegularFile(video)) {
                err.println("EVAL: SETUP_ERROR: recording not found: " + video
                        + " (private local-data is required for this evaluation)");
                return 3;
            }
            try {
                evaluateSource(video, wide ? 1920 : 2560, wide ? 1080 : 1440, sourceRows,
                        layout1440, tiles1080, limits1080, rows, out);
            } catch (IOException | IllegalStateException e) {
                err.println("EVAL: FAILED on " + sourceId + ": " + e.getMessage());
                return 3;
            }
        }
        return writeOutputs(rows, out, err, projectRoot);
    }

    static RecordingFrameDecoder openPreferred(Path video, int width, int height)
            throws IOException {
        int[] preferences = {
            opencv_videoio.CAP_FFMPEG, opencv_videoio.CAP_DSHOW, opencv_videoio.CAP_MSMF, -1,
        };
        for (int preference : preferences) {
            try {
                return RecordingFrameDecoder.open(video, width, height, preference);
            } catch (IllegalStateException e) {
                // Backend cannot open this container here: try the next one.
            }
        }
        throw new IllegalStateException("No video backend on this machine opens " + video);
    }

    private static void evaluateSource(Path video, int width, int height, List<Annotation> rows,
            GameplayLayout layout1440, List<GameplayRegion> tiles1080,
            ControlThresholds limits1080, List<FrameRow> out, PrintStream log)
            throws IOException {
        // Sampling prefers a backend with frame-exact indexing when one is available. The
        // shared default decoder path is untouched; when neither explicit backend opens, the
        // evaluation falls back to the platform default.
        RecordingFrameDecoder decoder = openPreferred(video, width, height);
        try (decoder) {
            double fps = decoder.fps();
            for (Annotation annotation : rows) {
                long target = Math.round(annotation.tSeconds * fps);
                while (decoder.decodedFrames() <= target) {
                    if (!decoder.read()) {
                        throw new IllegalStateException("video ends before frame " + target
                                + " for t=" + annotation.tSeconds);
                    }
                }
                PuzzleControlState state;
                if (width == 2560) {
                    state = PuzzleControlStateDetector.detect(decoder.frame(), layout1440,
                            ControlThresholds.PRODUCTION_1440P);
                } else {
                    state = PuzzleControlStateDetector.detectRegions(decoder.frame(), tiles1080,
                            width, height, limits1080);
                }
                out.add(new FrameRow(annotation, decoder.frameIndex(),
                        decoder.timestampSeconds(), state, match(annotation, state)));
            }
            log.println("EVAL: " + video.getFileName() + " backend=" + decoder.backendName()
                    + " decoded=" + decoder.decodedFrames());
        }
    }

    static boolean match(Annotation annotation, PuzzleControlState state) {
        if (state.valid() != annotation.expectValid) {
            return false;
        }
        if (!annotation.expectValid) {
            return true;
        }
        if (state.focus().isEmpty()
                || state.focus().get().index() != annotation.expectedFocus) {
            return false;
        }
        return state.selected().equals(annotation.expectedSelected);
    }

    private static int writeOutputs(List<FrameRow> rows, PrintStream out, PrintStream err,
            Path projectRoot) {
        StringBuilder frames = new StringBuilder(
                "source_id,t_seconds,frame_index,frame_seconds,valid,focus,selected,"
                        + "expect_valid,expected_focus,expected_selected,match,detail,"
                        + "scores,inners\n");
        StringBuilder report = new StringBuilder();
        int mismatches = 0;
        Map<String, int[]> totals = new TreeMap<>();
        for (FrameRow row : rows) {
            int[] counts = totals.computeIfAbsent(row.annotation.sourceId, ignored -> new int[2]);
            counts[0]++;
            if (row.match) {
                counts[1]++;
            } else {
                mismatches++;
            }
            frames.append(row.annotation.sourceId).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", row.annotation.tSeconds))
                    .append(',').append(row.frameIndex).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", row.frameSeconds)).append(',')
                    .append(row.state.valid()).append(',')
                    .append(row.state.focus().map(focus -> "C" + focus.index()).orElse(""))
                    .append(',').append(join(row.state.selected())).append(',')
                    .append(row.annotation.expectValid).append(',')
                    .append(row.annotation.expectedFocus < 0 ? ""
                            : "C" + row.annotation.expectedFocus)
                    .append(',').append(join(row.annotation.expectedSelected)).append(',')
                    .append(row.match).append(',').append(quote(row.state.detail())).append(',')
                    .append(join(row.state.bracketScores())).append(',')
                    .append(join(row.state.innerMeans())).append('\n');
        }
        StringBuilder summary = new StringBuilder("source_id,rows,matched,mismatched\n");
        for (Map.Entry<String, int[]> entry : totals.entrySet()) {
            summary.append(entry.getKey()).append(',').append(entry.getValue()[0]).append(',')
                    .append(entry.getValue()[1]).append(',')
                    .append(entry.getValue()[0] - entry.getValue()[1]).append('\n');
            report.append(String.format(Locale.ROOT, "%s: %d/%d matched%n", entry.getKey(),
                    entry.getValue()[1], entry.getValue()[0]));
        }
        report.append(String.format(Locale.ROOT, "total: %d/%d matched%n", rows.size() - mismatches,
                rows.size()));
        for (FrameRow row : rows) {
            if (!row.match) {
                report.append(String.format(Locale.ROOT,
                        "MISMATCH %s t=%.2f frame=%d valid=%s focus=%s selected=%s "
                                + "expected valid=%s focus=%s selected=%s detail=%s%n",
                        row.annotation.sourceId, row.annotation.tSeconds, row.frameIndex,
                        row.state.valid(),
                        row.state.focus().map(focus -> "C" + focus.index()).orElse("-"),
                        join(row.state.selected()), row.annotation.expectValid,
                        row.annotation.expectedFocus < 0 ? "-" : "C" + row.annotation.expectedFocus,
                        join(row.annotation.expectedSelected), row.state.detail()));
            }
        }
        try {
            Path target = projectRoot.resolve("target");
            Files.createDirectories(target);
            Files.writeString(target.resolve("stage7b-control-state-frames.csv"),
                    frames.toString(), StandardCharsets.UTF_8);
            Files.writeString(target.resolve("stage7b-control-state-summary.csv"),
                    summary.toString(), StandardCharsets.UTF_8);
            Files.writeString(target.resolve("stage7b-report.txt"), report.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("EVAL: FAILED writing outputs: " + e.getMessage());
            return 3;
        }
        out.print(report);
        out.println("EVAL: wrote " + FRAMES_CSV + ", " + SUMMARY_CSV + ", " + REPORT_TXT);
        return mismatches == 0 ? 0 : 1;
    }

    private static String join(java.util.SortedSet<Integer> selected) {
        StringBuilder text = new StringBuilder();
        for (int candidate : selected) {
            if (text.length() > 0) {
                text.append(';');
            }
            text.append(candidate);
        }
        return text.toString();
    }

    private static String join(int[] values) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                text.append(';');
            }
            text.append(values[index]);
        }
        return text.toString();
    }

    private static String quote(String detail) {
        return "\"" + detail.replace("\"", "\"\"") + "\"";
    }

    static List<Annotation> readAnnotations(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).trim().equals(
                "source_id,t_seconds,expected_focus,expected_selected,expect_valid,notes")) {
            throw new IllegalArgumentException("CSV " + csv + " has an unexpected header");
        }
        List<Annotation> annotations = new ArrayList<>();
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",", 6);
            if (parts.length != 6) {
                throw new IllegalArgumentException(
                        "CSV " + csv + ":" + lineNumber + " needs 6 columns");
            }
            String sourceId = parts[0].trim();
            if (!sourceId.equals("recording_1440p") && !sourceId.equals("recording_1080p")) {
                throw new IllegalArgumentException(
                        "CSV " + csv + ":" + lineNumber + " unknown source_id " + sourceId);
            }
            double tSeconds;
            try {
                tSeconds = Double.parseDouble(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "CSV " + csv + ":" + lineNumber + " bad t_seconds", e);
            }
            String focusText = parts[2].trim();
            int expectedFocus = -1;
            if (!focusText.isBlank()) {
                if (!focusText.matches("C[0-7]")) {
                    throw new IllegalArgumentException("CSV " + csv + ":" + lineNumber
                            + " bad expected_focus " + focusText);
                }
                expectedFocus = Integer.parseInt(focusText.substring(1));
            }
            java.util.SortedSet<Integer> expectedSelected = new java.util.TreeSet<>();
            String selectedText = parts[3].trim();
            if (!selectedText.isBlank()) {
                for (String part : selectedText.split(";", -1)) {
                    int candidate;
                    try {
                        candidate = Integer.parseInt(part.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("CSV " + csv + ":" + lineNumber
                                + " bad expected_selected " + selectedText, e);
                    }
                    if (candidate < 0 || candidate > 7) {
                        throw new IllegalArgumentException("CSV " + csv + ":" + lineNumber
                                + " bad expected_selected " + selectedText);
                    }
                    expectedSelected.add(candidate);
                }
            }
            boolean expectValid;
            if (parts[4].trim().equals("true")) {
                expectValid = true;
            } else if (parts[4].trim().equals("false")) {
                expectValid = false;
            } else {
                throw new IllegalArgumentException("CSV " + csv + ":" + lineNumber
                        + " bad expect_valid " + parts[4].trim());
            }
            annotations.add(new Annotation(sourceId, tSeconds, expectedFocus, expectedSelected,
                    expectValid, parts[5].trim()));
        }
        if (annotations.isEmpty()) {
            throw new IllegalArgumentException("CSV " + csv + " holds no annotations");
        }
        return annotations;
    }

    static String usage() {
        String separator = System.lineSeparator();
        return "Control-state detector evaluation (needs the private local-data recordings):"
                + separator
                + "reads the committed annotation timestamps, samples both recordings by exact"
                + separator
                + "frame index through the production detector, and reports focus/selected/"
                + separator + "validity agreement. Review outputs land ignored below target/."
                + separator + separator + "Usage: (no arguments)" + separator
                + "       --help" + separator;
    }

    record Annotation(String sourceId, double tSeconds, int expectedFocus,
            java.util.SortedSet<Integer> expectedSelected, boolean expectValid, String notes) {
    }

    record FrameRow(Annotation annotation, long frameIndex, double frameSeconds,
            PuzzleControlState state, boolean match) {
    }
}
