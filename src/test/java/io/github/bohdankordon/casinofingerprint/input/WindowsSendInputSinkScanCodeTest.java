package io.github.bohdankordon.casinofingerprint.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.diagnostic.ScanCodeSpec;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsGameKeySpec;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsSendInputSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.4 production scan-code contract: the promoted SCANCODE_BATCH representation,
 * the exact one-batch sequencing, the key-up cleanup identity and the no-hold shape.
 * Pure data plus a fake submission seam only: no test here emits OS input and none
 * constructs the Windows-only backend (static mapping and sequencing only).
 */
class WindowsSendInputSinkScanCodeTest {
    @Test
    void productionMappingPinsAllSixScanCodes() {
        assertEquals(new WindowsGameKeySpec(0x48, true), WindowsSendInputSink.spec(GameControl.UP));
        assertEquals(new WindowsGameKeySpec(0x50, true), WindowsSendInputSink.spec(GameControl.DOWN));
        assertEquals(new WindowsGameKeySpec(0x4B, true), WindowsSendInputSink.spec(GameControl.LEFT));
        assertEquals(new WindowsGameKeySpec(0x4D, true), WindowsSendInputSink.spec(GameControl.RIGHT));
        assertEquals(new WindowsGameKeySpec(0x1C, false), WindowsSendInputSink.spec(GameControl.SELECT));
        assertEquals(new WindowsGameKeySpec(0x0F, false), WindowsSendInputSink.spec(GameControl.PROCEED));
    }

    @Test
    void everyDownKeepsWVkZeroAndSetsScancode() {
        for (GameControl control : GameControl.values()) {
            WindowsSendInputSink.KeyStroke down = WindowsSendInputSink.downStroke(control);
            assertEquals(0, down.wVk(), control + " wVk stays zero");
            assertEquals(WindowsSendInputSink.spec(control).scanCode(), down.wScan(), control + " scan");
            assertTrue((down.flags() & WindowsSendInputSink.KEYEVENTF_SCANCODE) != 0, control + " SCANCODE set");
            assertFalse(down.keyUp(), control + " down is not a release");
        }
    }

    @Test
    void everyUpKeepsTheSameIdentityPlusKeyUp() {
        for (GameControl control : GameControl.values()) {
            WindowsSendInputSink.KeyStroke down = WindowsSendInputSink.downStroke(control);
            WindowsSendInputSink.KeyStroke up = WindowsSendInputSink.upStroke(control);
            assertEquals(0, up.wVk(), control + " wVk stays zero");
            assertEquals(down.wScan(), up.wScan(), control + " same scan");
            assertEquals(down.extended(), up.extended(), control + " same extended identity");
            assertTrue(up.keyUp(), control + " up is a release");
            assertEquals(down.flags() | WindowsSendInputSink.KEYEVENTF_KEYUP, up.flags(), control + " up adds KEYUP only");
        }
    }

    @Test
    void onlyArrowsCarryTheExtendedBit() {
        for (GameControl arrow : List.of(GameControl.UP, GameControl.DOWN, GameControl.LEFT, GameControl.RIGHT)) {
            assertTrue(WindowsSendInputSink.downStroke(arrow).extended(), arrow + " extended");
            assertTrue(WindowsSendInputSink.upStroke(arrow).extended(), arrow + " extended release");
        }
        for (GameControl plain : List.of(GameControl.SELECT, GameControl.PROCEED)) {
            assertFalse(WindowsSendInputSink.downStroke(plain).extended(), plain + " non-extended");
            assertFalse(WindowsSendInputSink.upStroke(plain).extended(), plain + " non-extended release");
        }
    }

    @Test
    void tapDescribesExactlyOneTwoEventBatch() {
        for (GameControl control : GameControl.values()) {
            List<WindowsSendInputSink.KeyStroke> batch = WindowsSendInputSink.describeTap(control);
            assertEquals(2, batch.size(), control + " is down plus up");
            assertFalse(batch.get(0).keyUp(), control + " down first");
            assertTrue(batch.get(1).keyUp(), control + " up second");
            assertEquals(WindowsSendInputSink.downStroke(control), batch.get(0));
            assertEquals(WindowsSendInputSink.upStroke(control), batch.get(1));
        }
    }

    @Test
    void fullBatchSuccessSendsOneBatchWithNoCleanup() {
        RecordingSender sender = new RecordingSender();
        WindowsSendInputSink.performTap(GameControl.UP, sender::send);
        assertEquals(1, sender.batches().size(), "exactly one native batch");
        assertEquals(2, sender.batches().get(0).size(), "down plus up");
        assertEquals(WindowsSendInputSink.describeTap(GameControl.UP), sender.batches().get(0));
    }

    @Test
    void partialBatchAttemptsOneKeyUpCleanupWithNoSecondDown() {
        RecordingSender sender = new RecordingSender();
        sender.failAt(0, 1);
        GameInputException error = assertThrows(GameInputException.class,
                () -> WindowsSendInputSink.performTap(GameControl.UP, sender::send));
        assertEquals(2, sender.batches().size(), "the batch plus one cleanup release");
        assertEquals(1, sender.downStrokes(), "no second key-down");
        assertEquals(2, sender.upStrokes(), "the batch key-up plus the cleanup release");
        assertTrue(sender.batches().get(1).get(0).keyUp(), "the cleanup is a release");
        assertEquals(WindowsSendInputSink.upStroke(GameControl.UP), sender.batches().get(1).get(0),
                "cleanup uses the exact same scan/extended identity");
        assertTrue(error.getMessage().contains("delivered 1 of 2"), error.getMessage());
        assertTrue(error.getMessage().contains("complete tap not confirmed"), error.getMessage());
        assertTrue(error.getMessage().contains("best-effort key-up attempted"), error.getMessage());
        assertFalse(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("no complete tap was sent")
                && error.getMessage().contains("no complete tap was sent")
                && !error.getMessage().contains("delivered"), "must not imply zero delivery: " + error.getMessage());
    }

    @Test
    void nativeSubmissionFailureStillAttemptsTheKeyUpAndStaysTruthful() {
        RecordingSender sender = new RecordingSender();
        sender.throwAt(0);
        GameInputException error = assertThrows(GameInputException.class,
                () -> WindowsSendInputSink.performTap(GameControl.SELECT, sender::send));
        assertEquals(2, sender.batches().size(), "failed batch plus one cleanup");
        assertEquals(1, sender.downStrokes(), "one logical down, no retry");
        assertTrue(sender.batches().get(1).get(0).keyUp(), "cleanup is a release");
        assertEquals(WindowsSendInputSink.upStroke(GameControl.SELECT), sender.batches().get(1).get(0));
        assertTrue(error.getMessage().contains("key-up was still attempted"), error.getMessage());
        assertTrue(error.getCause() != null || error.getMessage().contains("failed"), "cause or failure wording: " + error.getMessage());
    }

    @Test
    void productionMappingMatchesTheValidatedCharacterizationMapping() {
        for (GameControl control : List.of(GameControl.UP, GameControl.DOWN, GameControl.LEFT,
                GameControl.RIGHT, GameControl.SELECT)) {
            ScanCodeSpec characterized = ScanCodeSpec.forControl(control);
            WindowsGameKeySpec production = WindowsSendInputSink.spec(control);
            assertEquals(characterized.scanCode(), production.scanCode(), control + " scan matches");
            assertEquals(characterized.extended(), production.extended(), control + " extended matches");
        }
        ScanCodeSpec tab = ScanCodeSpec.tabForProceedProbe();
        WindowsGameKeySpec proceed = WindowsSendInputSink.spec(GameControl.PROCEED);
        assertEquals(tab.scanCode(), proceed.scanCode(), "PROCEED scan matches the validated Tab");
        assertEquals(tab.extended(), proceed.extended(), "PROCEED extended matches the validated Tab");
    }

    @Test
    void productionSinkCarriesNoHoldSleepOrVirtualKeyDelivery() throws IOException {
        Path source = Path.of(System.getProperty("user.dir")).resolve(
                "src/main/java/io/github/bohdankordon/casinofingerprint/input/win32/"
                        + "WindowsSendInputSink.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(text.contains("KEYEVENTF_SCANCODE"), "scan-code delivery");
        assertTrue(text.contains("SCANCODE_BATCH") || text.contains("scan-code"), "batch contract documented");
        assertFalse(text.contains("Thread.sleep"), "no sleep in production input");
        assertFalse(text.contains("sleepMillis"), "no sleeper in production input");
        assertFalse(text.contains("holdMillis") || text.contains("HOLD_POLL"), "no hold in production input");
        assertFalse(text.contains("virtualKey("), "old virtual-key delivery is gone");
        assertFalse(text.contains("VK_UP") || text.contains("VK_RETURN") || text.contains("VK_TAB"),
                "old virtual-key constants are gone");
        assertFalse(text.contains("wVk = new WORD(code)") || text.contains("wScan = new WORD(0)"),
                "old virtual-key translation is gone");
        assertTrue(text.contains("wVk") && text.contains("0, identity.scanCode()"),
                "wVk zero with scan identity: " + text.substring(0, Math.min(2000, text.length())));
    }

    /** Records every batch; failures are scripted per batch index without any native call. */
    private static final class RecordingSender {
        private final List<List<WindowsSendInputSink.KeyStroke>> batches = new ArrayList<>();
        private final java.util.Map<Integer, Integer> failDelivered = new java.util.HashMap<>();
        private final java.util.Set<Integer> throwInstead = new java.util.HashSet<>();

        void failAt(int index, int delivered) {
            failDelivered.put(index, delivered);
        }

        void throwAt(int index) {
            throwInstead.add(index);
        }

        List<List<WindowsSendInputSink.KeyStroke>> batches() {
            return List.copyOf(batches);
        }

        long downStrokes() {
            return batches.stream().flatMap(List::stream).filter(stroke -> !stroke.keyUp()).count();
        }

        long upStrokes() {
            return batches.stream().flatMap(List::stream).filter(WindowsSendInputSink.KeyStroke::keyUp).count();
        }

        int send(List<WindowsSendInputSink.KeyStroke> batch) {
            int index = batches.size();
            batches.add(List.copyOf(batch));
            if (throwInstead.contains(index)) {
                throw new GameInputException("fake native submission failure at " + index);
            }
            return failDelivered.getOrDefault(index, batch.size());
        }
    }
}
