package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BarPriceFillModelTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");
    private static final Instrument AAPL = new Instrument("AAPL", "tech", "US", 150.0);

    private static Order order(Side side, double limit) {
        return new Order("ord-1", "p-1", "port-1", "AAPL", side, 100L,
                limit, OrderStatus.NEW, T0, T0);
    }

    @Test
    void shouldFillBuy_whenCloseAtOrBelowLimit() {
        Map<String, Double> closes = Map.of("AAPL", 149.0);
        BarPriceFillModel m = new BarPriceFillModel(() -> closes);
        Optional<FillEvent> fill = m.simulate(order(Side.BUY, 150.0), AAPL, T0, new AtomicLong(1)::incrementAndGet);
        assertThat(fill).isPresent();
        assertThat(fill.get().filledPrice()).isEqualTo(149.0);
        assertThat(fill.get().filledQuantity()).isEqualTo(100L);
    }

    @Test
    void shouldNotFillBuy_whenCloseAboveLimit() {
        Map<String, Double> closes = Map.of("AAPL", 151.0);
        BarPriceFillModel m = new BarPriceFillModel(() -> closes);
        assertThat(m.simulate(order(Side.BUY, 150.0), AAPL, T0, new AtomicLong(1)::incrementAndGet))
                .isEmpty();
    }

    @Test
    void shouldFillSell_whenCloseAtOrAboveLimit() {
        Map<String, Double> closes = Map.of("AAPL", 151.0);
        BarPriceFillModel m = new BarPriceFillModel(() -> closes);
        Optional<FillEvent> fill = m.simulate(order(Side.SELL, 150.0), AAPL, T0, new AtomicLong(1)::incrementAndGet);
        assertThat(fill).isPresent();
        assertThat(fill.get().filledPrice()).isEqualTo(151.0);
    }

    @Test
    void shouldNotFillSell_whenCloseBelowLimit() {
        Map<String, Double> closes = Map.of("AAPL", 149.0);
        BarPriceFillModel m = new BarPriceFillModel(() -> closes);
        assertThat(m.simulate(order(Side.SELL, 150.0), AAPL, T0, new AtomicLong(1)::incrementAndGet))
                .isEmpty();
    }

    @Test
    void shouldReturnEmpty_whenSymbolMissingFromCloses() {
        BarPriceFillModel m = new BarPriceFillModel(HashMap::new);
        assertThat(m.simulate(order(Side.BUY, 150.0), AAPL, T0, new AtomicLong(1)::incrementAndGet))
                .isEmpty();
    }
}
