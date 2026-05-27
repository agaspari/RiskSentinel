package com.risksentinel.eval.strategy;

import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.eval.data.Bar;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FatFingerStrategyTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static Bar bar(String sym, int idx) {
        return new Bar(sym, T0.plusSeconds(idx), 150.0, 151.0, 149.0, 150.5, 1L);
    }

    @Test
    void shouldEmitOversizeBuy_everyBar() {
        FatFingerStrategy s = new FatFingerStrategy("port-1", "AAPL", 1_000_000L);
        for (int i = 0; i < 20; i++) {
            List<TradeProposal> out = s.onBar(bar("AAPL", i), null, CLOCK);
            assertThat(out).hasSize(1);
            assertThat(out.get(0).side()).isEqualTo(Side.BUY);
            assertThat(out.get(0).quantity()).isEqualTo(1_000_000L);
        }
    }

    @Test
    void shouldEmitDistinctProposalIds_perBar() {
        FatFingerStrategy s = new FatFingerStrategy("port-1", "AAPL", 1_000_000L);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            ids.add(s.onBar(bar("AAPL", i), null, CLOCK).get(0).proposalId());
        }
        assertThat(ids).hasSize(10);
    }

    @Test
    void shouldIgnoreBarsForOtherSymbols() {
        FatFingerStrategy s = new FatFingerStrategy("port-1", "AAPL", 1_000_000L);
        assertThat(s.onBar(bar("MSFT", 0), null, CLOCK)).isEmpty();
    }
}
