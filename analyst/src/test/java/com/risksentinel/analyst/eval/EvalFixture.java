package com.risksentinel.analyst.eval;

import com.risksentinel.core.audit.AuditLog;
import com.risksentinel.core.audit.SqliteAuditLog;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.positions.ConcurrentPositionBook;
import com.risksentinel.core.positions.PositionBook;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskSnapshotCache;
import com.risksentinel.mcp.ToolRegistry;
import com.risksentinel.mcp.tools.DisengageKillSwitchTool;
import com.risksentinel.mcp.tools.EngageKillSwitchTool;
import com.risksentinel.mcp.tools.GetInstrumentTool;
import com.risksentinel.mcp.tools.GetSnapshotTool;
import com.risksentinel.mcp.tools.ListPositionsTool;
import com.risksentinel.mcp.tools.ListRecentDecisionsTool;
import com.risksentinel.mcp.tools.SubmitProposalTool;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Test scaffolding for the trust-boundary eval. Builds the same wiring as
 * {@code com.risksentinel.mcp.Main#buildRegistry} but exposes every component
 * so the eval scenarios can assert post-state against the position book,
 * kill switch, audit log, etc.
 *
 * <p>Each fixture owns a synchronous {@link SqliteAuditLog} on a temp file —
 * no async wrapper, so audit reads in assertions see every committed write.
 * Call {@link #close()} after the test to release the DB connection.
 */
final class EvalFixture implements AutoCloseable {

    static final Map<String, Instrument> INSTRUMENTS = Map.of(
            "AAPL", new Instrument("AAPL", "Technology", "US", 150.0),
            "GOOGL", new Instrument("GOOGL", "Technology", "US", 100.0),
            "JPM", new Instrument("JPM", "Finance", "US", 200.0));

    final PositionBook positionBook = new ConcurrentPositionBook();
    final RiskSnapshotCache snapshotCache = new ConcurrentRiskSnapshotCache();
    final GatewayState gatewayState = new GatewayState();
    final AuditLog auditLog;
    final PreTradeGateway gateway;
    final ToolRegistry registry;

    EvalFixture(Path dbFile) {
        this.auditLog = new SqliteAuditLog(dbFile);
        GatewayLimits limits = new GatewayLimits(
                10_000L,
                1_000_000.0,
                1_000_000.0,
                1.0, 1.0,
                0.10, 100_000L,
                Duration.ofMinutes(5));
        this.gateway = new PreTradeGateway(
                snapshotCache, INSTRUMENTS, limits, gatewayState,
                Clock.systemUTC(),
                com.risksentinel.core.ops.LatencyRecorder.noop("eval-gateway"),
                new com.risksentinel.core.ops.NoopMetricsRegistry(),
                auditLog);
        this.registry = new ToolRegistry(List.of(
                new GetSnapshotTool(snapshotCache),
                new ListPositionsTool(positionBook),
                new GetInstrumentTool(INSTRUMENTS),
                new ListRecentDecisionsTool(auditLog),
                new SubmitProposalTool(gateway, Clock.systemUTC()),
                new EngageKillSwitchTool(gatewayState),
                new DisengageKillSwitchTool(gatewayState)));
    }

    @Override
    public void close() {
        auditLog.close();
        gatewayState.shutdown();
    }
}
