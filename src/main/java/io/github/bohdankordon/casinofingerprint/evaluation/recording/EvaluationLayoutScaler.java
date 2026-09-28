package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegion;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Derives one evaluation-only layout by scaling an existing layout uniformly.
 *
 * <p>Stage 6B hypothesis: the 1920x1080 recording has the same 16:9 aspect ratio as the bundled
 * 2560x1440 production layout, so the geometry might scale by a fixed factor. RECTANGLE EDGES are
 * scaled and only then turned back into width and height:
 *
 * <pre>
 * left   = x                 right  = x + width
 * top    = y                 bottom = y + height
 * scaledLeft   = round(left   * factor)
 * scaledRight  = round(right  * factor)
 * scaledTop    = round(top    * factor)
 * scaledBottom = round(bottom * factor)
 * scaledWidth  = scaledRight  - scaledLeft
 * scaledHeight = scaledBottom - scaledTop
 * </pre>
 *
 * <p>Scaling width and height independently would round the two opposite edges independently and
 * could shift a region by a pixel relative to its neighbours; the edge form keeps every region
 * consistent with the ones next to it.
 *
 * <p>Evaluation only. The derived layout is written below {@code target/} and never becomes a
 * production gameplay manifest, never resizes a frame, never tunes a coordinate and never adds a
 * supported resolution to the live runtime.
 */
public final class EvaluationLayoutScaler {
    /** Build-output path of the derived evaluation manifest (forward slashes). */
    public static final String DERIVED_LAYOUT_REL = "target/stage6-derived-layout-1920x1080.csv";

    private EvaluationLayoutScaler() {
    }

    /**
     * The single uniform scale factor implied by the two sizes.
     *
     * @throws IllegalArgumentException when the two sizes do not share one aspect ratio
     */
    public static double uniformScaleFactor(
            int fromWidth, int fromHeight, int toWidth, int toHeight) {
        if (fromWidth <= 0 || fromHeight <= 0 || toWidth <= 0 || toHeight <= 0) {
            throw new IllegalArgumentException("Frame sizes must be positive");
        }
        double horizontal = (double) toWidth / fromWidth;
        double vertical = (double) toHeight / fromHeight;
        if (Math.abs(horizontal - vertical) > 1e-9) {
            throw new IllegalArgumentException(String.format(
                    "Sizes %dx%d and %dx%d are not a uniform scale (%.6f vs %.6f)",
                    fromWidth, fromHeight, toWidth, toHeight, horizontal, vertical));
        }
        return horizontal;
    }

    /** Scales one region by edge scaling and validates the result against the target frame. */
    public static GameplayRegion scaleRegion(
            GameplayRegion region, double factor, int targetWidth, int targetHeight) {
        Objects.requireNonNull(region, "region");
        if (!Double.isFinite(factor) || factor <= 0.0) {
            throw new IllegalArgumentException("factor must be finite and positive, got " + factor);
        }
        int left = (int) Math.round(region.x() * factor);
        int top = (int) Math.round(region.y() * factor);
        int right = (int) Math.round((region.x() + region.width()) * factor);
        int bottom = (int) Math.round((region.y() + region.height()) * factor);
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Scaled region would be empty: " + region);
        }
        if (left < 0 || top < 0 || right > targetWidth || bottom > targetHeight) {
            throw new IllegalArgumentException(String.format(
                    "Scaled region %d,%d %dx%d leaves %dx%d",
                    left, top, width, height, targetWidth, targetHeight));
        }
        return new GameplayRegion(
                region.regionType(), region.candidateIndex(), left, top, width, height);
    }

    /** Scales every region of {@code regions}, preserving order. */
    public static List<GameplayRegion> scaleRegions(
            List<GameplayRegion> regions, double factor, int targetWidth, int targetHeight) {
        Objects.requireNonNull(regions, "regions");
        List<GameplayRegion> scaled = new ArrayList<>(regions.size());
        for (GameplayRegion region : regions) {
            scaled.add(scaleRegion(region, factor, targetWidth, targetHeight));
        }
        return List.copyOf(scaled);
    }

    /** Renders {@code regions} in the exact manifest format {@link GameplayLayout} reads. */
    public static String manifestCsv(List<GameplayRegion> regions) {
        Objects.requireNonNull(regions, "regions");
        StringBuilder csv = new StringBuilder("region_type,candidate_index,x,y,width,height\n");
        for (GameplayRegion region : regions) {
            csv.append(region.regionType().name()).append(',')
                    .append(region.candidateIndex() == null ? "" : region.candidateIndex()).append(',')
                    .append(region.x()).append(',')
                    .append(region.y()).append(',')
                    .append(region.width()).append(',')
                    .append(region.height()).append('\n');
        }
        return csv.toString();
    }

    /**
     * Writes the scaled manifest to {@code outputCsv} and reads it back through the production
     * {@link GameplayLayout} reader, so the derived geometry passes exactly the same contract as a
     * bundled manifest: one target, candidates 0..7, every region inside the frame.
     *
     * @return layout valid for frames of {@code targetWidth}x{@code targetHeight}
     */
    public static GameplayLayout writeAndRead(Path outputCsv, GameplayLayout source,
            int targetWidth, int targetHeight) throws IOException {
        Objects.requireNonNull(outputCsv, "outputCsv");
        Objects.requireNonNull(source, "source");
        double factor = uniformScaleFactor(
                source.sourceWidth(), source.sourceHeight(), targetWidth, targetHeight);
        List<GameplayRegion> scaled = scaleRegions(
                source.regions(), factor, targetWidth, targetHeight);
        GameplayLayout.validate(scaled, targetWidth, targetHeight);
        Path parent = outputCsv.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputCsv, manifestCsv(scaled), StandardCharsets.UTF_8);
        Path written = outputCsv.toAbsolutePath();
        return GameplayLayout.read(written, targetWidth, targetHeight);
    }

    /** Output path of the derived manifest below {@code projectRoot}. */
    public static Path derivedManifestPath(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        return projectRoot.resolve(DERIVED_LAYOUT_REL.replace('/', File.separatorChar));
    }
}
