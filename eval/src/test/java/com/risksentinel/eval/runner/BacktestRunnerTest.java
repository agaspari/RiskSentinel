package com.risksentinel.eval.runner;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.eval.data.Bar;
import com.risksentinel.eval.data.InMemoryMarketData;
import com.risksentinel.eval.data.SyntheticBarGenerator;
import com.risksentinel.eval.report.BacktestReport;
import com.risksentinel.eval.strategy.BuyAndHoldStrategy;
import com.risksentinel.eval.strategy.FatFingerStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class BacktestRunnerTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    private static GatewayLimits generousLimits() {
        return new GatewayLimits(
                10_000L,      // maxPositionQty
                10_000_000.0, // maxGrossExposure
                10_000_000.0, // maxNetExposure
                1.0,          // maxHHI
                1.0,          // maxSectorWeight
                10.0,         // fatFingerPriceDevPct (very permissive)
                10_000L,      // fatFingerMaxQty
                Duration.ofDays(36500));
    }

    private static Map<String, Instrument> oneInstrument() {
        return Map.of("AAPL", new Instrument("AAPL", "tech", "US", 150.0));
    }

    @Test
    void shouldFillBuyAndHold_byEndOfRun() {
        SyntheticBarGenerator data = new SyntheticBarGenerator(
                42L, List.of("AAPL"), T0, Duration.ofMinutes(1), 10, 0.0, 0.005,
                Map.of("AAPL", 150.0));
        List<Bar> bars = new ArrayList<>();
        data.forEach(bars::add);
        double firstClose = bars.get(0).close();
        double lastClose = bars.get(bars.size() - 1).close();

        BacktestSystem sys = new BacktestSystem(oneInstrument(), generousLimits(), T0);
        BacktestReport report = new BacktestRunner(sys)
                .run(new InMemoryMarketData(bars), new BuyAndHoldStrategy("port-1", "AAPL", 100L));

        assertThat(report.barsProcessed()).isEqualTo(10);
        assertThat(report.totalProposals()).isEqualTo(1);
        assertThat(report.accepted()).isEqualTo(1);
        assertThat(report.rejected()).isZero();
        assertThat(report.endingPositionBySymbol()).containsEntry("AAPL", 100L);

        // PnL = cashFlow (-100 * firstClose) + endingPosition * lastClose
        double expectedPnl = 100L * (lastClose - firstClose);
        assertThat(report.endingMarkToMarketPnl()).isCloseTo(expectedPnl, offset(1e-6));
    }

    @Test
    void shouldRejectAllFatFingers_byGateway() {
        SyntheticBarGenerator data = new SyntheticBarGenerator(
                42L, List.of("AAPL"), T0, Duration.ofMinutes(1), 20, 0.0, 0.005,
                Map.of("AAPL", 150.0));
        BacktestSystem sys = new BacktestSystem(oneInstrument(), generousLimits(), T0);
        BacktestReport report = new BacktestRunner(sys)
                .run(data, new FatFingerStrategy("port-1", "AAPL", 1_000_000L));

        assertThat(report.barsProcessed()).isEqualTo(20);
        assertThat(report.totalProposals()).isEqualTo(20);
        assertThat(report.accepted()).isZero();
        assertThat(report.rejected()).isEqualTo(20);
        assertThat(report.rejectsByCode()).containsEntry("FAT_FINGER_QUANTITY", 20);
        assertThat(report.endingPositionBySymbol()).isEmpty();
    }

    @Test
    void shouldApplyFillSynchronously_whenSubmitReturns() {
        // BuyAndHold on bar 1; after runner returns, position is reflected.
        SyntheticBarGenerator data = new SyntheticBarGenerator(
                42L, List.of("AAPL"), T0, Duration.ofMinutes(1), 3, 0.0, 0.001,
                Map.of("AAPL", 150.0));
        BacktestSystem sys = new BacktestSystem(oneInstrument(), generousLimits(), T0);
        new BacktestRunner(sys).run(data, new BuyAndHoldStrategy("port-1", "AAPL", 100L));

        // If the executor were async with a real queue, the fill might land later.
        // Here it must already be applied because DirectExecutorService ran it
        // before submit() returned.
        assertThat(sys.positions().getPosition("port-1", "AAPL"))
                .isPresent()
                .get()
                .extracting(p -> p.quantity())
                .isEqualTo(100L);
    }

    @Test
    void shouldProduceDeterministicReport_whenRunTwice() {
        SyntheticBarGenerator data1 = new SyntheticBarGenerator(
                42L, List.of("AAPL"), T0, Duration.ofMinutes(1), 10, 0.0, 0.005,
                Map.of("AAPL", 150.0));
        SyntheticBarGenerator data2 = new SyntheticBarGenerator(
                42L, List.of("AAPL"), T0, Duration.ofMinutes(1), 10, 0.0, 0.005,
                Map.of("AAPL", 150.0));

        BacktestReport a = new BacktestRunner(new BacktestSystem(oneInstrument(), generousLimits(), T0))
                .run(data1, new BuyAndHoldStrategy("port-1", "AAPL", 100L));
        BacktestReport b = new BacktestRunner(new BacktestSystem(oneInstrument(), generousLimits(), T0))
                .run(data2, new BuyAndHoldStrategy("port-1", "AAPL", 100L));

        assertThat(a.strategyName()).isEqualTo(b.strategyName());
        assertThat(a.startedAt()).isEqualTo(b.startedAt());
        assertThat(a.endedAt()).isEqualTo(b.endedAt());
        assertThat(a.barsProcessed()).isEqualTo(b.barsProcessed());
        assertThat(a.totalProposals()).isEqualTo(b.totalProposals());
        assertThat(a.accepted()).isEqualTo(b.accepted());
        assertThat(a.rejected()).isEqualTo(b.rejected());
        assertThat(a.rejectsByCode()).isEqualTo(b.rejectsByCode());
        assertThat(a.endingPositionBySymbol()).isEqualTo(b.endingPositionBySymbol());
        assertThat(a.endingMarkToMarketPnl()).isEqualTo(b.endingMarkToMarketPnl());
        // gatewayLatency is wall-clock dependent; intentionally not compared.
    }

    @Test
    void shouldHandleEmptyDataSource() {
        BacktestSystem sys = new BacktestSystem(oneInstrument(), generousLimits(), T0);
        BacktestReport report = new BacktestRunner(sys)
                .run(new InMemoryMarketData(List.of()), new BuyAndHoldStrategy("port-1", "AAPL", 100L));
        assertThat(report.barsProcessed()).isZero();
        assertThat(report.totalProposals()).isZero();
        assertThat(report.endingMarkToMarketPnl()).isZero();
    }
}
