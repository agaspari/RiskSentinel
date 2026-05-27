package com.risksentinel.core.gateway;

import com.risksentinel.core.audit.AuditLog;
import com.risksentinel.core.audit.Caller;
import com.risksentinel.core.audit.DecisionRecord;
import com.risksentinel.core.audit.SqliteAuditLog;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreTradeGatewayCallerTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    private static TradeProposal proposal(String id) {
        return new TradeProposal(
                id, "port-1", "AAPL", Side.BUY,
                100L, 150.0, 150.0, "test", 0.9, "snap-x", T0);
    }

    private static PreTradeGateway gatewayWith(AuditLog audit) {
        GatewayLimits limits = new GatewayLimits(
                10_000L, 1_000_000.0, 1_000_000.0,
                1.0, 1.0, 0.10, 100_000L, Duration.ofMinutes(5));
        return new PreTradeGateway(
                new ConcurrentRiskSnapshotCache(),
                Map.of("AAPL", new Instrument("AAPL", "tech", "US", 150.0)),
                limits,
                new GatewayState(),
                Clock.systemUTC(),
                com.risksentinel.core.ops.LatencyRecorder.noop("g"),
                new com.risksentinel.core.ops.NoopMetricsRegistry(),
                audit);
    }

    @Test
    void shouldRecordSystemCaller_whenDeprecatedDecideUsed(@TempDir Path dir) {
        try (SqliteAuditLog audit = new SqliteAuditLog(dir.resolve("audit.db"))) {
            PreTradeGateway gateway = gatewayWith(audit);

            gateway.decide(proposal("p-system"));

            DecisionRecord r = audit.findByProposalId("p-system").orElseThrow();
            assertThat(r.callerKind()).isEqualTo(Caller.CallerKind.SYSTEM);
            assertThat(r.callerId()).isEqualTo("system");
        }
    }

    @Test
    void shouldRecordOperatorCaller_whenOverloadUsed(@TempDir Path dir) {
        try (SqliteAuditLog audit = new SqliteAuditLog(dir.resolve("audit.db"))) {
            PreTradeGateway gateway = gatewayWith(audit);

            gateway.decide(proposal("p-op"), Caller.operator("alice"));

            DecisionRecord r = audit.findByProposalId("p-op").orElseThrow();
            assertThat(r.callerKind()).isEqualTo(Caller.CallerKind.OPERATOR);
            assertThat(r.callerId()).isEqualTo("alice");
        }
    }

    @Test
    void shouldRecordAgentCaller_onReject(@TempDir Path dir) {
        try (SqliteAuditLog audit = new SqliteAuditLog(dir.resolve("audit.db"))) {
            PreTradeGateway gateway = gatewayWith(audit);

            TradeProposal fatFinger = new TradeProposal(
                    "p-fat", "port-1", "AAPL", Side.BUY,
                    500_000L, 150.0, 150.0, "adversarial", 0.9, "snap-x", T0);
            gateway.decide(fatFinger, Caller.agent("eval-agent"));

            DecisionRecord r = audit.findByProposalId("p-fat").orElseThrow();
            assertThat(r.callerKind()).isEqualTo(Caller.CallerKind.AGENT);
            assertThat(r.callerId()).isEqualTo("eval-agent");
            assertThat(r.firstRejectCode()).isEqualTo("FAT_FINGER_QUANTITY");
        }
    }
}
