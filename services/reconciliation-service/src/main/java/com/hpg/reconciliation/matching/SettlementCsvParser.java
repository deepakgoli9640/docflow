package com.hpg.reconciliation.matching;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Streams a settlement CSV (docs/05-reconciliation.md §3) row by row, so large files never sit in memory.
 * Columns are found by header name (any order; extra columns ignored). A bad row becomes a malformed
 * {@link SettlementRow} and does not stop the file; a bad header does.
 */
public class SettlementCsvParser {

    public static final List<String> REQUIRED = List.of("entity_id", "type", "amount", "currency");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int MAX_LINE = 10_000;

    /** The file itself is unusable (empty, no or wrong header, too many rows). */
    public static class InvalidFileException extends RuntimeException {
        public InvalidFileException(String message) {
            super(message);
        }
    }

    private final int maxRows;

    public SettlementCsvParser(int maxRows) {
        this.maxRows = maxRows;
    }

    /** @return number of data rows read */
    public int parse(InputStream in, Consumer<SettlementRow> sink) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) {
            throw new InvalidFileException("The file is empty");
        }
        if (headerLine.startsWith("﻿")) {
            headerLine = headerLine.substring(1); // Excel's UTF-8 byte order mark
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header;
        try {
            header = split(headerLine);
        } catch (IllegalArgumentException e) {
            throw new InvalidFileException("Unreadable header: " + e.getMessage());
        }
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        for (String required : REQUIRED) {
            if (!columns.containsKey(required)) {
                throw new InvalidFileException("Missing column '" + required + "'. Expected header: "
                        + "entity_id,type,order_id,amount,fee,tax,currency,settlement_id,settled_at,created_at");
            }
        }
        int rowNumber = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            rowNumber++;
            if (rowNumber > maxRows) {
                throw new InvalidFileException("More than " + maxRows + " rows; split the file");
            }
            sink.accept(parseRow(rowNumber, line, columns));
        }
        return rowNumber;
    }

    private static SettlementRow parseRow(int rowNumber, String line, Map<String, Integer> columns) {
        String raw = line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line;
        try {
            List<String> cells = split(line);
            String entityId = required(cells, columns, "entity_id");
            if (entityId.length() > 64) {
                throw new IllegalArgumentException("entity_id is longer than 64 characters");
            }
            String type = required(cells, columns, "type").toLowerCase(Locale.ROOT);
            BigDecimal amount = money(required(cells, columns, "amount"), "amount");
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
            String currency = required(cells, columns, "currency").toUpperCase(Locale.ROOT);
            if (!CURRENCY.matcher(currency).matches()) {
                throw new IllegalArgumentException("currency '" + currency + "' is not an ISO-4217 code");
            }
            String fee = optional(cells, columns, "fee");
            String tax = optional(cells, columns, "tax");
            return new SettlementRow(rowNumber, entityId, type, optional(cells, columns, "order_id"), amount,
                    fee == null ? null : money(fee, "fee"), tax == null ? null : money(tax, "tax"), currency,
                    optional(cells, columns, "settlement_id"), instant(optional(cells, columns, "settled_at"), "settled_at"),
                    instant(optional(cells, columns, "created_at"), "created_at"), raw, null);
        } catch (IllegalArgumentException e) {
            return SettlementRow.malformed(rowNumber, raw, e.getMessage());
        }
    }

    private static String required(List<String> cells, Map<String, Integer> columns, String name) {
        String v = optional(cells, columns, name);
        if (v == null) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return v;
    }

    private static String optional(List<String> cells, Map<String, Integer> columns, String name) {
        Integer i = columns.get(name);
        if (i == null || i >= cells.size()) {
            return null;
        }
        String v = cells.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal money(String v, String name) {
        try {
            BigDecimal d = new BigDecimal(v);
            if (d.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException(name + " has more than 2 decimals: " + v);
            }
            return d.setScale(2);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number: " + v);
        }
    }

    private static Instant instant(String v, String name) {
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " is not an ISO-8601 UTC timestamp: " + v);
        }
    }

    /** RFC 4180 fields on one line: commas, double quotes, "" as an escaped quote. */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("unterminated quoted field");
        }
        out.add(cell.toString());
        return out;
    }
}
