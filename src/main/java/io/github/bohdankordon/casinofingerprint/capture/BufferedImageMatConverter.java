package io.github.bohdankordon.casinofingerprint.capture;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.util.Objects;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Converts one captured {@link BufferedImage} into an OpenCV {@code CV_8UC3} BGR frame.
 *
 * <p>Production captures are full-screen images, so the conversion avoids a Java per-pixel loop:
 * the image is brought to a plain {@code TYPE_3BYTE_BGR} layout (whose byte order already is
 * B, G, R) with the AWT raster pipeline, the backing byte array is wrapped in a temporary
 * {@code Mat} header and then cloned, so the returned matrix owns a private copy of the pixels.
 *
 * <p>Three short-lived resources take part in one conversion, in a fixed order: a native
 * {@link BytePointer} holding a copy of the image bytes, a {@code Mat} header that only borrows
 * that pointer, and the cloned {@code Mat} that owns real native storage. JavaCPP's
 * {@code BytePointer(byte[])} constructor allocates native memory and copies the array into it - it
 * is not a view of the Java array - so both temporaries are held in try-with-resources blocks and
 * closed before the method returns, the header before the pointer it borrows. Nothing from a
 * conversion call has to be garbage collected before the temporary pixel buffer is released.
 *
 * <p>Ownership: the returned Mat is independently owned by the caller and must be closed. It keeps
 * no reference to the image or to the temporaries, and the image is never modified, so closing one
 * never affects the other.
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
        BufferedImage bgr = plainThreeByteBgr(image);
        int width = bgr.getWidth();
        int height = bgr.getHeight();
        byte[] data = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        long stride = (long) width * 3L;
        if (data.length != stride * height) {
            throw new IllegalStateException("Unexpected TYPE_3BYTE_BGR buffer size: "
                    + data.length + " bytes for " + width + "x" + height);
        }
        // The pointer owns native storage, and the header only borrows it, so the header is closed
        // first and the pointer immediately after; the clone taken inside owns its own pixels.
        try (BytePointer pixels = new BytePointer(data)) {
            try (Mat header = new Mat(height, width, opencv_core.CV_8UC3, pixels, stride)) {
                return header.clone();
            }
        }
    }

    /**
     * Returns an image with the plain {@code TYPE_3BYTE_BGR} layout: one zero-offset byte buffer
     * whose row stride is exactly {@code 3 * width}. The input itself is returned when it already
     * is such a stand-alone image; every other image - another type, a subimage, or a padded or
     * translated raster - is copied through the AWT raster pipeline, so the raw byte layout read
     * by {@link #toBgrMat} is exact instead of assumed.
     */
    private static BufferedImage plainThreeByteBgr(BufferedImage image) {
        if (isPlainThreeByteBgr(image)) {
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

    /** Whether {@code image} already carries exactly the byte layout read by {@link #toBgrMat}. */
    private static boolean isPlainThreeByteBgr(BufferedImage image) {
        Raster raster = image.getRaster();
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR || raster.getParent() != null) {
            return false;
        }
        if (!(raster.getSampleModel() instanceof ComponentSampleModel sampleModel)) {
            return false;
        }
        if (raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0) {
            return false;
        }
        DataBuffer dataBuffer = raster.getDataBuffer();
        return dataBuffer.getDataType() == DataBuffer.TYPE_BYTE
                && dataBuffer.getOffset() == 0
                && sampleModel.getScanlineStride() == image.getWidth() * 3
                && dataBuffer.getSize() == (long) image.getWidth() * image.getHeight() * 3L;
    }
}
