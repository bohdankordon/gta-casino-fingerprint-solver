package io.github.bohdankordon.casinofingerprint.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.image.BufferedImage;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The BufferedImage to Mat conversion of captured frames: channel layout, known-pixel ordering and
 * independent ownership.
 */
class BufferedImageMatConverterTest {
    @BeforeAll
    static void loadNativeLibrary() {
        org.bytedeco.javacpp.Loader.load(opencv_core.class);
    }

    @Test
    void convertsToThreeChannelEightBitMatOfTheSameSize() {
        BufferedImage image = new BufferedImage(7, 3, BufferedImage.TYPE_INT_RGB);
        try (Mat mat = BufferedImageMatConverter.toBgrMat(image)) {
            assertEquals(7, mat.cols(), "Width");
            assertEquals(3, mat.rows(), "Height");
            assertEquals(opencv_core.CV_8UC3, mat.type(), "Type");
            assertEquals(3, mat.channels(), "Channels");
            assertEquals(opencv_core.CV_8U, mat.depth(), "Depth");
        }
    }

    @Test
    void knownPixelsKeepTheirBgrChannelOrder() {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x123456);
        image.setRGB(1, 0, 0xFFFFFF);
        image.setRGB(2, 0, 0x000000);

        try (Mat mat = BufferedImageMatConverter.toBgrMat(image);
                UByteIndexer indexer = mat.createIndexer()) {
            assertEquals(0x56, indexer.get(0, 0, 0), "Blue channel of 0x123456");
            assertEquals(0x34, indexer.get(0, 0, 1), "Green channel of 0x123456");
            assertEquals(0x12, indexer.get(0, 0, 2), "Red channel of 0x123456");
            assertEquals(255, indexer.get(0, 1, 0), "Blue of white");
            assertEquals(255, indexer.get(0, 1, 1), "Green of white");
            assertEquals(255, indexer.get(0, 1, 2), "Red of white");
            assertEquals(0, indexer.get(0, 2, 0), "Blue of black");
            assertEquals(0, indexer.get(0, 2, 1), "Green of black");
            assertEquals(0, indexer.get(0, 2, 2), "Red of black");
        }
    }

    @Test
    void convertsImageTypesThatAreNotThreeByteBgr() {
        BufferedImage argb = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        argb.setRGB(0, 0, 0xFF336699);
        argb.setRGB(1, 0, 0xFF808080);

        try (Mat mat = BufferedImageMatConverter.toBgrMat(argb);
                UByteIndexer indexer = mat.createIndexer()) {
            assertEquals(opencv_core.CV_8UC3, mat.type(), "Type");
            assertEquals(0x99, indexer.get(0, 0, 0), "Blue of opaque 0x336699");
            assertEquals(0x66, indexer.get(0, 0, 1), "Green of opaque 0x336699");
            assertEquals(0x33, indexer.get(0, 0, 2), "Red of opaque 0x336699");
            assertEquals(0x80, indexer.get(0, 1, 0), "Blue of gray");
        }
    }

    @Test
    void returnedMatOwnsIndependentBytes() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        fill(image, 0x204060);
        int expectedBlue = 0x60;
        int expectedGreen = 0x40;
        int expectedRed = 0x20;

        Mat mat = BufferedImageMatConverter.toBgrMat(image);
        try {
            fill(image, 0xFFFFFF);
            try (UByteIndexer indexer = mat.createIndexer()) {
                assertEquals(expectedBlue, indexer.get(3, 3, 0), "Blue unchanged by the source image");
                assertEquals(expectedGreen, indexer.get(3, 3, 1), "Green unchanged by the source");
                assertEquals(expectedRed, indexer.get(3, 3, 2), "Red unchanged by the source");
            }
        } finally {
            mat.close();
        }
        assertEquals(0xFFFFFF, image.getRGB(0, 0) & 0xFFFFFF, "The source image is still usable");
    }

    @Test
    void distinctConversionsDoNotSharePixelMemory() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        fill(image, 0x010203);
        try (Mat first = BufferedImageMatConverter.toBgrMat(image);
                Mat second = BufferedImageMatConverter.toBgrMat(image)) {
            assertNotEquals(first.data().address(), second.data().address(),
                    "Each conversion owns its own pixel buffer");
            assertEquals(0x03, first.data().get(0) & 0xFF, "First conversion keeps its first pixel");
        }
    }

    private static void fill(BufferedImage image, int rgb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, rgb);
            }
        }
    }
}
