package io.github.bohdankordon.casinofingerprint.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Physical-resolution variant selection: an exact 2560x1440 variant wins over the 2048x1152
 * logical one a 125% scaled desktop reports, and a logical-only capture is rejected instead of
 * being resized into the layout.
 */
class ResolutionVariantSelectorTest {
    private static final Resolution REQUIRED = new Resolution(2560, 1440);
    private static final Resolution LOGICAL = new Resolution(2048, 1152);

    /** A 2560x1440 panel at 125% scaling: 2048x1152 logical bounds, 2560x1440 physical mode. */
    private static final MonitorInfo SCALED_MONITOR = new MonitorInfo(
            0,
            "DISPLAY-1",
            true,
            new ScreenBounds(0, 0, LOGICAL.width(), LOGICAL.height()),
            new PhysicalDisplayMode(2560, 1440, 59.94));

    @Test
    void exactPhysicalVariantWinsOverTheLogicalVariant() {
        ResolutionVariant logical = variant(LOGICAL.width(), LOGICAL.height());
        ResolutionVariant physical = variant(2560, 1440);

        ResolutionVariant selected =
                ResolutionVariantSelector.selectExact(List.of(logical, physical), SCALED_MONITOR, REQUIRED);

        assertSame(physical, selected, "The physical variant is selected");
        assertEquals(REQUIRED, selected.resolution(), "Selected resolution");
    }

    @Test
    void selectionDoesNotDependOnVariantOrder() {
        ResolutionVariant logical = variant(LOGICAL.width(), LOGICAL.height());
        ResolutionVariant physical = variant(2560, 1440);

        ResolutionVariant selected =
                ResolutionVariantSelector.selectExact(List.of(physical, logical), SCALED_MONITOR, REQUIRED);

        assertSame(physical, selected, "The physical variant is selected whatever the order");
    }

    @Test
    void logicalOnlyCaptureIsRejectedInsteadOfResized() {
        ResolutionVariant logical = variant(LOGICAL.width(), LOGICAL.height());

        UnsupportedResolutionException failure = assertThrows(UnsupportedResolutionException.class,
                () -> ResolutionVariantSelector.selectExact(List.of(logical), SCALED_MONITOR, REQUIRED));

        assertEquals(SCALED_MONITOR, failure.monitor(), "Monitor recorded");
        assertEquals(REQUIRED, failure.required(), "Required resolution recorded");
        assertEquals(List.of(LOGICAL), failure.returnedVariants(), "Returned variants recorded");
        String message = failure.getMessage();
        assertTrue(message.contains("2560x1440"), "Diagnostic names the required resolution: " + message);
        assertTrue(message.contains("2048x1152"), "Diagnostic names the returned variant: " + message);
        assertTrue(message.contains("scaling"), "Diagnostic explains the DPI cause: " + message);
        assertTrue(message.contains("never"), "Diagnostic states the refusal to resize: " + message);
    }

    @Test
    void variantsOfAnyOtherSizeAreRejectedAsWell() {
        List<ResolutionVariant> variants = List.of(
                variant(3840, 2160),
                variant(1920, 1080),
                variant(LOGICAL.width(), LOGICAL.height()));

        UnsupportedResolutionException failure = assertThrows(UnsupportedResolutionException.class,
                () -> ResolutionVariantSelector.selectExact(variants, SCALED_MONITOR, REQUIRED));

        assertEquals(List.of(new Resolution(3840, 2160), new Resolution(1920, 1080), LOGICAL),
                failure.returnedVariants(), "Every returned variant is reported");
    }

    @Test
    void captureWithoutVariantsIsReported() {
        UnsupportedResolutionException failure = assertThrows(UnsupportedResolutionException.class,
                () -> ResolutionVariantSelector.selectExact(List.of(), SCALED_MONITOR, REQUIRED));

        assertTrue(failure.getMessage().contains("(none returned)"),
                "Diagnostic mentions the empty variant list: " + failure.getMessage());
    }

    private static ResolutionVariant variant(int width, int height) {
        return new ResolutionVariant(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
    }
}
