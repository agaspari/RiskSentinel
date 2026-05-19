package com.risksentinel.core.audit;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.GatewayDecision;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.ops.LatencyRecorder;
import com.risksentinel.core.ops.MetricsRegistry;
import com.risksentinel.core.ops.NoopMetricsRegistry;
import com.risksentinel.core.ops.Tags;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskSnapshotCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class AuditPipelineTest {

    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Map<String, Instrument> REGISTRY = Map.of("AAPL", AAPL);

    private TradeProposal proposal(String symbol, long qty, double price) {
        return new TradeProposal(
                UUID.randomUUID().toString(),
                "port-1", symbol, Side.BUY,
                qty, price, price,
                "rationale", 0.9, "snap-x", Instant.now());
    }

    private GatewayLimits limits() {
        // Tight enough that fat-finger rejects are easy to trigger; permissive on the rest.
        return new GatewayLimits(
                10_000L, 1_000_000.0, 1_000_000.0,
                1.0, 1.0, 0.10, 100_000L, Duration.ofMinutes(5));
    }

    private boolean waitFor(BooleanSupplier cond, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    @Test
    void shouldPersistDecisionsAcrossJvmRestart_simulated(@TempDir Path dir) throws InterruptedException {
        Path db = dir.resolve("audit.db");
        MetricsRegistry metrics = new NoopMetricsRegistry();
        RiskSnapshotCache cache = new ConcurrentRiskSnapshotCache();
        GatewayState state = new GatewayState();

        // First "JVM lifetime": gateway writes via the async log to SQLite, then both close.
        TradeProposal acceptP = proposal("AAPL", 100L, 150.0);
        TradeProposal rejectFat = proposal("AAPL", 200_000L, 150.0); // exceeds fatFingerMaxQty
        TradeProposal rejectUnk = proposal("ZZZZ", 100L, 150.0);     // unknown symbol

        try {
            try (SqliteAuditLog sqlite = new SqliteAuditLog(db);
                 AsyncAuditLog async = new AsyncAuditLog(
                         sqlite, 64, metrics.counter("audit_dropped_total", Tags.empty()))) {

                PreTradeGateway gw = new PreTradeGateway(
                        cache, REGISTRY, limits(), state, Clock.systemUTC(),
                        LatencyRecorder.noop("gw"), metrics, async);

                assertThat(gw.decide(acceptP)).isInstanceOf(GatewayDecision.Accept.class);
                assertThat(gw.decide(rejectFat)).isInstanceOf(GatewayDecision.Reject.class);
                assertThat(gw.decide(rejectUnk)).isInstanceOf(GatewayDecision.Reject.class);

                // Wait for the writer to drain — close() also drains, but we want to assert
                // count() works pre-close too.
                assertThat(waitFor(() -> async.count() == 3L, Duration.ofSeconds(2))).isTrue();
            }
        } finally {
            state.shutdown();
        }

        // Second "JVM lifetime": open a fresh SqliteAuditLog on the same file.
        try (SqliteAuditLog reopened = new SqliteAuditLog(db)) {
            assertThat(reopened.count()).isEqualTo(3L);

            DecisionRecord acceptR = reopened.findByProposalId(acceptP.proposalId()).orElseThrow();
            assertThat(acceptR.type()).isEqualTo(DecisionType.ACCEPT);
            assertThat(acceptR.firstRejectCode()).isNull();

            DecisionRecord fatR = reopened.findByProposalId(rejectFat.proposalId()).orElseThrow();
            assertThat(fatR.type()).isEqualTo(DecisionType.REJECT);
            assertThat(fatR.firstRejectCode()).isEqualTo("FAT_FINGER_QUANTITY");

            DecisionRecord unkR = reopened.findByProposalId(rejectUnk.proposalId()).orElseThrow();
            assertThat(unkR.type()).isEqualTo(DecisionType.REJECT);
            assertThat(unkR.firstRejectCode()).isEqualTo("UNKNOWN_SYMBOL");
            assertThat(unkR.reasonsJson()).contains("UNKNOWN_SYMBOL");
        }
    }

    @Test
    void shouldNotFailGateway_whenAuditDelegateThrows(@TempDir Path dir) {
        MetricsRegistry metrics = new NoopMetricsRegistry();
        RiskSnapshotCache cache = new ConcurrentRiskSnapshotCache();
        GatewayState state = new GatewayState();

        AuditLog broken = new AuditLog() {
            @Override public void record(DecisionRecord r) {
                throw new RuntimeException("simulated audit failure");
            }
            @Override public java.util.Optional<DecisionRecord> findByProposalId(String pid) {
                return java.util.Optional.empty();
            }
            @Override public java.util.List<DecisionRecord> findByPortfolio(String pid, int l) {
                return java.util.List.of();
            }
            @Override public long count() { return 0; }
            @Override public void close() {}
        };

        try {
            PreTradeGateway gw = new PreTradeGateway(
                    cache, REGISTRY, limits(), state, Clock.systemUTC(),
                    LatencyRecorder.noop("gw"), metrics, broken);

            GatewayDecision d = gw.decide(proposal("AAPL", 100L, 150.0));
            assertThat(d).isInstanceOf(GatewayDecision.Accept.class);
        } finally {
            state.shutdown();
        }
    }
}
