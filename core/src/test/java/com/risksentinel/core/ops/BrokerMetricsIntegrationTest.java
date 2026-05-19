package com.risksentinel.core.ops;

import com.risksentinel.core.broker.FillEvent;
import com.risksentinel.core.broker.FillSink;
import com.risksentinel.core.broker.InstantFillModel;
import com.risksentinel.core.broker.PaperBroker;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.GatewayDecision;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskSnapshotCache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerMetricsIntegrationTest {

    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Map<String, Instrument> REGISTRY = Map.of("AAPL", AAPL);

    private TradeProposal proposal(String id, String symbol) {
        return new TradeProposal(
                id, "port-1", symbol, Side.BUY, 1L, 150.0, 150.0,
                "t", 0.9, "snap-x", Instant.now());
    }

    private static ExecutorService synchronousExecutor() {
        return new AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    @Test
    void shouldExposeBrokerCountersAsPrometheusMetrics() {
        MicrometerMetricsRegistry metrics = new MicrometerMetricsRegistry();
        List<FillEvent> fills = new ArrayList<>();
        FillSink sink = fills::add;

        PaperBroker broker = new PaperBroker(
                REGISTRY,
                new InstantFillModel(),
                sink,
                synchronousExecutor(),
                Clock.systemUTC(),
                new BoundedOrderHistory(
                        Duration.ofHours(1), 10_000, Duration.ofMinutes(1), Clock.systemUTC()),
                LatencyRecorder.noop("submit"),
                LatencyRecorder.noop("e2e"),
                metrics);
        try {
            for (int i = 0; i < 100; i++) {
                broker.submit(proposal("p-" + i, "AAPL"));
            }
            // 5 with unknown symbol should mark REJECTED
            for (int i = 0; i < 5; i++) {
                broker.submit(proposal("z-" + i, "ZZZZ"));
            }

            String scrape = metrics.scrapeText();
            assertThat(scrape).contains("paper_broker_submitted_total");
            assertThat(scrape).contains("paper_broker_filled_total");
            assertThat(scrape).contains("paper_broker_rejected_total");
            assertThat(broker.submittedCount()).isEqualTo(105);
            assertThat(broker.filledCount()).isEqualTo(100);
            assertThat(broker.rejectedCount()).isEqualTo(5);
        } finally {
            broker.shutdown();
        }
    }

    @Test
    void shouldExposeGatewayDecisionCountersTaggedByOutcome() {
        MicrometerMetricsRegistry metrics = new MicrometerMetricsRegistry();
        RiskSnapshotCache cache = new ConcurrentRiskSnapshotCache();
        GatewayState state = new GatewayState(new BoundedIdempotencyCache(
                Duration.ofHours(1), 100_000, Duration.ofMinutes(1), Clock.systemUTC()));
        try {
            GatewayLimits limits = new GatewayLimits(
                    Long.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                    1.0, 1.0, 1.0, Long.MAX_VALUE, Duration.ofDays(1));
            PreTradeGateway gw = new PreTradeGateway(
                    cache, REGISTRY, limits, state, Clock.systemUTC(),
                    LatencyRecorder.noop("gw"), metrics);

            // 10 accepts
            for (int i = 0; i < 10; i++) {
                assertThat(gw.decide(proposal(UUID.randomUUID().toString(), "AAPL")))
                        .isInstanceOf(GatewayDecision.Accept.class);
            }
            // 3 rejects on unknown symbol → FAT_FINGER from FatFingerCheck (UNKNOWN_SYMBOL code)
            for (int i = 0; i < 3; i++) {
                gw.decide(proposal(UUID.randomUUID().toString(), "ZZZZ"));
            }

            String scrape = metrics.scrapeText();
            assertThat(scrape).contains("gateway_decide_total");
            assertThat(scrape).contains("decision=\"accept\"");
            assertThat(scrape).contains("decision=\"reject\"");
        } finally {
            state.shutdown();
        }
    }
}
