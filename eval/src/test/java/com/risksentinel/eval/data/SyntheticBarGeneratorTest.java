package com.risksentinel.eval.data;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyntheticBarGeneratorTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    private SyntheticBarGenerator gen(long seed) {
        return new SyntheticBarGenerator(
                seed,
                List.of("AAPL", "MSFT"),
                T0,
                Duration.ofMinutes(1),
                10,
                0.0,
                0.01,
                Map.of("AAPL", 150.0, "MSFT", 250.0));
    }

    @Test
    void shouldProduceIdenticalBars_forSameSeed() {
        List<Bar> a = new ArrayList<>();
        gen(42L).forEach(a::add);
        List<Bar> b = new ArrayList<>();
        gen(42L).forEach(b::add);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void shouldProduceDifferentBars_forDifferentSeed() {
        List<Bar> a = new ArrayList<>();
        gen(42L).forEach(a::add);
        List<Bar> b = new ArrayList<>();
        gen(43L).forEach(b::add);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void shouldEmitTwoSymbolsTimesNumBars_withMonotonicTimestamps() {
        List<Bar> all = new ArrayList<>();
        gen(7L).forEach(all::add);
        assertThat(all).hasSize(20);
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).timestamp())
                    .isAfterOrEqualTo(all.get(i - 1).timestamp());
        }
    }

    @Test
    void shouldEmitSymbolsInDeclaredOrder_perTimestamp() {
        List<Bar> all = new ArrayList<>();
        gen(7L).forEach(all::add);
        // Each timestamp slot should be {AAPL, MSFT} in that order.
        for (int i = 0; i < all.size(); i += 2) {
            assertThat(all.get(i).symbol()).isEqualTo("AAPL");
            assertThat(all.get(i + 1).symbol()).isEqualTo("MSFT");
        }
    }

    @Test
    void shouldProduceSelfConsistentOhlc() {
        gen(7L).forEach(b -> {
            assertThat(b.high()).isGreaterThanOrEqualTo(b.low());
            assertThat(b.high()).isGreaterThanOrEqualTo(b.open());
            assertThat(b.high()).isGreaterThanOrEqualTo(b.close());
            assertThat(b.low()).isLessThanOrEqualTo(b.open());
            assertThat(b.low()).isLessThanOrEqualTo(b.close());
        });
    }

    @Test
    void shouldReject_whenInitialPricesMissingSymbol() {
        assertThatThrownBy(() -> new SyntheticBarGenerator(
                1L, List.of("AAPL", "MSFT"), T0, Duration.ofMinutes(1), 10,
                0.0, 0.01, Map.of("AAPL", 150.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MSFT");
    }

    @Test
    void shouldReject_whenNumBarsZero() {
        assertThatThrownBy(() -> new SyntheticBarGenerator(
                1L, List.of("AAPL"), T0, Duration.ofMinutes(1), 0,
                0.0, 0.01, Map.of("AAPL", 150.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
