package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import java.util.Arrays;
import java.util.Objects;

/**
 * Evaluation-only panel-presence reading: plain-data evidence that the fingerprint panel is
 * present, absent, or ambiguous on one frame, independent of solver recognition.
 *
 * <p>Carries the four static-chrome anchor means plus the central green-excess score that
 * together decide presence. The tracker consumes only the presence enum; the scores stay
 * diagnostic for characterization, CSV notes, and manual review. Image-free by construction.
 *
 * @param presence decided presence state; never null
 * @param anchorMeans four static-chrome anchor gray means, 0..255, in fixed order
 * @param greenExcess central green excess mean, G minus R, -255..255
 * @param brightCount anchors at or above the bright floor, 0..4
 * @param detail short human-readable evidence note; never null
 */
public record ExternalPanelPresenceResult(ExternalPanelPresence presence, int[] anchorMeans,
        int greenExcess, int brightCount, String detail) {
    public ExternalPanelPresenceResult {
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(anchorMeans, "anchorMeans");
        Objects.requireNonNull(detail, "detail");
        if (anchorMeans.length != 4) {
            throw new IllegalArgumentException("Presence needs four anchor means");
        }
        int bright = 0;
        for (int mean : anchorMeans) {
            if (mean < 0 || mean > 255) {
                throw new IllegalArgumentException("Anchor mean must be 0..255, got " + mean);
            }
            if (mean >= ExternalPuzzlePanelPresenceDetector.BRIGHT_FLOOR) {
                bright++;
            }
        }
        if (brightCount != bright) {
            throw new IllegalArgumentException("brightCount " + brightCount
                    + " disagrees with anchor means " + Arrays.toString(anchorMeans));
        }
        if (greenExcess < -255 || greenExcess > 255) {
            throw new IllegalArgumentException("greenExcess must be -255..255");
        }
        anchorMeans = anchorMeans.clone();
    }

    @Override
    public int[] anchorMeans() {
        return anchorMeans.clone();
    }
}
