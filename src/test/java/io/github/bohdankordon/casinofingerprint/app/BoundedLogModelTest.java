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
        assertTrue(kept.length() <= BoundedLogModel.MAX_LINE_CHARS + 20, kept);
    }

    @Test
    void consumerCursorSurvivesTheCap() {
        BoundedLogModel model = new BoundedLogModel();
        for (int index = 0; index < BoundedLogModel.MAX_LINES; index++) {
            model.append("line-" + index);
        }
        long cursor = model.totalAppended();
        for (int index = BoundedLogModel.MAX_LINES;
                index < BoundedLogModel.MAX_LINES + 50; index++) {
            model.append("line-" + index);
        }
        BoundedLogModel.LogBatch batch = model.entriesSince(cursor);
        assertEquals(50, batch.lines().size());
        assertEquals("line-" + BoundedLogModel.MAX_LINES, batch.lines().get(0));
        assertEquals("line-" + (BoundedLogModel.MAX_LINES + 49),
                batch.lines().get(batch.lines().size() - 1));
        assertEquals(model.totalAppended(), batch.nextCursor());
        assertTrue(!batch.resynchronized());
    }

    @Test
    void laggingConsumerResynchronizesToNewestWindow() {
        BoundedLogModel model = new BoundedLogModel();
        for (int index = 0; index < 2 * BoundedLogModel.MAX_LINES + 10; index++) {
            model.append("line-" + index);
        }
        BoundedLogModel.LogBatch batch = model.entriesSince(0);
        assertTrue(batch.resynchronized());
        assertEquals(BoundedLogModel.MAX_LINES, batch.lines().size());
        assertEquals("line-" + (BoundedLogModel.MAX_LINES + 10), batch.lines().get(0));
        assertEquals(model.totalAppended(), batch.nextCursor());
    }

    @Test
    void interleavedConsumptionDeliversEveryNewLineOnce() {
        BoundedLogModel model = new BoundedLogModel();
        long cursor = 0;
        java.util.ArrayList<String> received = new java.util.ArrayList<>();
        int total = BoundedLogModel.MAX_LINES + 500;
        for (int index = 0; index < total; index++) {
            model.append("line-" + index);
            BoundedLogModel.LogBatch batch = model.entriesSince(cursor);
            assertTrue(!batch.resynchronized());
            received.addAll(batch.lines());
            cursor = batch.nextCursor();
        }
        assertEquals(total, received.size());
        for (int index = 0; index < total; index++) {
            assertEquals("line-" + index, received.get(index));
        }
    }
}
