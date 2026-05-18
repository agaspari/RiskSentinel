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

class ConcentrationCheckTest {

    private final RiskCheck check = new ConcentrationCheck();

    @Test
    void shouldPass_whenWellDiversified() {
        // Three roughly equal positions; HHI ≈ 0.33.
        RiskSnapshot snap = snapshot("port-1",
                Map.of(
                        "AAPL", pos("AAPL", 100L, 150.0),    // 15,000 Tech
                        "GOOGL", pos("GOOGL", 150L, 100.0),  // 15,000 Tech
                        "JPM", pos("JPM", 75L, 200.0)),      // 15,000 Finance
                45_000.0, 45_000.0,
                Map.of("Technology", 30_000.0, "Finance", 15_000.0));

        // The fixture's Tech weight sits at ~64% post-trade. Use a sector cap
        // permissive enough that a diversified-but-Tech-heavy portfolio passes.
        GatewayLimits permissive = new GatewayLimits(
                10_000L, 1_000_000.0, 500_000.0,
                0.9, 0.8, 0.10, 100_000L, Duration.ofSeconds(30));

        TradeProposal p = proposal("JPM", Side.BUY, 10L, 200.0); // marginal

        assertThat(check.check(p, ctx(snap, JPM, permissive, new GatewayState()))).isEmpty();
    }

    @Test
    void shouldReject_whenPostTradeHHIExceedsLimit() {
        // Current: one position, AAPL 15,000. HHI already 1.0. maxHHI 0.5 should fail.
        RiskSnapshot snap = snapshot("port-1",
                Map.of("AAPL", pos("AAPL", 100L, 150.0)),
                15_000.0, 15_000.0,
                Map.of("Technology", 15_000.0));

        GatewayLimits limits = new GatewayLimits(
                100_000L, 10_000_000.0, 10_000_000.0,
                0.5, 0.9, 0.10, 1_000_000L, Duration.ofSeconds(30));

        // Add a tiny GOOGL position; still concentrated in AAPL.
        TradeProposal p = proposal("GOOGL", Side.BUY, 10L, 100.0);

        var reasons = check.check(p, ctx(snap, GOOGL, limits, new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .contains(RejectCode.CONCENTRATION_EXCEEDED);
    }

    @Test
    void shouldReject_whenPostTradeSectorWeightExceedsCap() {
        // Current: AAPL 15,000 (Tech) + JPM 15,000 (Finance). 50/50.
        RiskSnapshot snap = snapshot("port-1",
                Map.of(
                        "AAPL", pos("AAPL", 100L, 150.0),
                        "JPM", pos("JPM", 75L, 200.0)),
                30_000.0, 30_000.0,
                Map.of("Technology", 15_000.0, "Finance", 15_000.0));

        GatewayLimits limits = new GatewayLimits(
                100_000L, 10_000_000.0, 10_000_000.0,
                0.99, 0.55, 0.10, 1_000_000L, Duration.ofSeconds(30));

        // Buy more AAPL — push Tech above 55% cap.
        TradeProposal p = proposal("AAPL", Side.BUY, 200L, 150.0);

        var reasons = check.check(p, ctx(snap, AAPL, limits, new GatewayState()));

        assertThat(reasons).extracting(RejectReason::code)
                .contains(RejectCode.SECTOR_CAP_EXCEEDED);
    }

    @Test
    void shouldNoOp_whenSnapshotNull() {
        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);
        assertThat(check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()))).isEmpty();
    }

    @Test
    void shouldNoOp_whenInstrumentUnknown() {
        RiskSnapshot snap = snapshot("port-1",
                Map.of("AAPL", pos("AAPL", 100L, 150.0)),
                15_000.0, 15_000.0,
                Map.of("Technology", 15_000.0));
        TradeProposal p = proposal("ZZZZ", Side.BUY, 100L, 150.0);
        assertThat(check.check(p, ctx(snap, null, defaultLimits(), new GatewayState()))).isEmpty();
    }
}
