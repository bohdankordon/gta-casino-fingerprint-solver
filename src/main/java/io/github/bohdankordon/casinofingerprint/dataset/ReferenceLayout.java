package io.github.bohdankordon.casinofingerprint.dataset;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Objects;

/** Reads and validates the human-readable crop manifest (CSV). */
public final class ReferenceLayout {
    static final int SOURCE_WIDTH = 1500;
    static final int SOURCE_HEIGHT = 1900;

    private ReferenceLayout() {
    }

    /**
     * Reads {@code manifestCsv} and validates the full dataset contract.
     *
     * @return unmodifiable crops in manifest order
     */
    public static List<ReferenceCrop> read(Path manifestCsv) throws IOException {
        Objects.requireNonNull(manifestCsv, "manifestCsv");
        List<String> lines = Files.readAllLines(manifestCsv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Manifest is empty: " + manifestCsv);
        }
        String header = lines.get(0).trim();
        String expected = "fingerprint_id,asset_type,fragment_id,x,y,width,height,output_path";
        if (!header.equals(expected)) {
            throw new IllegalArgumentException("Unexpected manifest header: " + header);
        }
        List<ReferenceCrop> crops = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            crops.add(parseLine(line, manifestCsv, i + 1));
        }
        validate(crops, SOURCE_WIDTH, SOURCE_HEIGHT);
        return List.copyOf(crops);
    }

    private static ReferenceCrop parseLine(String line, Path manifestCsv, int lineNumber) {
        String[] parts = line.split(",", -1);
        if (parts.length != 8) {
            throw new IllegalArgumentException(
                    "Manifest " + manifestCsv + ":" + lineNumber + " needs 8 columns, got " + parts.length);
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        FingerprintId fingerprintId;
        try {
            fingerprintId = FingerprintId.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Manifest " + manifestCsv + ":" + lineNumber + " unknown fingerprint_id: " + parts[0]);
        }
        ReferenceAssetType assetType = ReferenceAssetType.fromManifest(parts[1]);
        Integer fragmentId = null;
        if (!parts[2].isEmpty()) {
            try {
                fragmentId = Integer.valueOf(parts[2]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Manifest " + manifestCsv + ":" + lineNumber
                        + " invalid fragment_id: " + parts[2]);
            }
        }
        int x = parseInt(parts[3], manifestCsv, lineNumber, "x");
        int y = parseInt(parts[4], manifestCsv, lineNumber, "y");
        int width = parseInt(parts[5], manifestCsv, lineNumber, "width");
        int height = parseInt(parts[6], manifestCsv, lineNumber, "height");
        String outputPath = parts[7];
        try {
            return new ReferenceCrop(fingerprintId, assetType, fragmentId, x, y, width, height, outputPath);
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

    /** Validates counts, ids, rectangles and output paths. */
    public static void validate(List<ReferenceCrop> crops, int sourceWidth, int sourceHeight) {
        Objects.requireNonNull(crops, "crops");
        long targets = crops.stream().filter(c -> c.assetType() == ReferenceAssetType.TARGET).count();
        long fragments = crops.stream().filter(c -> c.assetType() == ReferenceAssetType.FRAGMENT).count();
        if (targets != 4) {
            throw new IllegalArgumentException("Manifest needs exactly 4 targets, got " + targets);
        }
        if (fragments != 16) {
            throw new IllegalArgumentException("Manifest needs exactly 16 fragments, got " + fragments);
        }
        if (crops.size() != 20) {
            throw new IllegalArgumentException("Manifest needs exactly 20 records, got " + crops.size());
        }
        Map<FingerprintId, List<ReferenceCrop>> byId = new EnumMap<>(FingerprintId.class);
        for (FingerprintId id : FingerprintId.values()) {
            byId.put(id, new ArrayList<>());
        }
        for (ReferenceCrop crop : crops) {
            byId.get(crop.fingerprintId()).add(crop);
        }
        for (FingerprintId id : FingerprintId.values()) {
            List<ReferenceCrop> group = byId.get(id);
            long t = group.stream().filter(c -> c.assetType() == ReferenceAssetType.TARGET).count();
            List<ReferenceCrop> frags = group.stream()
                    .filter(c -> c.assetType() == ReferenceAssetType.FRAGMENT)
                    .toList();
            if (t != 1) {
                throw new IllegalArgumentException(id + " needs exactly 1 target, got " + t);
            }
            if (frags.size() != 4) {
                throw new IllegalArgumentException(id + " needs exactly 4 fragments, got " + frags.size());
            }
            Set<Integer> ids = new TreeSet<>();
            for (ReferenceCrop frag : frags) {
                ids.add(frag.fragmentId());
            }
            if (!ids.equals(Set.of(1, 2, 3, 4))) {
                throw new IllegalArgumentException(id + " needs fragment ids [1,2,3,4], got " + ids);
            }
        }
        Set<String> paths = new HashSet<>();
        for (ReferenceCrop crop : crops) {
            if (!paths.add(crop.outputPath())) {
                throw new IllegalArgumentException("Duplicate output_path: " + crop.outputPath());
            }
            if (crop.x() + crop.width() > sourceWidth || crop.y() + crop.height() > sourceHeight) {
                throw new IllegalArgumentException("Crop outside source image: " + crop);
            }
        }
    }

}
