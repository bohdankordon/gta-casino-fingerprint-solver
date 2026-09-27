package io.github.bohdankordon.casinofingerprint.capture;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.util.Objects;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Converts one captured {@link BufferedImage} into an OpenCV {@code CV_8UC3} BGR frame.
 *
 * <p>Production captures are full-screen images, so the conversion avoids a Java per-pixel loop:
 * the image is brought to {@code TYPE_3BYTE_BGR} (whose byte layout is already B, G, R) with the
 * AWT raster pipeline, the backing byte array is wrapped in a temporary {@code Mat} header and
 * then cloned, so the returned matrix owns a private copy of the pixels.
 *
 * <p>Ownership: the returned Mat is independently owned by the caller and must be closed. It
 * keeps no reference to the image, and the image is never modified, so closing one never affects
 * the other.
 */
public final class BufferedImageMatConverter {
    private BufferedImageMatConverter() {
    }

    /**
     * @param image captured pixels of any common image type; not modified, not retained
     * @return caller-owned {@code CV_8UC3} BGR Mat with the same width and height
     */
    public static Mat toBgrMat(BufferedImage image) {
        Objects.requireNonNull(image, "image");
        BufferedImage bgr = threeByteBgr(image);
        Raster raster = bgr.getRaster();
        DataBufferByte buffer = (DataBufferByte) raster.getDataBuffer();
        byte[] data = buffer.getData();
        int width = bgr.getWidth();
        int height = bgr.getHeight();
        SampleModel sampleModel = raster.getSampleModel();
        if (!(sampleModel instanceof ComponentSampleModel componentSampleModel)) {
            throw new IllegalStateException("TYPE_3BYTE_BGR needs a component sample model, got "
                    + sampleModel.getClass().getName());
        }
        int stride = componentSampleModel.getScanlineStride();
        long offset = (long) buffer.getOffset()
                + (long) raster.getSampleModelTranslateY() * stride
                + (long) raster.getSampleModelTranslateX() * 3L;
        long lastByte = offset + (long) stride * (height - 1) + (long) width * 3L;
        if (stride < width * 3 || lastByte > data.length) {
            throw new IllegalStateException("Unexpected TYPE_3BYTE_BGR raster layout: stride "
                    + stride + ", " + data.length + " bytes for " + width + "x" + height);
        }
        BytePointer pixels = new BytePointer(data).position(offset);
        try (Mat header = new Mat(height, width, opencv_core.CV_8UC3, pixels, stride)) {
            return header.clone();
        }
    }

    /**
     * Returns an image with the {@code TYPE_3BYTE_BGR} layout, either the input itself when it is
     * already a stand-alone image of that layout, or a fresh copy produced by the AWT raster
     * pipeline otherwise.
     */
    private static BufferedImage threeByteBgr(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR && image.getRaster().getParent() == null) {
            return image;
        }
        BufferedImage converted =
                new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = converted.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return converted;
    }
}
