package com.risksentinel.core.ops;

import com.risksentinel.core.broker.FillEvent;
import com.risksentinel.core.broker.FillSink;
import com.risksentinel.core.broker.InstantFillModel;
import com.risksentinel.core.broker.PaperBroker;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskSnapshotCache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyInstrumentationTest {

    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Map<String, Instrument> REGISTRY = Map.of("AAPL", AAPL);
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    private static GatewayLimits permissiveLimits() {
        return new GatewayLimits(
                Long.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                1.0, 1.0, 1.0, Long.MAX_VALUE, Duration.ofDays(1));
    }

    private TradeProposal proposal(String id) {
        return new TradeProposal(
                id, "port-1", "AAPL", Side.BUY, 1L, 150.0, 150.0,
                "t", 0.9, "snap-x", Instant.now());
    }

    @Test
    void shouldRecordDecideLatency_onEveryGatewayDecision() {
        RiskSnapshotCache cache = new ConcurrentRiskSnapshotCache();
        BoundedIdempotencyCache idem = new BoundedIdempotencyCache(
                Duration.ofHours(1), 100_000, Duration.ofMinutes(1), Clock.systemUTC());
        GatewayState state = new GatewayState(idem);
        LatencyRecorder probe = LatencyRecorder.active("gateway-decide", ONE_SECOND_NANOS, 3);
        try {
            PreTradeGateway gw = new PreTradeGateway(
                    cache, REGISTRY, permissiveLimits(), state, Clock.systemUTC(), probe);

            int n = 1_000;
            for (int i = 0; i < n; i++) {
                gw.decide(proposal(UUID.randomUUID().toString()));
            }

            LatencySnapshot snap = probe.snapshot();
            assertThat(snap.count()).isEqualTo(n);
            assertThat(snap.maxNanos()).isPositive();
            assertThat(snap.p99Nanos()).isPositive();
            assertThat(snap.p50Nanos()).isLessThanOrEqualTo(snap.p99Nanos());
        } finally {
            state.shutdown();
        }
    }

    @Test
    void shouldRecordDecideLatencyEntryAndExit_underConcurrentLoad() throws InterruptedException {
        RiskSnapshotCache cache = new ConcurrentRiskSnapshotCache();
        BoundedIdempotencyCache idem = new BoundedIdempotencyCache(
                Duration.ofHours(1), 1_000_000, Duration.ofMinutes(1), Clock.systemUTC());
        GatewayState state = new GatewayState(idem);
        LatencyRecorder probe = LatencyRecorder.active("gateway-decide", ONE_SECOND_NANOS, 3);
        try {
            PreTradeGateway gw = new PreTradeGateway(
                    cache, REGISTRY, permissiveLimits(), state, Clock.systemUTC(), probe);

            int threads = 16;
            int perThread = 250;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    final int tid = t;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                gw.decide(proposal("t" + tid + "-i" + i));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(probe.snapshot().count()).isEqualTo((long) threads * perThread);
        } finally {
            state.shutdown();
        }
    }

    @Test
    void shouldRecordBrokerSubmitAndEndToEndLatency() {
        LatencyRecorder submit = LatencyRecorder.active("broker-submit", ONE_SECOND_NANOS, 3);
        LatencyRecorder e2e = LatencyRecorder.active("broker-e2e", ONE_SECOND_NANOS, 3);
        List<FillEvent> fills = new java.util.ArrayList<>();
        FillSink sink = fills::add;

        PaperBroker broker = new PaperBroker(
                REGISTRY,
                new InstantFillModel(),
                sink,
                synchronousExecutor(),
                Clock.systemUTC(),
                new BoundedOrderHistory(
                        Duration.ofHours(1), 10_000, Duration.ofMinutes(1), Clock.systemUTC()),
                submit, e2e,
                new NoopMetricsRegistry());
        try {
            int n = 500;
            for (int i = 0; i < n; i++) {
                broker.submit(proposal("p-" + i));
            }
            assertThat(fills).hasSize(n);
            assertThat(submit.snapshot().count()).isEqualTo(n);
            assertThat(e2e.snapshot().count()).isEqualTo(n);
        } finally {
            broker.shutdown();
        }
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
}
