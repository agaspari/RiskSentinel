package com.risksentinel.eval.report;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestReportTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    private static BacktestReport sample() {
        return new BacktestReport(
                "BuyAndHold[port-1:AAPL:100]",
                T0,
                T0.plusSeconds(600),
                10,
                1, 1, 0,
                Map.of(),
                Map.of("AAPL", 100L),
                123.45,
                new LatencyStats(1L, 50L, 95L, 99L, 100L));
    }

    @Test
    void shouldConstruct_withValidFields() {
        BacktestReport r = sample();
        assertThat(r.strategyName()).contains("BuyAndHold");
        assertThat(r.endingPositionBySymbol()).containsEntry("AAPL", 100L);
    }

    @Test
    void shouldReject_whenAcceptPlusRejectMismatchesTotal() {
        assertThatThrownBy(() -> new BacktestReport(
                "s", T0, T0, 1, 5, 2, 2, Map.of(), Map.of(), 0.0,
                new LatencyStats(0, 0, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalProposals");
    }

    @Test
    void shouldReject_whenEndedBeforeStarted() {
        assertThatThrownBy(() -> new BacktestReport(
                "s", T0.plusSeconds(10), T0, 1, 0, 0, 0, Map.of(), Map.of(), 0.0,
                new LatencyStats(0, 0, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endedAt");
    }

    @Test
    void shouldReject_whenStrategyNameBlank() {
        assertThatThrownBy(() -> new BacktestReport(
                " ", T0, T0, 1, 0, 0, 0, Map.of(), Map.of(), 0.0,
                new LatencyStats(0, 0, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDefensivelyCopy_mutableMaps() {
        java.util.Map<String, Integer> mutableRejects = new java.util.HashMap<>();
        mutableRejects.put("FAT_FINGER_QUANTITY", 3);
        java.util.Map<String, Long> mutablePositions = new java.util.HashMap<>();
        mutablePositions.put("AAPL", 100L);

        BacktestReport r = new BacktestReport(
                "s", T0, T0.plusSeconds(1), 1, 3, 0, 3,
                mutableRejects, mutablePositions, 0.0,
                new LatencyStats(0, 0, 0, 0, 0));
        mutableRejects.put("LATE_ADDITION", 999);
        mutablePositions.put("LATE", 999L);

        assertThat(r.rejectsByCode()).containsOnlyKeys("FAT_FINGER_QUANTITY");
        assertThat(r.endingPositionBySymbol()).containsOnlyKeys("AAPL");
    }

    @Test
    void shouldRenderMarkdown_withAllSections() {
        BacktestReport r = new BacktestReport(
                "BuyAndHold[port-1:AAPL:100]",
                T0, T0.plusSeconds(600),
                10, 5, 4, 1,
                Map.of("FAT_FINGER_QUANTITY", 1),
                Map.of("AAPL", 100L),
                123.45,
                new LatencyStats(5L, 1000L, 5000L, 9000L, 12000L));
        String md = r.toMarkdown();
        assertThat(md).contains("BuyAndHold[port-1:AAPL:100]");
        assertThat(md).contains("Bars processed: 10");
        assertThat(md).contains("total=5");
        assertThat(md).contains("accepted=4");
        assertThat(md).contains("rejected=1");
        assertThat(md).contains("FAT_FINGER_QUANTITY: 1");
        assertThat(md).contains("AAPL: 100");
        assertThat(md).contains("123.45");
        assertThat(md).contains("p50=1000");
        assertThat(md).contains("p99=9000");
    }
}
