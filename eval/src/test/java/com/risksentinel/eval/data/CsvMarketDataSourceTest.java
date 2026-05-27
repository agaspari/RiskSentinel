package com.risksentinel.eval.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvMarketDataSourceTest {

    private static final String HEADER =
            "symbol,timestamp_iso,open,high,low,close,volume";

    private static Path write(Path dir, String name, String body) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, body);
        return p;
    }

    @Test
    void shouldParseThreeRows(@TempDir Path dir) throws Exception {
        Path csv = write(dir, "good.csv",
                HEADER + "\n"
                        + "AAPL,2026-05-19T12:00:00Z,150.0,151.0,149.0,150.5,1000\n"
                        + "AAPL,2026-05-19T12:01:00Z,150.5,152.0,150.0,151.5,1100\n"
                        + "MSFT,2026-05-19T12:00:00Z,250.0,251.0,249.0,250.5,2000\n");
        List<Bar> bars = new ArrayList<>();
        new CsvMarketDataSource(csv).forEach(bars::add);
        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).symbol()).isEqualTo("AAPL");
        assertThat(bars.get(0).timestamp())
                .isEqualTo(Instant.parse("2026-05-19T12:00:00Z"));
        assertThat(bars.get(2).symbol()).isEqualTo("MSFT");
    }

    @Test
    void shouldRejectRow_whenHighBelowLow(@TempDir Path dir) throws Exception {
        Path csv = write(dir, "bad-ohlc.csv",
                HEADER + "\n"
                        + "AAPL,2026-05-19T12:00:00Z,150.0,148.0,149.0,150.5,1000\n");
        assertThatThrownBy(() -> {
            for (Bar b : new CsvMarketDataSource(csv)) { /* drain */ }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("high");
    }

    @Test
    void shouldRejectRow_whenMalformedNumber(@TempDir Path dir) throws Exception {
        Path csv = write(dir, "bad-num.csv",
                HEADER + "\n"
                        + "AAPL,2026-05-19T12:00:00Z,oops,151.0,149.0,150.5,1000\n");
        assertThatThrownBy(() -> {
            for (Bar b : new CsvMarketDataSource(csv)) { /* drain */ }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    void shouldRejectRow_whenWrongFieldCount(@TempDir Path dir) throws Exception {
        Path csv = write(dir, "short.csv",
                HEADER + "\n"
                        + "AAPL,2026-05-19T12:00:00Z,150.0,151.0,149.0,150.5\n");
        assertThatThrownBy(() -> {
            for (Bar b : new CsvMarketDataSource(csv)) { /* drain */ }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("7 fields");
    }

    @Test
    void shouldRejectFile_whenHeaderMismatch(@TempDir Path dir) throws Exception {
        Path csv = write(dir, "bad-header.csv",
                "wrong,header\nAAPL,2026-05-19T12:00:00Z,150.0,151.0,149.0,150.5,1000\n");
        assertThatThrownBy(() -> new CsvMarketDataSource(csv).iterator())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("header");
    }

    @Test
    void shouldRejectFile_whenEmpty(@TempDir Path dir) throws Exception {
        Path csv = write(dir, "empty.csv", "");
        assertThatThrownBy(() -> new CsvMarketDataSource(csv).iterator())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void shouldSkipBlankLines(@TempDir Path dir) throws Exception {
        Path csv = write(dir, "blanks.csv",
                HEADER + "\n"
                        + "AAPL,2026-05-19T12:00:00Z,150.0,151.0,149.0,150.5,1000\n"
                        + "\n"
                        + "AAPL,2026-05-19T12:01:00Z,150.5,152.0,150.0,151.5,1100\n");
        List<Bar> bars = new ArrayList<>();
        new CsvMarketDataSource(csv).forEach(bars::add);
        assertThat(bars).hasSize(2);
    }
}
