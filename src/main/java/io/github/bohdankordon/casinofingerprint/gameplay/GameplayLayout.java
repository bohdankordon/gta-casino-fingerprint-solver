package io.github.bohdankordon.casinofingerprint.gameplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads and validates a resolution-specific gameplay layout manifest (CSV).
 *
 * <p>Coordinates are only valid for the source dimensions the manifest was measured
 * against. The bundled representative layout targets 2560x1440; additional layouts for
 * other resolutions or UI scales can be added later and used with the same
 * {@link GameplayFrameExtractor} without changing extraction logic.
 */
public final class GameplayLayout {
    /** Width the bundled representative fixture was measured against. */
    public static final int REPRESENTATIVE_WIDTH = 2560;
    /** Height the bundled representative fixture was measured against. */
    public static final int REPRESENTATIVE_HEIGHT = 1440;

    private final int sourceWidth;
    private final int sourceHeight;
    private final List<GameplayRegion> regions;

    private GameplayLayout(int sourceWidth, int sourceHeight, List<GameplayRegion> regions) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.regions = regions;
    }

    /**
     * Reads the bundled representative 2560x1440 layout.
     */
    public static GameplayLayout representative(Path manifestCsv) throws IOException {
        return read(manifestCsv, REPRESENTATIVE_WIDTH, REPRESENTATIVE_HEIGHT);
    }

    /**
     * Reads {@code manifestCsv} and validates the full layout contract against the given
     * source dimensions: exactly one target, candidates 0..7 with no gaps or duplicates,
     * and every ROI fully inside the frame.
     *
     * @return layout with regions in manifest order
     */
    public static GameplayLayout read(Path manifestCsv, int sourceWidth, int sourceHeight) throws IOException {
        Objects.requireNonNull(manifestCsv, "manifestCsv");
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("source dimensions must be positive");
        }
        List<String> lines = Files.readAllLines(manifestCsv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Manifest is empty: " + manifestCsv);
        }
        String header = lines.get(0).trim();
        String expected = "region_type,candidate_index,x,y,width,height";
        if (!header.equals(expected)) {
            throw new IllegalArgumentException("Unexpected manifest header: " + header);
        }
        List<GameplayRegion> regions = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            regions.add(parseLine(line, manifestCsv, i + 1));
        }
        validate(regions, sourceWidth, sourceHeight);
        return new GameplayLayout(sourceWidth, sourceHeight, List.copyOf(regions));
    }

    private static GameplayRegion parseLine(String line, Path manifestCsv, int lineNumber) {
        String[] parts = line.split(",", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException(
                    "Manifest " + manifestCsv + ":" + lineNumber + " needs 6 columns, got " + parts.length);
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        GameplayRegionType regionType;
        try {
            regionType = GameplayRegionType.fromManifest(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Manifest " + manifestCsv + ":" + lineNumber + " " + e.getMessage(), e);
        }
        Integer candidateIndex = null;
        if (!parts[1].isEmpty()) {
            try {
                candidateIndex = Integer.valueOf(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Manifest " + manifestCsv + ":" + lineNumber
                        + " invalid candidate_index: " + parts[1]);
            }
        }
        int x = parseInt(parts[2], manifestCsv, lineNumber, "x");
        int y = parseInt(parts[3], manifestCsv, lineNumber, "y");
        int width = parseInt(parts[4], manifestCsv, lineNumber, "width");
        int height = parseInt(parts[5], manifestCsv, lineNumber, "height");
        try {
            return new GameplayRegion(regionType, candidateIndex, x, y, width, height);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Manifest " + manifestCsv + ":" + lineNumber + " " + e.getMessage(), e);
        }
    }

    private static int parseInt(String value, Path manifestCsv, int lineNumber, String column) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Manifest " + manifestCsv + ":" + lineNumber
                    + " invalid " + column + ": " + value);
        }
    }

    /** Validates counts, candidate indices and rectangles. */
    public static void validate(List<GameplayRegion> regions, int sourceWidth, int sourceHeight) {
        Objects.requireNonNull(regions, "regions");
        long targets = regions.stream().filter(r -> r.regionType() == GameplayRegionType.TARGET).count();
        if (targets != 1) {
            throw new IllegalArgumentException("Manifest needs exactly 1 target, got " + targets);
        }
        List<GameplayRegion> candidates = regions.stream()
                .filter(r -> r.regionType() == GameplayRegionType.CANDIDATE)
                .toList();
        if (candidates.size() != 8) {
            throw new IllegalArgumentException("Manifest needs exactly 8 candidates, got " + candidates.size());
        }
        Set<Integer> indices = new TreeSet<>();
        for (GameplayRegion candidate : candidates) {
            indices.add(candidate.candidateIndex());
        }
        if (!indices.equals(Set.of(0, 1, 2, 3, 4, 5, 6, 7))) {
            throw new IllegalArgumentException("Candidates need indices [0..7], got " + indices);
        }
        Set<String> seen = new HashSet<>();
        for (GameplayRegion region : regions) {
            String key = region.regionType() + ":" + region.candidateIndex();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Duplicate region: " + key);
            }
            if (region.x() + region.width() > sourceWidth || region.y() + region.height() > sourceHeight) {
                throw new IllegalArgumentException(
                        "Region outside " + sourceWidth + "x" + sourceHeight + ": " + region);
            }
        }
    }

    public int sourceWidth() {
        return sourceWidth;
    }

    public int sourceHeight() {
        return sourceHeight;
    }

    /** All regions in manifest order. */
    public List<GameplayRegion> regions() {
        return regions;
    }

    /** The single target region. */
    public GameplayRegion target() {
        return regions.stream()
                .filter(r -> r.regionType() == GameplayRegionType.TARGET)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Layout has no target"));
    }

    /** The eight candidate regions in row-major 0..7 order. */
    public List<GameplayRegion> candidatesRowMajor() {
        return regions.stream()
                .filter(r -> r.regionType() == GameplayRegionType.CANDIDATE)
                .sorted(Comparator.comparingInt(GameplayRegion::candidateIndex))
                .toList();
    }
}
