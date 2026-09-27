package io.github.bohdankordon.casinofingerprint.dataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * Regenerates the 20 canonical reference assets from the source collage and manifest.
 *
 * <p>Canonical assets are raw crops: original pixels, resolution and color channels are
 * preserved with no resizing, filtering or normalization.
 */
public final class ReferenceDatasetGenerator {
    static final int EXPECTED_WIDTH = 1500;
    static final int EXPECTED_HEIGHT = 1900;
    static final String SOURCE_REL = "dataset/source/casino-fingerprints-reference.png";
    static final String MANIFEST_REL = "dataset/layout/reference-layout.csv";

    private ReferenceDatasetGenerator() {
    }

    /**
     * Runs the generator against {@code user.dir}, or {@code args[0]} when a project root is given.
     */
    public static void main(String[] args) throws IOException {
        Path projectRoot = args.length > 0
                ? Path.of(args[0])
                : Path.of(System.getProperty("user.dir"));
        generate(projectRoot);
    }

    /** Loads, validates, crops and writes every manifest asset under {@code projectRoot}. */
    public static void generate(Path projectRoot) throws IOException {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        Loader.load(opencv_core.class);
        Path sourcePath = projectRoot.resolve(SOURCE_REL);
        Path manifestPath = projectRoot.resolve(MANIFEST_REL);
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalStateException("Source image not found: " + sourcePath);
        }
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("Manifest not found: " + manifestPath);
        }
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        try (Mat source = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
            if (source.empty()) {
                throw new IllegalStateException("OpenCV could not decode source image: " + sourcePath);
            }
            if (source.cols() != EXPECTED_WIDTH || source.rows() != EXPECTED_HEIGHT) {
                throw new IllegalStateException("Source must be " + EXPECTED_WIDTH + "x" + EXPECTED_HEIGHT
                        + " but was " + source.cols() + "x" + source.rows());
            }
            for (ReferenceCrop crop : crops) {
                writeCrop(source, projectRoot, crop);
            }
        }
        System.out.println("Reference dataset generated: " + crops.size() + " assets from " + sourcePath);
    }

    private static void writeCrop(Mat source, Path projectRoot, ReferenceCrop crop) {
        if (crop.x() + crop.width() > source.cols() || crop.y() + crop.height() > source.rows()) {
            throw new IllegalArgumentException("Crop outside source image: " + crop);
        }
        Path output = projectRoot.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
        try {
            Files.createDirectories(output.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create output directory: " + output.getParent(), e);
        }
        Rect rect = new Rect(crop.x(), crop.y(), crop.width(), crop.height());
        try (Mat view = new Mat(source, rect);
                Mat owned = view.clone()) {
            if (!opencv_imgcodecs.imwrite(output.toString(), owned)) {
                throw new IllegalStateException("OpenCV could not write canonical asset: " + output);
            }
        }
    }
}
