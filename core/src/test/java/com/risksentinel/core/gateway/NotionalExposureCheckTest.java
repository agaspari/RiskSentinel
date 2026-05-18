package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.risksentinel.core.gateway.CheckTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class NotionalExposureCheckTest {

    private final RiskCheck check = new NotionalExposureCheck();

    private GatewayLimits tightLimits() {
        // Tight caps so we can demonstrate reject behavior with modest numbers.
        return new GatewayLimits(
                10_000L,
                20_000.0,     // gross cap
                10_000.0,     // net cap
                0.9, 0.9, 0.10, 100_000L, Duration.ofSeconds(30));
    }

    @Test
    void shouldReject_whenPostTradeGrossExceedsLimit() {
        // Current: 100 GOOGL @ 100 = 10,000 gross/net
        RiskSnapshot snap = snapshot("port-1",
                Map.of("GOOGL", pos("GOOGL", 100L, 100.0)),
                10_000.0, 10_000.0,
                Map.of("Technology", 10_000.0));

        // Buy 100 AAPL @ 150 → adds 15,000 to gross → 25,000 > 20,000 cap
        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);

        var reasons = check.check(p, ctx(snap, AAPL, tightLimits(), new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .contains(RejectCode.GROSS_EXPOSURE_EXCEEDED);
    }

    @Test
    void shouldReject_whenPostTradeNetExceedsLimit() {
        // Current net 0. Buy 100 AAPL @ 150 → net 15,000 > 10,000 cap
        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);

        var reasons = check.check(p, ctx(null, AAPL, tightLimits(), new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .contains(RejectCode.NET_EXPOSURE_EXCEEDED);
    }

    @Test
    void shouldPass_whenWithinBothLimits() {
        TradeProposal p = proposal("AAPL", Side.BUY, 50L, 150.0); // 7,500 notional
        assertThat(check.check(p, ctx(null, AAPL, tightLimits(), new GatewayState()))).isEmpty();
    }

    @Test
    void shouldReduceExposure_whenSellingExistingLong() {
        // Hold 100 AAPL (15,000 gross/net). Sell 50 → post: 50 AAPL (7,500 gross/net).
        RiskSnapshot snap = snapshot("port-1",
                Map.of("AAPL", pos("AAPL", 100L, 150.0)),
                15_000.0, 15_000.0,
                Map.of("Technology", 15_000.0));

        TradeProposal p = proposal("AAPL", Side.SELL, 50L, 150.0);

        assertThat(check.check(p, ctx(snap, AAPL, tightLimits(), new GatewayState()))).isEmpty();
    }

    @Test
    void shouldNoOp_whenInstrumentUnknown() {
        TradeProposal p = proposal("ZZZZ", Side.BUY, 100L, 150.0);
        assertThat(check.check(p, ctx(null, null, tightLimits(), new GatewayState()))).isEmpty();
    }
}
