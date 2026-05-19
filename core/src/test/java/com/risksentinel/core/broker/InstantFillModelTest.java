package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InstantFillModelTest {

    private static final Instant T0 = Instant.parse("2026-05-18T12:00:00Z");
    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);

    private final FillModel model = new InstantFillModel();

    private Order order(Side side, long qty, double limit) {
        return new Order(
                "ord-1", "prop-1", "port-1", "AAPL", side,
                qty, limit, OrderStatus.NEW, T0, T0);
    }

    @Test
    void shouldEmitFullFill_atLimitPrice_onBuy() {
        Order o = order(Side.BUY, 100L, 150.0);
        AtomicLong seq = new AtomicLong();

        Optional<FillEvent> fill = model.simulate(o, AAPL, T0, seq::incrementAndGet);

        assertThat(fill).isPresent();
        FillEvent fe = fill.get();
        assertThat(fe.filledQuantity()).isEqualTo(100L);
        assertThat(fe.filledPrice()).isEqualTo(150.0);
        assertThat(fe.filledAt()).isEqualTo(T0);
    }

    @Test
    void shouldEmitFullFill_atLimitPrice_onSell() {
        Order o = order(Side.SELL, 50L, 200.0);
        AtomicLong seq = new AtomicLong();

        Optional<FillEvent> fill = model.simulate(o, AAPL, T0, seq::incrementAndGet);

        assertThat(fill).isPresent();
        assertThat(fill.get().filledQuantity()).isEqualTo(50L);
        assertThat(fill.get().filledPrice()).isEqualTo(200.0);
    }

    @Test
    void shouldPreserveOrderIdAndProposalId_inFill() {
        Order o = order(Side.BUY, 10L, 150.0);
        AtomicLong seq = new AtomicLong();

        FillEvent fe = model.simulate(o, AAPL, T0, seq::incrementAndGet).orElseThrow();

        assertThat(fe.orderId()).isEqualTo("ord-1");
        assertThat(fe.proposalId()).isEqualTo("prop-1");
    }

    @Test
    void shouldUseProvidedFillIdGenerator() {
        Order o = order(Side.BUY, 10L, 150.0);
        AtomicLong seq = new AtomicLong(41L);

        FillEvent fe = model.simulate(o, AAPL, T0, seq::incrementAndGet).orElseThrow();

        assertThat(fe.fillId()).isEqualTo(42L);
    }

    @Test
    void shouldReturnEmpty_whenInstrumentUnknown() {
        Order o = order(Side.BUY, 10L, 150.0);
        AtomicLong seq = new AtomicLong();

        Optional<FillEvent> fill = model.simulate(o, null, T0, seq::incrementAndGet);

        assertThat(fill).isEmpty();
    }
}
