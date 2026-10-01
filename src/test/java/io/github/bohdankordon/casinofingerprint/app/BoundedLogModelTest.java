package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The activity view keeps a newest-capped window in original order. */
class BoundedLogModelTest {
    @Test
    void keepsAppendedLinesInOrder() {
        BoundedLogModel model = new BoundedLogModel();
        model.append("first");
        model.append("second");
        assertEquals(List.of("first", "second"), model.snapshot());
        assertEquals(2, model.size());
    }

    @Test
    void dropsOldestLinesPastTheCap() {
        BoundedLogModel model = new BoundedLogModel();
        for (int index = 0; index < BoundedLogModel.MAX_LINES + 100; index++) {
            model.append("line-" + index);
        }
        assertEquals(BoundedLogModel.MAX_LINES, model.size());
        List<String> snapshot = model.snapshot();
        assertEquals("line-100", snapshot.get(0));
        assertEquals("line-" + (BoundedLogModel.MAX_LINES + 99),
                snapshot.get(snapshot.size() - 1));
    }

    @Test
    void truncatesSingleOverlongLines() {
        BoundedLogModel model = new BoundedLogModel();
        String longLine = "x".repeat(BoundedLogModel.MAX_LINE_CHARS + 50);
        model.append(longLine);
        String kept = model.snapshot().get(0);
        assertTrue(kept.endsWith(" [truncated]"), kept);
        assertTrue(kept.length() <= BoundedLogModel.MAX_LINE_CHARS + 20, kept);
    }
}
