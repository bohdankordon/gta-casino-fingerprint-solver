package io.github.bohdankordon.casinofingerprint.runtime;

/**
 * A captured frame does not match the frame size the selected gameplay layout was measured
 * against. Live runtimes report this as the UNSUPPORTED_FRAME state.
 *
 * <p>On a Windows desktop with display scaling this is a normal runtime condition rather than a
 * backend bug: a capture can come back as a logical-resolution image (2048x1152 for a 2560x1440
 * panel at 125% scaling). The frame is rejected before ROI extraction and is never resized,
 * because the Stage 2 coordinates are physical pixels and scaling the image would silently move
 * every region.
 */
public final class UnsupportedFrameSizeException extends RuntimeException {
    private final int expectedWidth;
    private final int expectedHeight;
    private final int actualWidth;
    private final int actualHeight;

    public UnsupportedFrameSizeException(
            int expectedWidth, int expectedHeight, int actualWidth, int actualHeight) {
        super("Captured frame is " + actualWidth + "x" + actualHeight
                + " but the selected gameplay layout requires "
                + expectedWidth + "x" + expectedHeight + ". " + diagnostic(actualWidth, expectedWidth));
        this.expectedWidth = expectedWidth;
        this.expectedHeight = expectedHeight;
        this.actualWidth = actualWidth;
        this.actualHeight = actualHeight;
    }

    public int expectedWidth() {
        return expectedWidth;
    }

    public int expectedHeight() {
        return expectedHeight;
    }

    public int actualWidth() {
        return actualWidth;
    }

    public int actualHeight() {
        return actualHeight;
    }

    private static String diagnostic(int actualWidth, int expectedWidth) {
        if (actualWidth < expectedWidth) {
            return "A smaller frame usually means the capture returned a DPI-scaled "
                    + "logical-resolution image; this runtime refuses to resize it into the "
                    + "physical layout and takes no decision for it.";
        }
        return "The frame is not the supported physical resolution; it is not resized and no "
                + "decision is taken for it.";
    }
}
