package com.risksentinel.eval.property;

import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.eval.data.Bar;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoisyStrategyTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static Bar bar(int i) {
        return new Bar("AAPL", T0.plusSeconds(i), 150.0, 151.0, 149.0, 150.5, 1L);
    }

    private static List<TradeProposal> drive(NoisyStrategy s, int bars) {
        List<TradeProposal> out = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            out.addAll(s.onBar(bar(i), null, CLOCK));
        }
        return out;
    }

    @Test
    void shouldProduceIdenticalProposals_forSameSeed() {
        NoisyStrategy a = new NoisyStrategy("port-1", 42L, 3, 100L);
        NoisyStrategy b = new NoisyStrategy("port-1", 42L, 3, 100L);
        List<TradeProposal> aps = drive(a, 30);
        List<TradeProposal> bps = drive(b, 30);
        assertThat(aps).hasSize(bps.size());
        for (int i = 0; i < aps.size(); i++) {
            assertThat(aps.get(i).side()).isEqualTo(bps.get(i).side());
            assertThat(aps.get(i).quantity()).isEqualTo(bps.get(i).quantity());
        }
    }

    @Test
    void shouldProduceDifferentProposals_forDifferentSeed() {
        List<TradeProposal> a = drive(new NoisyStrategy("port-1", 1L, 3, 100L), 50);
        List<TradeProposal> b = drive(new NoisyStrategy("port-1", 2L, 3, 100L), 50);
        // Highly likely to differ across 50 bars × up to 3 proposals.
        assertThat(a).isNotEqualTo(b);
    }
}
