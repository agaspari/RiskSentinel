package com.risksentinel.eval.strategy;

import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.eval.data.Bar;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeanReversionStrategyTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static Bar bar(String sym, double close, int idx) {
        return new Bar(sym, T0.plusSeconds(idx), close, close * 1.001, close * 0.999, close, 1L);
    }

    private static List<TradeProposal> feed(MeanReversionStrategy s, double[] closes) {
        List<TradeProposal> last = List.of();
        for (int i = 0; i < closes.length; i++) {
            last = s.onBar(bar("AAPL", closes[i], i), null, CLOCK);
        }
        return last;
    }

    @Test
    void shouldEmitNothing_whileWindowFilling() {
        MeanReversionStrategy s = new MeanReversionStrategy("port-1", "AAPL", 4, 1.5, 10L);
        assertThat(s.onBar(bar("AAPL", 100.0, 0), null, CLOCK)).isEmpty();
        assertThat(s.onBar(bar("AAPL", 100.0, 1), null, CLOCK)).isEmpty();
        assertThat(s.onBar(bar("AAPL", 100.0, 2), null, CLOCK)).isEmpty();
    }

    @Test
    void shouldEmitNothing_whenStddevZero() {
        MeanReversionStrategy s = new MeanReversionStrategy("port-1", "AAPL", 4, 1.5, 10L);
        // All same → stddev=0 → no signal.
        List<TradeProposal> last = feed(s, new double[]{100.0, 100.0, 100.0, 100.0, 100.0});
        assertThat(last).isEmpty();
    }

    @Test
    void shouldEmitBuy_onSignificantDip() {
        MeanReversionStrategy s = new MeanReversionStrategy("port-1", "AAPL", 4, 1.5, 10L);
        // Sequence ending in a big dip: 100, 100, 100, 100, 90  → z ≈ -2 → BUY.
        List<TradeProposal> last = feed(s, new double[]{100.0, 100.0, 100.0, 100.0, 90.0});
        assertThat(last).hasSize(1);
        assertThat(last.get(0).side()).isEqualTo(Side.BUY);
        assertThat(last.get(0).quantity()).isEqualTo(10L);
    }

    @Test
    void shouldEmitSell_onSignificantSpike() {
        MeanReversionStrategy s = new MeanReversionStrategy("port-1", "AAPL", 4, 1.5, 10L);
        List<TradeProposal> last = feed(s, new double[]{100.0, 100.0, 100.0, 100.0, 110.0});
        assertThat(last).hasSize(1);
        assertThat(last.get(0).side()).isEqualTo(Side.SELL);
    }

    @Test
    void shouldIgnoreBarsForOtherSymbols() {
        MeanReversionStrategy s = new MeanReversionStrategy("port-1", "AAPL", 4, 1.5, 10L);
        for (int i = 0; i < 10; i++) {
            assertThat(s.onBar(bar("MSFT", 200.0 + i, i), null, CLOCK)).isEmpty();
        }
    }
}
