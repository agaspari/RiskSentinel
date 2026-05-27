package com.risksentinel.eval.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Streams bars from a CSV file with header
 * {@code symbol,timestamp_iso,open,high,low,close,volume}.
 *
 * <p>Intentionally minimal: no quoted fields, no embedded commas, no headers
 * beyond the fixed seven. Malformed rows throw {@link IllegalStateException}
 * with a 1-based line number so the user can find the bad row.
 *
 * <p>Each call to {@link #iterator()} re-opens the file. Callers should
 * consume the iterator fully or expect a leaked reader — the iterator only
 * closes the underlying reader at end-of-stream or on parse failure.
 */
public final class CsvMarketDataSource implements MarketDataSource {

    private static final String EXPECTED_HEADER = "symbol,timestamp_iso,open,high,low,close,volume";

    private final Path file;

    public CsvMarketDataSource(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @Override
    public Iterator<Bar> iterator() {
        final BufferedReader reader;
        try {
            reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String header;
        try {
            header = reader.readLine();
        } catch (IOException e) {
            closeQuietly(reader);
            throw new UncheckedIOException(e);
        }
        if (header == null) {
            closeQuietly(reader);
            throw new IllegalStateException("CSV is empty: " + file);
        }
        if (!header.trim().equals(EXPECTED_HEADER)) {
            closeQuietly(reader);
            throw new IllegalStateException(
                    "CSV header mismatch in " + file
                            + ": expected '" + EXPECTED_HEADER + "', got '" + header + "'");
        }

        return new Iterator<>() {
            private String pending;
            private int lineNo = 1; // header is line 1
            private boolean exhausted = false;

            @Override
            public boolean hasNext() {
                if (exhausted) return false;
                if (pending != null) return true;
                try {
                    String line = reader.readLine();
                    while (line != null && line.isBlank()) {
                        lineNo++;
                        line = reader.readLine();
                    }
                    if (line == null) {
                        exhausted = true;
                        closeQuietly(reader);
                        return false;
                    }
                    pending = line;
                    lineNo++;
                    return true;
                } catch (IOException e) {
                    closeQuietly(reader);
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public Bar next() {
                if (!hasNext()) throw new NoSuchElementException();
                String line = pending;
                pending = null;
                return parse(line, lineNo);
            }
        };
    }

    private Bar parse(String line, int lineNo) {
        String[] parts = line.split(",", -1);
        if (parts.length != 7) {
            throw new IllegalStateException(
                    "Malformed CSV row at line " + lineNo + ": expected 7 fields, got " + parts.length);
        }
        try {
            String symbol = parts[0].trim();
            Instant ts = Instant.parse(parts[1].trim());
            double open = Double.parseDouble(parts[2].trim());
            double high = Double.parseDouble(parts[3].trim());
            double low = Double.parseDouble(parts[4].trim());
            double close = Double.parseDouble(parts[5].trim());
            long volume = Long.parseLong(parts[6].trim());
            return new Bar(symbol, ts, open, high, low, close, volume);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Malformed CSV row at line " + lineNo + ": " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(BufferedReader r) {
        try { r.close(); } catch (IOException ignored) {}
    }
}
