package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal CSV reader for the committed Stage 6 annotation files.
 *
 * <p>It supports exactly what the annotation contract needs: a fixed header, comma separated
 * fields, optional double quoting with {@code ""} escapes and blank line skipping. Values are
 * trimmed, so hand-edited annotation files stay readable. Every failure names the file and the
 * one-based line the parser was reading.
 */
final class CsvTable {
    private final Path csv;
    private final List<Row> rows;

    private CsvTable(Path csv, List<Row> rows) {
        this.csv = csv;
        this.rows = rows;
    }

    /** Path this table was read from; used in diagnostics only. */
    Path source() {
        return csv;
    }

    /** Data rows in file order, header excluded. */
    List<Row> rows() {
        return rows;
    }

    /**
     * Reads {@code csv} and validates that its first line is exactly {@code expectedHeader}.
     */
    static CsvTable read(Path csv, String expectedHeader) throws IOException {
        Objects.requireNonNull(csv, "csv");
        Objects.requireNonNull(expectedHeader, "expectedHeader");
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("CSV is empty: " + csv);
        }
        String header = lines.get(0).trim();
        if (!header.equals(expectedHeader)) {
            throw new IllegalArgumentException(
                    "Unexpected header in " + csv + ": " + header);
        }
        List<Row> rows = new ArrayList<>();
        CsvTable table = new CsvTable(csv, rows);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            rows.add(table.new Row(i + 1, parseLine(line, csv, i + 1)));
        }
        return table;
    }

    private static String[] parseLine(String line, Path csv, int lineNumber) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                if (current.toString().isBlank()) {
                    current.setLength(0);
                }
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException(
                    "CSV " + csv + ":" + lineNumber + " has an unterminated quoted field");
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    /** One data row with typed, validated accessors. */
    final class Row {
        private final int lineNumber;
        private final String[] fields;

        private Row(int lineNumber, String[] fields) {
            this.lineNumber = lineNumber;
            this.fields = fields;
        }

        /** One-based line number in the source file. */
        int lineNumber() {
            return lineNumber;
        }

        /** Non-empty trimmed value; empty values are a contract violation. */
        String text(int index, String column) {
            String value = optional(index);
            if (value.isEmpty()) {
                throw error(column + " must not be empty");
            }
            return value;
        }

        /** Trimmed value, possibly empty. */
        String optional(int index) {
            if (index >= fields.length) {
                throw error("expected at least " + (index + 1) + " columns, got " + fields.length);
            }
            return fields[index];
        }

        int integer(int index, String column) {
            String value = text(index, column);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw error("invalid " + column + ": " + value);
            }
        }

        long longInteger(int index, String column) {
            String value = text(index, column);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw error("invalid " + column + ": " + value);
            }
        }

        double decimal(int index, String column) {
            String value = text(index, column);
            try {
                double parsed = Double.parseDouble(value);
                if (!Double.isFinite(parsed)) {
                    throw error("invalid " + column + ": " + value);
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw error("invalid " + column + ": " + value);
            }
        }

        boolean flag(int index, String column) {
            String value = text(index, column);
            if (value.equalsIgnoreCase("true")) {
                return true;
            }
            if (value.equalsIgnoreCase("false")) {
                return false;
            }
            throw error("invalid " + column + ": " + value + " (expected true or false)");
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(
                    "CSV " + csv + ":" + lineNumber + " " + message);
        }
    }
}
