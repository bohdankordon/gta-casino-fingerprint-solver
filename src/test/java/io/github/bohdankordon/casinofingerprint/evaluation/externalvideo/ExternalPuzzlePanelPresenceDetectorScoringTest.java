package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Synthetic presence scoring checks: pure score vectors, no images, no recordings.
 */
class ExternalPuzzlePanelPresenceDetectorScoringTest {
    @Test
    void fourBrightCalmGreenIsPresent() {
        ExternalPanelPresenceResult result = ExternalPuzzlePanelPresenceDetector.decide(
                new int[]{180, 190, 170, 200}, 0);
        assertEquals(ExternalPanelPresence.PRESENT, result.presence());
        assertEquals(4, result.brightCount());
    }

    @Test
    void threeBrightCalmGreenIsPresent() {
        ExternalPanelPresenceResult result = ExternalPuzzlePanelPresenceDetector.decide(
                new int[]{133, 133, 151, 40}, -1);
        assertEquals(ExternalPanelPresence.PRESENT, result.presence());
    }

    @Test
    void greenOverlayForcesAbsentEvenWhenBright() {
        ExternalPanelPresenceResult result = ExternalPuzzlePanelPresenceDetector.decide(
                new int[]{134, 134, 151, 153}, 21);
        assertEquals(ExternalPanelPresence.ABSENT, result.presence());
    }

    @Test
    void darkChromeIsAbsent() {
        ExternalPanelPresenceResult result = ExternalPuzzlePanelPresenceDetector.decide(
                new int[]{37, 97, 40, 50}, -12);
        assertEquals(ExternalPanelPresence.ABSENT, result.presence());
    }

    @Test
    void splitChromeIsAmbiguous() {
        ExternalPanelPresenceResult result = ExternalPuzzlePanelPresenceDetector.decide(
                new int[]{150, 150, 40, 30}, 0);
        assertEquals(ExternalPanelPresence.AMBIGUOUS, result.presence());
    }

    @Test
    void badVectorsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalPuzzlePanelPresenceDetector.decide(new int[]{1, 2, 3}, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalPuzzlePanelPresenceDetector.decide(new int[]{1, 2, 3, 300}, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalPuzzlePanelPresenceDetector.decide(new int[]{1, 2, 3, 4}, 300));
    }
}
