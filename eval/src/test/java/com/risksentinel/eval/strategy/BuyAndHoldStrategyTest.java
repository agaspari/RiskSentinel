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

class BuyAndHoldStrategyTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static Bar bar(String sym, Instant ts, double close) {
        return new Bar(sym, ts, close, close * 1.01, close * 0.99, close, 1000L);
    }

    @Test
    void shouldEmitOneBuy_onFirstMatchingBar() {
        BuyAndHoldStrategy s = new BuyAndHoldStrategy("port-1", "AAPL", 100L);
        List<TradeProposal> out = s.onBar(bar("AAPL", T0, 150.0), null, CLOCK);
        assertThat(out).hasSize(1);
        TradeProposal p = out.get(0);
        assertThat(p.portfolioId()).isEqualTo("port-1");
        assertThat(p.symbol()).isEqualTo("AAPL");
        assertThat(p.side()).isEqualTo(Side.BUY);
        assertThat(p.quantity()).isEqualTo(100L);
        assertThat(p.limitPrice()).isEqualTo(150.0);
    }

    @Test
    void shouldEmitNothing_onSubsequentBars() {
        BuyAndHoldStrategy s = new BuyAndHoldStrategy("port-1", "AAPL", 100L);
        s.onBar(bar("AAPL", T0, 150.0), null, CLOCK);
        for (int i = 1; i < 5; i++) {
            assertThat(s.onBar(bar("AAPL", T0.plusSeconds(i), 150.0 + i), null, CLOCK))
                    .isEmpty();
        }
    }

    @Test
    void shouldEmitNothing_forNonMatchingSymbol() {
        BuyAndHoldStrategy s = new BuyAndHoldStrategy("port-1", "AAPL", 100L);
        assertThat(s.onBar(bar("MSFT", T0, 250.0), null, CLOCK)).isEmpty();
    }

    @Test
    void shouldExposePortfolioId() {
        BuyAndHoldStrategy s = new BuyAndHoldStrategy("port-1", "AAPL", 100L);
        assertThat(s.portfolioId()).isEqualTo("port-1");
    }
}
