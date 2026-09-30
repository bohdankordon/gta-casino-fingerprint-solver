package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsSendInputSink;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.2 pure delivery plans: the VK_BATCH plan is exactly the production baseline
 * representation, the scan-code plans pin the exact {@code wVk}/{@code wScan}/flags, the hold
 * contract is enforced per mode, and PROCEED has no plan in any mode.
 */
class DiagnosticDeliveryPlanTest {
    private static final List<GameControl> ALLOWED = List.of(GameControl.UP, GameControl.DOWN,
            GameControl.LEFT, GameControl.RIGHT, GameControl.SELECT);
    private static final List<GameControl> ARROWS = List.of(GameControl.UP, GameControl.DOWN,
            GameControl.LEFT, GameControl.RIGHT);

    @Test
    void holdDefaultsAndBoundsArePinned() {
        assertEquals(50, DiagnosticDeliveryPlan.DEFAULT_HOLD_MILLIS, "default hold is 50 ms");
        assertEquals(10, DiagnosticDeliveryPlan.MIN_HOLD_MILLIS, "lower bound");
        assertEquals(200, DiagnosticDeliveryPlan.MAX_HOLD_MILLIS, "upper bound");
    }

    @Test
    void vkBatchPlanIsExactlyTheProductionBaselineRepresentation() {
        for (GameControl control : ALLOWED) {
            DiagnosticDeliveryPlan plan = DiagnosticDeliveryPlan.forTap(control,
                    DiagnosticInputDeliveryMode.VK_BATCH, 0);
            assertTrue(plan.batched(), control + " is one batch");
            assertEquals(0, plan.holdMillis(), "BATCH modes carry no hold");
            int productionVirtualKey = WindowsSendInputSink.virtualKey(control);
            assertEquals(productionVirtualKey, plan.down().wVk(),
                    control + " key-down must use the production virtual key");
            assertEquals(productionVirtualKey, plan.up().wVk(),
                    control + " key-up must use the production virtual key");
            assertEquals(0, plan.down().wScan(), control + " key-down wScan");
            assertEquals(0, plan.up().wScan(), control + " key-up wScan");
            assertEquals(0, plan.down().flags(), control + " key-down has no flags");
            assertEquals(DiagnosticKeyStroke.KEYEVENTF_KEYUP, plan.up().flags(),
                    control + " key-up is a release");
            assertFalse(plan.down().keyUp(), control + " down stroke is a key-down");
            assertTrue(plan.up().keyUp(), control + " up stroke is a key-up");
        }
    }

    @Test
    void vkHoldPlanKeepsTheProductionVirtualKeysAndCarriesTheExplicitHold() {
        for (GameControl control : ALLOWED) {
            DiagnosticDeliveryPlan plan = DiagnosticDeliveryPlan.forTap(control,
                    DiagnosticInputDeliveryMode.VK_HOLD, 50);
            assertFalse(plan.batched(), control + " holds");
            assertEquals(50, plan.holdMillis(), control + " explicit hold");
            assertEquals(WindowsSendInputSink.virtualKey(control), plan.down().wVk(),
                    control + " key-down virtual key");
            assertEquals(WindowsSendInputSink.virtualKey(control), plan.up().wVk(),
                    control + " key-up virtual key");
            assertFalse(plan.down().usesScanCode(), control + " is not a scan-code plan");
            assertFalse(plan.down().extended(), control + " carries no extended bit");
        }
    }

    @Test
    void scancodeBatchPlanZeroesTheVirtualKeyAndPinsTheFlags() {
        for (GameControl control : ALLOWED) {
            ScanCodeSpec spec = ScanCodeSpec.forControl(control);
            DiagnosticDeliveryPlan plan = DiagnosticDeliveryPlan.forTap(control,
                    DiagnosticInputDeliveryMode.SCANCODE_BATCH, 0);
            assertTrue(plan.batched(), control + " is one batch");
            assertEquals(0, plan.down().wVk(), control + " wVk must stay zero");
            assertEquals(0, plan.up().wVk(), control + " wVk must stay zero");
            assertEquals(spec.scanCode(), plan.down().wScan(), control + " key-down scan code");
            assertEquals(spec.scanCode(), plan.up().wScan(), control + " key-up scan code");
            int downFlags = DiagnosticKeyStroke.KEYEVENTF_SCANCODE
                    | (spec.extended() ? DiagnosticKeyStroke.KEYEVENTF_EXTENDEDKEY : 0);
            assertEquals(downFlags, plan.down().flags(), control + " key-down flags");
            assertEquals(downFlags | DiagnosticKeyStroke.KEYEVENTF_KEYUP, plan.up().flags(),
                    control + " key-up flags");
        }
    }

    @Test
    void scancodeHoldPlanCarriesTheSameStrokesPlusTheHold() {
        for (GameControl control : ALLOWED) {
            DiagnosticDeliveryPlan batch = DiagnosticDeliveryPlan.forTap(control,
                    DiagnosticInputDeliveryMode.SCANCODE_BATCH, 0);
            DiagnosticDeliveryPlan hold = DiagnosticDeliveryPlan.forTap(control,
                    DiagnosticInputDeliveryMode.SCANCODE_HOLD, 50);
            assertEquals(batch.down(), hold.down(), control + " key-down stroke is identical");
            assertEquals(batch.up(), hold.up(), control + " key-up stroke is identical");
            assertFalse(hold.batched(), control + " holds");
            assertEquals(50, hold.holdMillis(), control + " explicit hold is preserved");
        }
    }

    @Test
    void arrowsCarryTheExtendedKeyFlagInEveryScanCodeMode() {
        for (GameControl arrow : ARROWS) {
            for (DiagnosticInputDeliveryMode mode : List.of(
                    DiagnosticInputDeliveryMode.SCANCODE_BATCH,
                    DiagnosticInputDeliveryMode.SCANCODE_HOLD)) {
                DiagnosticDeliveryPlan plan = DiagnosticDeliveryPlan.forTap(arrow, mode,
                        mode.holds() ? 50 : 0);
                assertTrue(plan.down().extended(), arrow + " " + mode + " key-down is extended");
                assertTrue(plan.up().extended(), arrow + " " + mode + " key-up is extended");
            }
        }
    }

    @Test
    void selectNeverUsesTheExtendedNumpadEnterSemantics() {
        for (DiagnosticInputDeliveryMode mode : List.of(
                DiagnosticInputDeliveryMode.SCANCODE_BATCH,
                DiagnosticInputDeliveryMode.SCANCODE_HOLD)) {
            DiagnosticDeliveryPlan plan = DiagnosticDeliveryPlan.forTap(GameControl.SELECT, mode,
                    mode.holds() ? 50 : 0);
            assertEquals(ScanCodeSpec.MAIN_ENTER, plan.down().wScan(), "main Enter make code");
            assertFalse(plan.down().extended(), "main Enter has no E0 prefix (numpad Enter does)");
            assertFalse(plan.up().extended(), "the release keeps the same key identity");
        }
    }

    @Test
    void batchModesRejectAnExplicitHold() {
        for (DiagnosticInputDeliveryMode mode : List.of(DiagnosticInputDeliveryMode.VK_BATCH,
                DiagnosticInputDeliveryMode.SCANCODE_BATCH)) {
            for (int hold : List.of(1, 10, 50, 200)) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> DiagnosticDeliveryPlan.forTap(GameControl.UP, mode, hold),
                        mode + " with hold " + hold + " must be refused");
                assertTrue(error.getMessage().contains("no-sleep batch"),
                        mode + ": " + error.getMessage());
            }
        }
    }

    @Test
    void holdModesEnforceTheExplicitHoldBounds() {
        for (DiagnosticInputDeliveryMode mode : List.of(DiagnosticInputDeliveryMode.VK_HOLD,
                DiagnosticInputDeliveryMode.SCANCODE_HOLD)) {
            for (int hold : List.of(0, 9, 201, 1000)) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> DiagnosticDeliveryPlan.forTap(GameControl.UP, mode, hold),
                        mode + " with hold " + hold + " must be refused");
                assertTrue(error.getMessage().contains("between 10 and 200"),
                        mode + ": " + error.getMessage());
            }
            assertEquals(10, DiagnosticDeliveryPlan.forTap(GameControl.UP, mode, 10).holdMillis(),
                    mode + " accepts the lower bound");
            assertEquals(200, DiagnosticDeliveryPlan.forTap(GameControl.UP, mode, 200).holdMillis(),
                    mode + " accepts the upper bound");
        }
    }

    @Test
    void proceedIsRefusedInEveryMode() {
        for (DiagnosticInputDeliveryMode mode : DiagnosticInputDeliveryMode.values()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DiagnosticDeliveryPlan.forTap(GameControl.PROCEED, mode,
                            mode.holds() ? 50 : 0),
                    mode + " must refuse PROCEED");
            assertTrue(error.getMessage().contains("Tab"), mode + ": " + error.getMessage());
        }
    }

    @Test
    void noPlanEverCarriesTheTabKeyInAnyRepresentation() {
        for (DiagnosticInputDeliveryMode mode : DiagnosticInputDeliveryMode.values()) {
            for (GameControl control : ALLOWED) {
                DiagnosticDeliveryPlan plan = DiagnosticDeliveryPlan.forTap(control, mode,
                        mode.holds() ? 50 : 0);
                assertNotEquals(0x09, plan.down().wVk(), control + " " + mode + " is not VK_TAB");
                assertNotEquals(0x09, plan.up().wVk(), control + " " + mode + " is not VK_TAB");
                assertNotEquals(0x0F, plan.down().wScan(),
                        control + " " + mode + " is not the Tab make code");
                assertNotEquals(0x0F, plan.up().wScan(),
                        control + " " + mode + " is not the Tab make code");
            }
        }
    }

    @Test
    void thePlanRecordItselfCannotHoldContradictoryStrokes() {
        DiagnosticKeyStroke down = DiagnosticKeyStroke.virtualKey(0x26, false);
        DiagnosticKeyStroke up = DiagnosticKeyStroke.virtualKey(0x26, true);
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosticDeliveryPlan(DiagnosticInputDeliveryMode.VK_HOLD, up, up, 50),
                "the down stroke cannot be a release");
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosticDeliveryPlan(DiagnosticInputDeliveryMode.VK_HOLD, down, down, 50),
                "the up stroke must be a release");
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosticDeliveryPlan(DiagnosticInputDeliveryMode.VK_BATCH, down, up, 50),
                "a BATCH plan cannot carry a hold");
        assertEquals(50, new DiagnosticDeliveryPlan(DiagnosticInputDeliveryMode.VK_HOLD, down, up,
                50).holdMillis(), "an explicit HOLD plan is valid");
    }
}
