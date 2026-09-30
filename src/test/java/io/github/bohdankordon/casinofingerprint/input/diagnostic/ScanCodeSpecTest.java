package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Stage 8C.2 Set-1 scan-code contract for the five allowed controls: arrow keys are the
 * extended (E0) cluster, SELECT is the main keyboard Enter and not the keypad variant, and
 * PROCEED has no mapping at all.
 */
class ScanCodeSpecTest {
    private static final List<GameControl> ALLOWED = List.of(GameControl.UP, GameControl.DOWN,
            GameControl.LEFT, GameControl.RIGHT, GameControl.SELECT);

    @Test
    void arrowClusterUsesTheExtendedSet1MakeCodes() {
        assertEquals(new ScanCodeSpec(0x48, true), ScanCodeSpec.forControl(GameControl.UP),
                "arrow up is E0 48");
        assertEquals(new ScanCodeSpec(0x50, true), ScanCodeSpec.forControl(GameControl.DOWN),
                "arrow down is E0 50");
        assertEquals(new ScanCodeSpec(0x4B, true), ScanCodeSpec.forControl(GameControl.LEFT),
                "arrow left is E0 4B");
        assertEquals(new ScanCodeSpec(0x4D, true), ScanCodeSpec.forControl(GameControl.RIGHT),
                "arrow right is E0 4D");
    }

    @Test
    void theFourArrowsAreDistinctExtendedKeys() {
        Set<Integer> codes = List.of(GameControl.UP, GameControl.DOWN, GameControl.LEFT,
                        GameControl.RIGHT).stream()
                .map(ScanCodeSpec::forControl)
                .peek(spec -> assertTrue(spec.extended(), "every arrow carries the E0 prefix"))
                .map(ScanCodeSpec::scanCode)
                .collect(Collectors.toSet());
        assertEquals(4, codes.size(), "four distinct make codes: " + codes);
    }

    @Test
    void selectIsTheMainKeyboardEnterNotTheNumpadVariant() {
        ScanCodeSpec select = ScanCodeSpec.forControl(GameControl.SELECT);
        assertEquals(0x1C, select.scanCode(), "main Enter make code");
        assertEquals(ScanCodeSpec.MAIN_ENTER, select.scanCode(), "pinned constant");
        assertFalse(select.extended(), "the numeric-keypad Enter is the E0 1C variant and must"
                + " not be used for SELECT");
    }

    @Test
    void proceedHasNoScanCodeMappingSoTabCanNeverBeSent() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScanCodeSpec.forControl(GameControl.PROCEED));
        assertTrue(error.getMessage().contains("Tab"), error.getMessage());
    }

    @Test
    void allowedControlsAreExactlyTheFiveMappedControls() {
        List<GameControl> mapped = Arrays.stream(GameControl.values())
                .filter(control -> control != GameControl.PROCEED)
                .toList();
        assertEquals(ALLOWED, mapped, "exactly five mapped controls");
        for (GameControl control : mapped) {
            assertTrue(ScanCodeSpec.forControl(control).scanCode() > 0, control + " maps");
        }
    }

    @Test
    void noMappedControlIsTheTabKey() {
        for (GameControl control : ALLOWED) {
            assertNotEquals(0x0F, ScanCodeSpec.forControl(control).scanCode(),
                    control + " must never carry the Tab make code");
        }
    }

    @Test
    void aScanCodeStaysWithinOneByte() {
        assertThrows(IllegalArgumentException.class, () -> new ScanCodeSpec(-1, false));
        assertThrows(IllegalArgumentException.class, () -> new ScanCodeSpec(0x100, false));
        assertEquals(0, new ScanCodeSpec(0, false).scanCode(), "zero is a legal byte value");
        assertEquals(0xFF, new ScanCodeSpec(0xFF, true).scanCode(), "0xFF is a legal byte value");
    }
}
