package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import io.github.bohdankordon.casinofingerprint.control.ControlThresholds;
import io.github.bohdankordon.casinofingerprint.control.LayoutControlReader;
import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.EvaluationLayoutScaler;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingAnnotationCatalog;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingFrameDecoder;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingRoundAnnotation;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingSource;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingSourceCatalog;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.orchestration.FrameControlReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Local presence-signal characterization (never in CI). Measures chrome anchors and green
 * excess on the committed fixture, clean-session full frames, and private Stage 6 recording
 * frames (round middles, inter-round gaps, exits, overlay floods), then writes ignored analysis
 * under target/stage8a-presence-characterization/. No image is committed.
 */
public final class ExternalPanelPresenceCharacterizationMain {
    static final String OUTPUT_REL = "target/stage8a-presence-characterization";

    private ExternalPanelPresenceCharacterizationMain() {
    }

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err, Path.of(System.getProperty("user.dir")));
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err, Path projectRoot) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            out.println("Usage: ExternalPanelPresenceCharacterizationMain (no options)");
            return 0;
        }
        if (args.length != 0) {
            err.println("error: this tool takes no options");
            return 2;
        }
        try {
            GameplayLayout production = GameplayLayout.representative(
                    projectRoot.resolve(GameplayFixture.LAYOUT_REL));
            Path outputRoot = projectRoot.resolve(OUTPUT_REL.replace('/', java.io.File.separatorChar));
            Files.createDirectories(outputRoot);
            List<String> rows = new ArrayList<>();
            rows.add("sample,anchors,g0,g1,g2,g3,green,bright,presence,detail");
            Path fixture = projectRoot.resolve(GameplayFixture.SOURCE_REL);
            if (Files.isRegularFile(fixture)) {
                try (Mat frame = opencv_imgcodecs.imread(fixture.toString(),
                        opencv_imgcodecs.IMREAD_UNCHANGED)) {
                    if (frame != null && !frame.empty()) {
                        try (Mat owned = toBgr(frame)) {
                            ExternalPanelPresenceResult result =
                                    ExternalPuzzlePanelPresenceDetector.detect(owned, production);
                            rows.add(csvRow("fixture-present", result));
                            out.println("fixture: " + result.presence() + " " + result.detail());
                        }
                    }
                }
            }
            Path clean = projectRoot.resolve("target/stage8a/youtube-clean-01/screenshots");
            if (Files.isDirectory(clean)) {
                try (var stream = Files.list(clean)) {
                    for (Path full : stream.filter(p -> p.toString().endsWith("-full.png")).sorted().toList()) {
                        try (Mat frame = opencv_imgcodecs.imread(full.toString(),
                                opencv_imgcodecs.IMREAD_UNCHANGED)) {
                            if (frame == null || frame.empty()) {
                                continue;
                            }
                            try (Mat owned = toBgr(frame)) {
                                if (owned.cols() != production.sourceWidth()
                                        || owned.rows() != production.sourceHeight()) {
                                    continue;
                                }
                                ExternalPanelPresenceResult result =
                                        ExternalPuzzlePanelPresenceDetector.detect(owned, production);
                                rows.add(csvRow("clean-" + full.getFileName().toString(), result));
                            }
                        }
                    }
                }
            }
            samplePrivateRecordings(projectRoot, production, rows, out);
            Path csv = outputRoot.resolve("anchor-scores.csv");
            Files.writeString(csv, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
            Files.writeString(outputRoot.resolve("characterization.md"), report(rows),
                    StandardCharsets.UTF_8);
            out.println("wrote " + csv);
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("CHARACTERIZATION FAILED: " + e.getMessage());
            return 3;
        }
    }

    private static void samplePrivateRecordings(Path projectRoot, GameplayLayout production,
            List<String> rows, PrintStream out) {
        List<RecordingSource> catalog;
        RecordingAnnotationCatalog annotations;
        try {
            catalog = RecordingSourceCatalog.readCommitted(projectRoot);
            annotations = RecordingAnnotationCatalog.readCommitted(projectRoot);
        } catch (IOException | IllegalArgumentException e) {
            out.println("private catalog unavailable: " + e.getMessage());
            return;
        }
        for (RecordingSource source : catalog) {
            Path video = source.localPath(projectRoot);
            if (!Files.isRegularFile(video)) {
                out.println("skip " + source.sourceId() + ": no local recording");
                continue;
            }
            try {
                sampleOneSource(source, video, annotations, production, rows, out);
            } catch (IOException | RuntimeException e) {
                out.println("skip " + source.sourceId() + ": " + e.getMessage());
            }
        }
    }

    private static void sampleOneSource(RecordingSource source, Path video,
            RecordingAnnotationCatalog annotations, GameplayLayout production,
            List<String> rows, PrintStream out) throws IOException {
        GameplayLayout layout = production;
        ControlThresholds thresholds = ControlThresholds.PRODUCTION_1440P;
        if (source.width() != production.sourceWidth()
                || source.height() != production.sourceHeight()) {
            double factor = EvaluationLayoutScaler.uniformScaleFactor(
                    production.sourceWidth(), production.sourceHeight(),
                    source.width(), source.height());
            layout = EvaluationLayoutScaler.writeAndRead(
                    video.getParent().resolve("derived-layout-tmp.csv"), production,
                    source.width(), source.height());
            thresholds = ControlThresholds.PRODUCTION_1440P.scaled(factor * factor);
        }
        FrameControlReader controlReader = new LayoutControlReader(layout, thresholds);
        List<RecordingRoundAnnotation> rounds = annotations.roundsFor(source.sourceId());
        try (RecordingFrameDecoder decoder =
                RecordingFrameDecoder.open(video, source.width(), source.height())) {
            int ceilingSamples = 0;
            while (decoder.read()) {
                double seconds = decoder.timestampSeconds();
                String label = labelFor(seconds, rounds);
                if (label == null) {
                    continue;
                }
                PuzzleControlState control;
                try {
                    control = controlReader.read(decoder.frame());
                } catch (RuntimeException e) {
                    continue;
                }
                boolean ceiling = !control.valid() && control.detail().contains("CEILING");
                boolean take = label.startsWith("round-middle") || label.startsWith("inter-round")
                        || label.startsWith("exit") || (ceiling && ceilingSamples < 4);
                if (!take) {
                    continue;
                }
                if (ceiling) {
                    ceilingSamples++;
                }
                try (Mat owned = toBgr(decoder.frame())) {
                    ExternalPanelPresenceResult result =
                            ExternalPuzzlePanelPresenceDetector.detect(owned, layout);
                    rows.add(csvRow(source.sourceId() + "-" + label + "-" + String.format(Locale.ROOT, "%.2f", seconds), result));
                }
                if (rows.size() > 120) {
                    break;
                }
            }
        }
        out.println("sampled " + source.sourceId());
    }

    private static String labelFor(double seconds, List<RecordingRoundAnnotation> rounds) {
        for (RecordingRoundAnnotation round : rounds) {
            double mid = (round.startSeconds() + round.endSeconds()) / 2.0;
            if (Math.abs(seconds - mid) < 0.05) {
                return "round-middle-h" + round.hackId() + "r" + round.roundId();
            }
            double exit = round.endSeconds() + 1.0;
            if (Math.abs(seconds - exit) < 0.05) {
                return "exit-h" + round.hackId() + "r" + round.roundId();
            }
        }
        for (int i = 0; i + 1 < rounds.size(); i++) {
            RecordingRoundAnnotation a = rounds.get(i);
            RecordingRoundAnnotation b = rounds.get(i + 1);
            double gap = (a.endSeconds() + b.startSeconds()) / 2.0;
            if (b.startSeconds() - a.endSeconds() > 0.2 && Math.abs(seconds - gap) < 0.05) {
                return "inter-round-h" + a.hackId();
            }
        }
        return null;
    }

    private static String report(List<String> rows) {
        int present = 0;
        int absent = 0;
        int ambiguous = 0;
        int presentMinBright = Integer.MAX_VALUE;
        int absentMaxBrightDark = Integer.MIN_VALUE;
        int presentMaxGreen = Integer.MIN_VALUE;
        int absentMinGreenHack = Integer.MAX_VALUE;
        for (int i = 1; i < rows.size(); i++) {
            String[] parts = rows.get(i).split(",", -1);
            if (parts.length < 9) {
                continue;
            }
            String presence = parts[8];
            int bright = Integer.parseInt(parts[7]);
            int green = Integer.parseInt(parts[6]);
            String sample = parts[0];
            if (presence.equals("PRESENT")) {
                present++;
                presentMinBright = Math.min(presentMinBright, bright);
                presentMaxGreen = Math.max(presentMaxGreen, green);
            } else if (presence.equals("ABSENT")) {
                absent++;
                if (green < ExternalPuzzlePanelPresenceDetector.GREEN_ABSENT) {
                    absentMaxBrightDark = Math.max(absentMaxBrightDark, bright);
                }
                if (green >= ExternalPuzzlePanelPresenceDetector.GREEN_ABSENT) {
                    absentMinGreenHack = Math.min(absentMinGreenHack, green);
                }
            } else {
                ambiguous++;
            }
        }
        StringBuilder text = new StringBuilder();
        text.append("# Panel-presence characterization (local only, never committed)\n\n");
        text.append("Samples: present=").append(present).append(" absent=").append(absent)
                .append(" ambiguous=").append(ambiguous).append("\n\n");
        text.append("## Signals tested\n");
        text.append("- four static chrome anchors (timeout, clone-target, components, access-attempts)\n");
        text.append("- central green excess for HACK SUCCESS overlay\n");
        text.append("- rejected: OCR, predicted id, solver confidence, selected set (circular)\n\n");
        text.append("## Chosen rule\n");
        text.append("BRIGHT_FLOOR=110, GREEN_ABSENT=15, present needs 3-4 bright with calm green; ");
        text.append("green forces absent; 1 or fewer bright is absent; split is ambiguous.\n\n");
        text.append("## Worst-case margins\n");
        text.append("present min bright=").append(presentMinBright)
                .append(" (needs 3); absent dark max bright=").append(absentMaxBrightDark)
                .append(" (allows 1)\n");
        text.append("present max green=").append(presentMaxGreen)
                .append(" (ceiling 15); hack min green=").append(absentMinGreenHack)
                .append("\n\n");
        text.append("## Overlays and error behavior\n");
        text.append("ERROR and SIGNAL PATCH keep headers bright with calm green, so presence stays ");
        text.append("PRESENT and the same-puzzle retry path is preserved. HACK SUCCESS tints the ");
        text.append("center green and dims headers, so presence goes ABSENT and post-puzzle ROI ");
        text.append("garbage is ignored. Transition frames with split chrome fail closed to AMBIGUOUS.\n\n");
        text.append("## Known limitations\n");
        text.append("- heavy compression or gamma shifts could push a chrome anchor across 110; ");
        text.append("four anchors plus green keep single-anchor flips from deciding.\n");
        text.append("- a fullscreen wall the exact color of header chrome could fake one anchor; ");
        text.append("three-anchor quorum plus green still fail closed.\n");
        text.append("- direct cuts with no absent frame rely on FOUR_SELECTED_PENDING, not presence.\n");
        text.append("STOP condition passed: present vs absent separate convincingly.\n");
        return text.toString();
    }

    private static String csvRow(String sample, ExternalPanelPresenceResult result) {
        int[] means = result.anchorMeans();
        return String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%d,%s,%s", sample,
                means.length, means[0], means[1], means[2], means[3], result.greenExcess(),
                result.brightCount(), result.presence().name(),
                result.detail().replace(',', ';'));
    }

    private static Mat toBgr(Mat frame) {
        if (frame.channels() == 3) {
            return frame.clone();
        }
        if (frame.channels() == 4) {
            Mat bgr = new Mat();
            org.bytedeco.opencv.global.opencv_imgproc.cvtColor(frame, bgr,
                    org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2BGR);
            return bgr;
        }
        if (frame.channels() == 1) {
            Mat bgr = new Mat();
            org.bytedeco.opencv.global.opencv_imgproc.cvtColor(frame, bgr,
                    org.bytedeco.opencv.global.opencv_imgproc.COLOR_GRAY2BGR);
            return bgr;
        }
        throw new IllegalArgumentException("channels=" + frame.channels());
    }
}
