package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.PhysicalDisplayMode;
import io.github.bohdankordon.casinofingerprint.capture.ScreenBounds;
import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ARM eligibility stays explainable without windows: every missing piece is
 * named, exactly one supported monitor may preselect, and known abort-key
 * conflicts always surface.
 */
class OperatorArmPolicyTest {
    private static final MonitorInfo SUPPORTED =
            monitor(0, true, 2560, 1440);
    private static final MonitorInfo SECOND_SUPPORTED =
            monitor(1, false, 2560, 1440);
    private static final MonitorInfo UNSUPPORTED =
            monitor(2, false, 1920, 1080);

    @Test
    void completeConfigurationArms() {
        OperatorArmPolicy.Eligibility eligibility = OperatorArmPolicy.check(
                SUPPORTED, "GTA5_Enhanced.exe", EmergencyAbortKey.F4, false);
        assertTrue(eligibility.allowed(), eligibility.explain());
        assertTrue(eligibility.issues().isEmpty());
    }

    @Test
    void missingMonitorNamesSupportedDisplay() {
        OperatorArmPolicy.Eligibility eligibility = OperatorArmPolicy.check(
                null, "GTA5_Enhanced.exe", EmergencyAbortKey.F4, false);
        assertFalse(eligibility.allowed());
        assertTrue(eligibility.explain().contains("2560x1440"), eligibility.explain());
    }

    @Test
    void unsupportedMonitorRefusesArm() {
        OperatorArmPolicy.Eligibility eligibility = OperatorArmPolicy.check(
                UNSUPPORTED, "GTA5_Enhanced.exe", EmergencyAbortKey.F4, false);
        assertFalse(eligibility.allowed());
        assertTrue(eligibility.explain().contains("2560x1440"), eligibility.explain());
    }

    @Test
    void blankTargetNamesTargetExecutable() {
        OperatorArmPolicy.Eligibility eligibility = OperatorArmPolicy.check(
                SUPPORTED, "   ", EmergencyAbortKey.F4, false);
        assertFalse(eligibility.allowed());
        assertTrue(eligibility.explain().contains("target executable"), eligibility.explain());
    }

    @Test
    void missingAbortKeyNamesAbortKey() {
        OperatorArmPolicy.Eligibility eligibility = OperatorArmPolicy.check(
                SUPPORTED, "GTA5_Enhanced.exe", null, false);
        assertFalse(eligibility.allowed());
        assertTrue(eligibility.explain().contains("emergency abort key"), eligibility.explain());
    }

    @Test
    void terminatedSessionLocksReArmWithRestartMessage() {
        OperatorArmPolicy.Eligibility eligibility = OperatorArmPolicy.check(
                SUPPORTED, "GTA5_Enhanced.exe", EmergencyAbortKey.F4, true);
        assertFalse(eligibility.allowed());
        assertTrue(eligibility.explain().contains("Restart the application"),
                eligibility.explain());
    }

    @Test
    void exactlyOneSupportedMonitorPreselects() {
        Optional<MonitorInfo> preselected = OperatorArmPolicy.preselectCandidate(
                List.of(SUPPORTED, UNSUPPORTED));
        assertEquals(Optional.of(SUPPORTED), preselected);
    }

    @Test
    void zeroSupportedMonitorsNeedExplicitChoice() {
        assertEquals(Optional.empty(),
                OperatorArmPolicy.preselectCandidate(List.of(UNSUPPORTED)));
    }

    @Test
    void severalSupportedMonitorsNeedExplicitChoice() {
        assertEquals(Optional.empty(), OperatorArmPolicy.preselectCandidate(
                List.of(SUPPORTED, SECOND_SUPPORTED)));
    }

    @Test
    void documentedAbortConflictSurfacesImmediately() {
        Optional<String> warning =
                OperatorArmPolicy.abortConflictText(EmergencyAbortKey.F12);
        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("F12"), warning.get());
        assertTrue(warning.get().contains("Steam"), warning.get());
    }

    @Test
    void cleanAbortKeyHasNoWarning() {
        assertEquals(Optional.empty(),
                OperatorArmPolicy.abortConflictText(EmergencyAbortKey.F4));
        assertEquals(Optional.empty(), OperatorArmPolicy.abortConflictText(null));
    }

    @Test
    void defaultTargetIsTheValidatedExecutable() {
        assertEquals("GTA5_Enhanced.exe",
                OperatorArmPolicy.DEFAULT_TARGET_EXECUTABLE);
    }

    @Test
    void uniqueSupportedMonitorSelectsItsComboIndex() {
        assertEquals(0, OperatorArmPolicy.refreshSelectionIndex(
                List.of(SUPPORTED, UNSUPPORTED)));
        assertEquals(1, OperatorArmPolicy.refreshSelectionIndex(
                List.of(UNSUPPORTED, SUPPORTED)));
    }

    @Test
    void severalSupportedMonitorsLeaveComboUnselected() {
        assertEquals(-1, OperatorArmPolicy.refreshSelectionIndex(
                List.of(SUPPORTED, SECOND_SUPPORTED)));
    }

    @Test
    void zeroSupportedMonitorsLeaveComboUnselected() {
        assertEquals(-1, OperatorArmPolicy.refreshSelectionIndex(
                List.of(UNSUPPORTED)));
        assertEquals(-1, OperatorArmPolicy.refreshSelectionIndex(List.of()));
    }

    @Test
    void emptySelectionKeepsArmIneligible() {
        OperatorArmPolicy.Eligibility eligibility = OperatorArmPolicy.check(
                null, "GTA5_Enhanced.exe", EmergencyAbortKey.F4, false);
        assertFalse(eligibility.allowed());
    }

    private static MonitorInfo monitor(int index, boolean primary, int physicalWidth,
            int physicalHeight) {
        return new MonitorInfo(index, "DISPLAY-" + (index + 1), primary,
                new ScreenBounds(0, 0, 2048, 1152),
                new PhysicalDisplayMode(physicalWidth, physicalHeight, 59.94));
    }
}
