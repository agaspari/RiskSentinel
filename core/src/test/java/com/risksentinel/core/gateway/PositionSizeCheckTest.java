package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.risksentinel.core.gateway.CheckTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class PositionSizeCheckTest {

    private final RiskCheck check = new PositionSizeCheck();

    @Test
    void shouldReject_whenPostTradeQtyExceedsLimit_onBuy() {
        // Current 9_500 + buy 1_000 = 10_500 > 10_000
        RiskSnapshot snap = snapshot("port-1",
                Map.of("AAPL", pos("AAPL", 9_500L, 150.0)),
                9_500 * 150.0,
                9_500 * 150.0,
                Map.of("Technology", 9_500 * 150.0));

        TradeProposal p = proposal("AAPL", Side.BUY, 1_000L, 150.0);

        var reasons = check.check(p, ctx(snap, AAPL, defaultLimits(), new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .contains(RejectCode.POSITION_SIZE_EXCEEDED);
    }

    @Test
    void shouldReject_whenPostTradeQtyExceedsLimit_onShortSell() {
        // Current 0; sell 20_000 → -20_000 → |20_000| > 10_000
        TradeProposal p = proposal("AAPL", Side.SELL, 20_000L, 150.0);

        var reasons = check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .contains(RejectCode.POSITION_SIZE_EXCEEDED);
    }

    @Test
    void shouldPass_whenAtExactlyLimit() {
        TradeProposal p = proposal("AAPL", Side.BUY, 10_000L, 150.0);
        assertThat(check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()))).isEmpty();
    }

    @Test
    void shouldHandleNullSnapshot_asZeroCurrentPosition() {
        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);
        assertThat(check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()))).isEmpty();
    }

    @Test
    void shouldHandleMissingSymbolInSnapshot_asZeroCurrentPosition() {
        RiskSnapshot snap = snapshot("port-1",
                Map.of("GOOGL", pos("GOOGL", 50L, 100.0)),
                5000.0, 5000.0, Map.of("Technology", 5000.0));

        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);

        assertThat(check.check(p, ctx(snap, AAPL, defaultLimits(), new GatewayState()))).isEmpty();
    }
}
