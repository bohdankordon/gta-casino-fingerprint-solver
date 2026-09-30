package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.3 dedicated Tab plan: the fixed SCANCODE_BATCH Tab strokes pin the exact
 * {@code wVk}/{@code wScan}/flags contract, while the general control mapping keeps
 * refusing PROCEED. Pure data only: no test here touches a native library.
 */
class ProceedDiagnosticPlanTest {
    @Test
    void tabScanCodeConstantIsPinned() {
        assertEquals(0x0F, ScanCodeSpec.TAB_PROCEED, "Tab Set-1 make code is 0x0F");
    }

    @Test
    void dedicatedTabMappingIsNonExtended() {
        ScanCodeSpec tab = ScanCodeSpec.tabForProceedProbe();
        assertEquals(0x0F, tab.scanCode(), "Tab make code");
        assertFalse(tab.extended(), "Tab carries no E0 prefix");
    }

    @Test
    void generalControlMappingStillRefusesProceed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScanCodeSpec.forControl(GameControl.PROCEED));
        assertTrue(error.getMessage().contains("PROCEED"), error.getMessage());
        assertTrue(error.getMessage().contains("Tab"), error.getMessage());
    }

    @Test
    void generalPlanFactoryStillRefusesProceed() {
        for (DiagnosticInputDeliveryMode mode : DiagnosticInputDeliveryMode.values()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DiagnosticDeliveryPlan.forTap(GameControl.PROCEED, mode,
                            mode.holds() ? 50 : 0),
                    mode + " must refuse PROCEED");
            assertTrue(error.getMessage().contains("Tab"), mode + ": " + error.getMessage());
        }
    }

    @Test
    void proceedPlanIsFixedScanCodeBatchWithNoHold() {
        ProceedDiagnosticPlan plan = ProceedDiagnosticPlan.proceedProbe();
        assertEquals(DiagnosticInputDeliveryMode.SCANCODE_BATCH, plan.mode(), "Fixed mode");
        assertEquals(0, plan.holdMillis(), "BATCH carries no hold");
        assertTrue(plan.batched(), "One batch");
    }

    @Test
    void tabDownStrokeMapping() {
        DiagnosticKeyStroke down = ProceedDiagnosticPlan.proceedProbe().down();
        assertEquals(0, down.wVk(), "SCANCODE ignores wVk, which stays zero");
        assertEquals(0x0F, down.wScan(), "Tab Set-1 make code");
        assertTrue(down.usesScanCode(), "SCANCODE set");
        assertTrue((down.flags() & DiagnosticKeyStroke.KEYEVENTF_SCANCODE) != 0, "SCANCODE flag");
        assertFalse(down.extended(), "EXTENDED not set for Tab");
        assertFalse(down.keyUp(), "KEYUP not set for key-down");
        assertEquals(DiagnosticKeyStroke.KEYEVENTF_SCANCODE, down.flags(),
                "down flags are exactly SCANCODE");
    }

    @Test
    void tabUpStrokeMapping() {
        DiagnosticKeyStroke up = ProceedDiagnosticPlan.proceedProbe().up();
        assertEquals(0, up.wVk(), "wVk stays zero");
        assertEquals(0x0F, up.wScan(), "same Tab make code");
        assertTrue(up.usesScanCode(), "SCANCODE set");
        assertTrue(up.keyUp(), "KEYUP set for release");
        assertFalse(up.extended(), "EXTENDED not set for Tab release");
        assertEquals(DiagnosticKeyStroke.KEYEVENTF_SCANCODE | DiagnosticKeyStroke.KEYEVENTF_KEYUP,
                up.flags(), "up flags are exactly SCANCODE|KEYUP");
    }

    @Test
    void downAndUpShareTheSameTabIdentity() {
        ProceedDiagnosticPlan plan = ProceedDiagnosticPlan.proceedProbe();
        assertEquals(plan.down().wScan(), plan.up().wScan(), "Same scan code");
        assertEquals(0, plan.down().wVk(), "wVk zero");
        assertEquals(0, plan.up().wVk(), "wVk zero");
    }

    @Test
    void planNeverUsesVkTab() {
        ProceedDiagnosticPlan plan = ProceedDiagnosticPlan.proceedProbe();
        assertFalse(plan.down().wVk() == 0x09, "down is not VK_TAB");
        assertFalse(plan.up().wVk() == 0x09, "up is not VK_TAB");
    }

    @Test
    void planRecordRejectsContradictoryShapes() {
        DiagnosticKeyStroke down = ProceedDiagnosticPlan.proceedProbe().down();
        DiagnosticKeyStroke up = ProceedDiagnosticPlan.proceedProbe().up();
        assertThrows(IllegalArgumentException.class,
                () -> new ProceedDiagnosticPlan(DiagnosticInputDeliveryMode.SCANCODE_HOLD, down,
                        up, 50),
                "only SCANCODE_BATCH is allowed");
        assertThrows(IllegalArgumentException.class,
                () -> new ProceedDiagnosticPlan(DiagnosticInputDeliveryMode.SCANCODE_BATCH, down,
                        up, 50),
                "a hold contradicts the no-sleep batch");
        assertThrows(IllegalArgumentException.class,
                () -> new ProceedDiagnosticPlan(DiagnosticInputDeliveryMode.SCANCODE_BATCH, up,
                        up, 0),
                "the down stroke cannot be a release");
        assertThrows(IllegalArgumentException.class,
                () -> new ProceedDiagnosticPlan(DiagnosticInputDeliveryMode.SCANCODE_BATCH, down,
                        down, 0),
                "the up stroke must be a release");
    }
}
