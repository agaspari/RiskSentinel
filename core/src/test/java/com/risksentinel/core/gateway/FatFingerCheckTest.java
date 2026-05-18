package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import org.junit.jupiter.api.Test;

import static com.risksentinel.core.gateway.CheckTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class FatFingerCheckTest {

    private final RiskCheck check = new FatFingerCheck();

    @Test
    void shouldReject_whenQtyAboveCeiling() {
        TradeProposal p = proposal("AAPL", Side.BUY, 200_000L, 150.0);

        var reasons = check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .contains(RejectCode.FAT_FINGER_QUANTITY);
    }

    @Test
    void shouldReject_whenLimitPriceDeviatesAboveThreshold() {
        // 10% threshold; market 150; limit 200 → 33% deviation
        TradeProposal p = proposal("AAPL", Side.BUY, 100, 200.0);

        var reasons = check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .contains(RejectCode.FAT_FINGER_PRICE_DEVIATION);
    }

    @Test
    void shouldPass_whenPriceWithinThreshold() {
        TradeProposal p = proposal("AAPL", Side.BUY, 100, 155.0); // 3.3% from 150
        assertThat(check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()))).isEmpty();
    }

    @Test
    void shouldReject_whenInstrumentUnknown() {
        TradeProposal p = proposal("ZZZZ", Side.BUY, 100, 150.0);

        var reasons = check.check(p, ctx(null, null, defaultLimits(), new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .contains(RejectCode.UNKNOWN_SYMBOL);
    }

    @Test
    void shouldEmitBothReasons_whenQtyAndPriceBothBad() {
        TradeProposal p = proposal("AAPL", Side.BUY, 200_000L, 999.0);

        var reasons = check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .containsExactlyInAnyOrder(
                        RejectCode.FAT_FINGER_QUANTITY,
                        RejectCode.FAT_FINGER_PRICE_DEVIATION);
    }
}
