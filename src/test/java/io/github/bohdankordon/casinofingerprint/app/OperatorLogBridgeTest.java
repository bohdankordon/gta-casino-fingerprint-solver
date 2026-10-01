package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The disk and UI tee: every line reaches the authoritative UTF-8 file while
 * the visible model keeps only its newest window.
 */
class OperatorLogBridgeTest {
    @TempDir
    Path temporary;

    @Test
    void teesEveryLineToDiskAndView() throws IOException {
        BoundedLogModel model = new BoundedLogModel();
        Path log = temporary.resolve("session.log");
        try (OperatorLogBridge bridge = new OperatorLogBridge(log, model)) {
            bridge.emit("OPERATOR SESSION START");
            PrintStream stream = bridge.printStream();
            stream.println("LIVE: monitors (1)");
            stream.println("embedded line breaks split deterministically");
            stream.println("Zażółć gęślą jaźń");
            stream.print("trailing partial without newline");
            stream.close();
        }
        List<String> disk = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertEquals(List.of("OPERATOR SESSION START", "LIVE: monitors (1)",
                "embedded line breaks split deterministically", "Zażółć gęślą jaźń",
                "trailing partial without newline"), disk);
        assertEquals(disk, model.snapshot());
    }

    @Test
    void carriageReturnsNeverLeakIntoStoredLines() throws IOException {
        BoundedLogModel model = new BoundedLogModel();
        Path log = temporary.resolve("session.log");
        try (OperatorLogBridge bridge = new OperatorLogBridge(log, model)) {
            PrintStream stream = bridge.printStream();
            stream.print("windows line ending here\r\n");
            stream.flush();
        }
        List<String> disk = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertEquals(List.of("windows line ending here"), disk);
        String carriage = "\r\n";
        for (String line : disk) {
            assertTrue(!line.contains(carriage), line);
        }
    }

    @Test
    void utf8ContentRoundTrips() throws IOException {
        BoundedLogModel model = new BoundedLogModel();
        Path log = temporary.resolve("session.log");
        try (OperatorLogBridge bridge = new OperatorLogBridge(log, model)) {
            bridge.emit("UTF-8 check: ąęłśćńóżź");
            bridge.printStream().println("second line");
        }
        List<String> disk = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertEquals(2, disk.size());
        assertEquals("UTF-8 check: ąęłśćńóżź", disk.get(0));
    }

    @Test
    void boundedViewNeverTruncatesTheAuthoritativeDiskLog() throws IOException {
        BoundedLogModel model = new BoundedLogModel();
        Path log = temporary.resolve("session.log");
        try (OperatorLogBridge bridge = new OperatorLogBridge(log, model)) {
            for (int index = 0; index < BoundedLogModel.MAX_LINES + 100; index++) {
                bridge.emit("line-" + index);
            }
        }
        assertEquals(BoundedLogModel.MAX_LINES, model.size());
        List<String> disk = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertEquals(BoundedLogModel.MAX_LINES + 100, disk.size());
        assertEquals("line-0", disk.get(0));
    }

    @Test
    void closeIsIdempotentAndEmittingAfterCloseFails() throws IOException {
        BoundedLogModel model = new BoundedLogModel();
        OperatorLogBridge bridge =
                new OperatorLogBridge(temporary.resolve("session.log"), model);
        bridge.close();
        bridge.close();
        assertThrows(IllegalStateException.class, () -> bridge.emit("late line"));
    }
}
