package io.github.bohdankordon.casinofingerprint.matching.evaluation;

import io.github.bohdankordon.casinofingerprint.matching.FragmentScoreMatrix;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Optional Stage 3 image diagnostics: a score heatmap and a reference-vs-candidate preview.
 *
 * <p>Evaluation only. Every number shown here is also written to CSV and to the text report, so
 * neither image is ever the only source of a measurement.
 */
final class Stage3ImageDiagnostics {
    private static final Scalar BACKGROUND = new Scalar(24, 24, 24, 0);
    private static final Scalar TEXT = new Scalar(235, 235, 235, 0);
    private static final Scalar DARK_TEXT = new Scalar(10, 10, 10, 0);
    private static final Scalar GROUND_TRUTH = new Scalar(0, 220, 0, 0);
    private static final Scalar COLUMN_BEST = new Scalar(230, 200, 0, 0);

    private Stage3ImageDiagnostics() {
    }

    /** Candidate x fragment heatmap with ground-truth cells and per-column winners highlighted. */
    static void writeHeatmap(FragmentScoreMatrix matrix, FixtureAnnotation annotation, Path output)
            throws IOException {
        int cellWidth = 190;
        int cellHeight = 96;
        int labelWidth = 170;
        int headerHeight = 132;
        int footerHeight = 92;
        int columns = matrix.fragmentIds().size();
        int rows = matrix.candidateIndices().size();
        int canvasWidth = labelWidth + columns * cellWidth + 24;
        int canvasHeight = headerHeight + rows * cellHeight + footerHeight;
        try (Mat canvas = new Mat(canvasHeight, canvasWidth, opencv_core.CV_8UC3, BACKGROUND)) {
            putText(canvas, "Stage 3 fragment similarity - identified target " + annotation.target(),
                    24, 40, 0.62, TEXT);
            putText(canvas, "rows: gameplay candidate index 0..7   columns: reference fragment id 1..4",
                    24, 68, 0.52, TEXT);
            for (int column = 0; column < columns; column++) {
                int x = labelWidth + column * cellWidth;
                putText(canvas, "FRAGMENT_" + matrix.fragmentIds().get(column), x + 24, headerHeight - 16,
                        0.52, TEXT);
            }
            for (int row = 0; row < rows; row++) {
                int candidateIndex = matrix.candidateIndices().get(row);
                int y = headerHeight + row * cellHeight;
                putText(canvas, "CANDIDATE_" + candidateIndex, 16, y + cellHeight / 2 + 6, 0.52, TEXT);
                for (int column = 0; column < columns; column++) {
                    int fragmentId = matrix.fragmentIds().get(column);
                    double score = matrix.score(candidateIndex, fragmentId).value();
                    int intensity = (int) Math.round(30 + 200 * score);
                    int x = labelWidth + column * cellWidth;
                    Scalar fill = new Scalar(intensity, intensity, intensity, 0);
                    Rect rect = new Rect(x + 4, y + 4, cellWidth - 8, cellHeight - 8);
                    fillRect(canvas, rect, fill);
                    Scalar textColor = intensity > 150 ? DARK_TEXT : TEXT;
                    putText(canvas, String.format(Locale.ROOT, "%.4f", score), x + 26,
                            y + cellHeight / 2 + 8, 0.62, textColor);
                    if (annotation.candidateFor(fragmentId) == candidateIndex) {
                        rectangle(canvas, rect, GROUND_TRUTH, 3);
                    } else if (matrix.bestCandidateForFragment(fragmentId) == candidateIndex) {
                        rectangle(canvas, new Rect(x + 7, y + 7, cellWidth - 14, cellHeight - 14),
                                COLUMN_BEST, 1);
                    }
                }
            }
            int legendY = canvasHeight - 62;
            rectangle(canvas, new Rect(24, legendY, 26, 20), GROUND_TRUTH, 3);
            putText(canvas, "human-verified ground-truth cell (fixture annotation)", 64, legendY + 16,
                    0.5, TEXT);
            rectangle(canvas, new Rect(24, legendY + 26, 26, 20), COLUMN_BEST, 1);
            putText(canvas, "best candidate in that fragment column (matcher ranking)", 64,
                    legendY + 42, 0.5, TEXT);
            write(canvas, output);
        }
    }

    /**
     * One row per reference fragment: the reference profile next to the highest scoring candidate
     * and the strongest incorrect candidate.
     */
    static void writePreview(FingerprintId target, ReferenceFingerprintLibrary library,
            List<Mat> normalizedCandidates, FragmentScoreMatrix matrix, FixtureAnnotation annotation,
            Path output) throws IOException {
        int tile = 256;
        int gap = 26;
        int pad = 26;
        int rowHeight = tile + 96 + pad;
        int rows = matrix.fragmentIds().size();
        String[] headers = new String[rows];
        String[] subHeaders = new String[rows];
        int textWidth = 0;
        for (int row = 0; row < rows; row++) {
            int fragmentId = matrix.fragmentIds().get(row);
            int correct = annotation.candidateFor(fragmentId);
            int best = matrix.bestCandidateForFragment(fragmentId);
            int strongestIncorrect = strongestIncorrectCandidate(matrix, fragmentId, correct);
            headers[row] = String.format(Locale.ROOT,
                    "FRAGMENT_%d   ground truth C%d = %.4f   matcher top C%d = %.4f   %s",
                    fragmentId, correct, matrix.score(correct, fragmentId).value(), best,
                    matrix.score(best, fragmentId).value(), best == correct ? "[agree]" : "[DISAGREES]");
            subHeaders[row] = String.format(Locale.ROOT, "strongest incorrect C%d = %.4f   margin = %.4f",
                    strongestIncorrect, matrix.score(strongestIncorrect, fragmentId).value(),
                    matrix.score(correct, fragmentId).value()
                            - matrix.score(strongestIncorrect, fragmentId).value());
            textWidth = Math.max(textWidth, estimatedTextWidth(headers[row], 0.48));
            textWidth = Math.max(textWidth, estimatedTextWidth(subHeaders[row], 0.48));
        }
        int canvasWidth = Math.max(pad + 3 * tile + 2 * gap + pad, 2 * pad + textWidth);
        int canvasHeight = pad + 20 + rows * rowHeight + 40;
        try (Mat canvas = new Mat(canvasHeight, canvasWidth, opencv_core.CV_8UC3, BACKGROUND)) {
            putText(canvas, "Stage 3 fragment match preview - identified target " + target,
                    24, 40, 0.62, TEXT);
            int y = 60;
            for (int row = 0; row < rows; row++) {
                int fragmentId = matrix.fragmentIds().get(row);
                int correct = annotation.candidateFor(fragmentId);
                int best = matrix.bestCandidateForFragment(fragmentId);
                int strongestIncorrect = strongestIncorrectCandidate(matrix, fragmentId, correct);
                double correctScore = matrix.score(correct, fragmentId).value();
                double bestScore = matrix.score(best, fragmentId).value();
                double incorrectScore = matrix.score(strongestIncorrect, fragmentId).value();
                putText(canvas, headers[row], 24, y + 24, 0.48, best == correct ? TEXT : GROUND_TRUTH);
                putText(canvas, subHeaders[row], 24, y + 46, 0.48, TEXT);
                int imageY = y + 58;
                drawTile(canvas, upscale(library.fragment(target, fragmentId)), pad, imageY, tile);
                putText(canvas, "reference FRAGMENT_" + fragmentId, pad, imageY + tile + 20, 0.46, TEXT);
                drawTile(canvas, upscale(normalizedCandidates.get(best)), pad + tile + gap, imageY, tile);
                putText(canvas, String.format(Locale.ROOT, "matcher top: C%d (%.4f)", best, bestScore),
                        pad + tile + gap, imageY + tile + 20, 0.46, TEXT);
                int thirdX = pad + 2 * (tile + gap);
                drawTile(canvas, upscale(normalizedCandidates.get(strongestIncorrect)), thirdX, imageY, tile);
                putText(canvas, String.format(Locale.ROOT, "strongest incorrect: C%d (%.4f)",
                                strongestIncorrect, incorrectScore),
                        thirdX, imageY + tile + 20, 0.46, TEXT);
                putText(canvas, String.format(Locale.ROOT, "margin %.4f", correctScore - incorrectScore),
                        thirdX, imageY + tile + 44, 0.46, TEXT);
                y += rowHeight;
            }
            putText(canvas, "candidates shown as normalized 128x128 profiles (2x display), the same data matching scored",
                    24, canvasHeight - 16, 0.46, TEXT);
            write(canvas, output);
        }
    }

    private static int strongestIncorrectCandidate(FragmentScoreMatrix matrix, int fragmentId, int correct) {
        int best = -1;
        for (int candidate : matrix.candidateIndices()) {
            if (candidate == correct) {
                continue;
            }
            if (best < 0 || matrix.score(candidate, fragmentId).value()
                    > matrix.score(best, fragmentId).value()) {
                best = candidate;
            }
        }
        return best;
    }

    private static void drawTile(Mat canvas, Mat gray, int x, int y, int size) {
        try (Mat color = new Mat()) {
            opencv_imgproc.cvtColor(gray, color, opencv_imgproc.COLOR_GRAY2BGR);
            try (Mat view = new Mat(canvas, new Rect(x, y, size, size))) {
                color.copyTo(view);
            }
        } finally {
            gray.close();
        }
        rectangle(canvas, new Rect(x, y, size, size), new Scalar(120, 120, 120, 0), 1);
    }

    private static Mat upscale(Mat gray) {
        Mat scaled = new Mat();
        opencv_imgproc.resize(gray, scaled, new Size(gray.cols() * 2, gray.rows() * 2), 0, 0,
                opencv_imgproc.INTER_NEAREST);
        return scaled;
    }

    /** Conservative label width estimate; an overestimate only widens the canvas. */
    private static int estimatedTextWidth(String text, double scale) {
        return (int) Math.ceil(text.length() * 20 * scale);
    }

    private static void fillRect(Mat canvas, Rect rect, Scalar color) {
        opencv_imgproc.rectangle(canvas, rect, color, -1, opencv_imgproc.LINE_8, 0);
    }

    private static void rectangle(Mat canvas, Rect rect, Scalar color, int thickness) {
        opencv_imgproc.rectangle(canvas, rect, color, thickness, opencv_imgproc.LINE_8, 0);
    }

    private static void putText(Mat canvas, String text, int x, int y, double scale, Scalar color) {
        opencv_imgproc.putText(canvas, text, new Point(x, y), opencv_imgproc.FONT_HERSHEY_SIMPLEX, scale,
                color, 1, opencv_imgproc.LINE_8, false);
    }

    private static void write(Mat image, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        if (!opencv_imgcodecs.imwrite(output.toString(), image)) {
            throw new IllegalStateException("Could not write Stage 3 diagnostic image: " + output);
        }
    }
}
