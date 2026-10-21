package com.brightpath.channel.service;

import com.brightpath.channel.security.AppException;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC-4180 style CSV parsing/formatting used for import and export.
 * All exported cells are quoted/escaped; imports are parsed with a proper
 * state machine (quoted fields, embedded commas, newlines and quotes).
 */
public final class CsvUtil {

    private static final int MAX_ROWS = 5_000;
    private static final int MAX_COLUMNS = 64;

    private CsvUtil() {
    }

    public static String render(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            sb.append(renderRow(row)).append("\r\n");
        }
        return sb.toString();
    }

    public static String renderRow(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String cell = cells[i] == null ? "" : cells[i];
            boolean needsQuotes = cell.contains(",") || cell.contains("\"")
                    || cell.contains("\n") || cell.contains("\r");
            if (needsQuotes) {
                sb.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(cell);
            }
        }
        return sb.toString();
    }

    /** Parses CSV text into rows of cells. Throws AppException with a line reference on malformed input. */
    public static List<String[]> parse(String content) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int line = 1;
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                    i++;
                }
            } else {
                switch (c) {
                    case '"' -> {
                        if (cell.isEmpty()) {
                            inQuotes = true;
                        } else {
                            throw AppException.badRequest("Malformed CSV at line " + line + ": quote inside unquoted cell.");
                        }
                        i++;
                    }
                    case ',' -> {
                        row.add(cell.toString());
                        cell.setLength(0);
                        i++;
                    }
                    case '\r' -> i++;
                    case '\n' -> {
                        row.add(cell.toString());
                        cell.setLength(0);
                        if (!row.isEmpty() && row.stream().anyMatch(s -> !s.isBlank())) {
                            rows.add(row.toArray(new String[0]));
                            if (rows.size() > MAX_ROWS) {
                                throw AppException.badRequest("Import file exceeds the maximum of " + MAX_ROWS + " rows.");
                            }
                        }
                        row = new ArrayList<>();
                        line++;
                        i++;
                    }
                    default -> {
                        cell.append(c);
                        i++;
                    }
                }
            }
        }
        if (inQuotes) {
            throw AppException.badRequest("Malformed CSV at line " + line + ": unterminated quoted field.");
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            if (row.stream().anyMatch(s -> !s.isBlank())) {
                rows.add(row.toArray(new String[0]));
            }
        }
        if (!rows.isEmpty() && rows.get(0).length > MAX_COLUMNS) {
            throw AppException.badRequest("Import file exceeds the maximum of " + MAX_COLUMNS + " columns.");
        }
        return rows;
    }
}
