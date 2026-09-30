package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.2 delivery-mode vocabulary: exactly four explicit modes, each one a distinct shape,
 * and PROCEED can never select one. Pure data only: no test here touches a native library.
 */
class DiagnosticInputDeliveryModeTest {
    private static final List<String> NAMES = List.of("VK_BATCH", "VK_HOLD", "SCANCODE_BATCH",
            "SCANCODE_HOLD");

    @Test
    void exactlyFourModesExist() {
        assertEquals(4, DiagnosticInputDeliveryMode.values().length, "Exactly four modes");
        assertEquals(NAMES, Arrays.stream(DiagnosticInputDeliveryMode.values()).map(Enum::name)
                .toList(), "The documented mode names");
        for (String name : NAMES) {
            assertTrue(DiagnosticInputDeliveryMode.NAMES.contains(name),
                    "The usage list names " + name + ": " + DiagnosticInputDeliveryMode.NAMES);
        }
    }

    @Test
    void parseAcceptsExactlyTheFourDocumentedNames() {
        for (String name : NAMES) {
            assertEquals(name, DiagnosticInputDeliveryMode.parse(name).name(),
                    name + " parses to itself");
        }
    }

    @Test
    void unknownNamesAreRefusedWithTheAllowedList() {
        for (String unknown : List.of("VK", "SCANCODE", "HOLD", "BATCH", "VK_BATCH_",
                "scancode_hold", "TAB")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DiagnosticInputDeliveryMode.parse(unknown), unknown + " must be refused");
            assertTrue(error.getMessage().contains("UNSUPPORTED DELIVERY MODE"),
                    unknown + ": " + error.getMessage());
            assertTrue(error.getMessage().contains("SCANCODE_HOLD"),
                    "The refusal lists the allowed modes: " + error.getMessage());
        }
    }

    @Test
    void proceedCanNeverSelectADeliveryMode() {
        for (String spelling : List.of("PROCEED", "proceed", "Proceed")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DiagnosticInputDeliveryMode.parse(spelling),
                    spelling + " must be refused as a mode");
            assertTrue(error.getMessage().contains("PROCEED"), error.getMessage());
            assertTrue(error.getMessage().contains("Tab"), error.getMessage());
        }
    }

    @Test
    void theShapeOfEveryModeMatchesItsName() {
        assertFalse(DiagnosticInputDeliveryMode.VK_BATCH.usesScanCodes(), "VK_BATCH is VK based");
        assertFalse(DiagnosticInputDeliveryMode.VK_BATCH.holds(), "VK_BATCH never holds");
        assertTrue(DiagnosticInputDeliveryMode.VK_BATCH.isBatch(), "VK_BATCH is a batch");

        assertFalse(DiagnosticInputDeliveryMode.VK_HOLD.usesScanCodes(), "VK_HOLD is VK based");
        assertTrue(DiagnosticInputDeliveryMode.VK_HOLD.holds(), "VK_HOLD holds");
        assertFalse(DiagnosticInputDeliveryMode.VK_HOLD.isBatch(), "VK_HOLD is not a batch");

        assertTrue(DiagnosticInputDeliveryMode.SCANCODE_BATCH.usesScanCodes(), "scan codes");
        assertFalse(DiagnosticInputDeliveryMode.SCANCODE_BATCH.holds(), "no hold");
        assertTrue(DiagnosticInputDeliveryMode.SCANCODE_BATCH.isBatch(), "one batch");

        assertTrue(DiagnosticInputDeliveryMode.SCANCODE_HOLD.usesScanCodes(), "scan codes");
        assertTrue(DiagnosticInputDeliveryMode.SCANCODE_HOLD.holds(), "holds");
        assertFalse(DiagnosticInputDeliveryMode.SCANCODE_HOLD.isBatch(), "not a batch");
    }

    @Test
    void everyModeDescribesItselfForTheOperatorBanner() {
        for (DiagnosticInputDeliveryMode mode : DiagnosticInputDeliveryMode.values()) {
            assertFalse(mode.description().isBlank(), mode + " has an operator description");
        }
    }
}
