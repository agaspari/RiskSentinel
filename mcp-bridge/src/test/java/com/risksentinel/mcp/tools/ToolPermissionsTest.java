package com.risksentinel.mcp.tools;

import com.risksentinel.core.audit.AuditLog;
import com.risksentinel.core.audit.DecisionRecord;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.positions.ConcurrentPositionBook;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.mcp.ToolPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the declared {@link ToolPermission} for every existing tool. A
 * change here is a deliberate policy change, not a refactor — review carefully.
 */
class ToolPermissionsTest {

    @Test
    void getSnapshot_isReadOnly() {
        assertThat(new GetSnapshotTool(new ConcurrentRiskSnapshotCache()).permission())
                .isEqualTo(ToolPermission.READ_ONLY);
    }

    @Test
    void listPositions_isReadOnly() {
        assertThat(new ListPositionsTool(new ConcurrentPositionBook()).permission())
                .isEqualTo(ToolPermission.READ_ONLY);
    }

    @Test
    void getInstrument_isReadOnly() {
        assertThat(new GetInstrumentTool(Map.of(
                "AAPL", new Instrument("AAPL", "tech", "US", 150.0))).permission())
                .isEqualTo(ToolPermission.READ_ONLY);
    }

    @Test
    void listRecentDecisions_isReadOnly() {
        assertThat(new ListRecentDecisionsTool(new NoopAuditLog()).permission())
                .isEqualTo(ToolPermission.READ_ONLY);
    }

    @Test
    void submitProposal_isWrite() {
        GatewayLimits limits = new GatewayLimits(
                10_000L, 1_000_000.0, 1_000_000.0,
                1.0, 1.0, 0.10, 100_000L, Duration.ofMinutes(5));
        PreTradeGateway gateway = new PreTradeGateway(
                new ConcurrentRiskSnapshotCache(), Map.of(), limits, new GatewayState());

        assertThat(new SubmitProposalTool(gateway, Clock.systemUTC()).permission())
                .isEqualTo(ToolPermission.WRITE);
    }

    @Test
    void engageKillSwitch_isAdmin() {
        assertThat(new EngageKillSwitchTool(new GatewayState()).permission())
                .isEqualTo(ToolPermission.ADMIN);
    }

    @Test
    void disengageKillSwitch_isAdmin() {
        assertThat(new DisengageKillSwitchTool(new GatewayState()).permission())
                .isEqualTo(ToolPermission.ADMIN);
    }

    private static final class NoopAuditLog implements AuditLog {
        @Override public void record(DecisionRecord record) {}
        @Override public Optional<DecisionRecord> findByProposalId(String proposalId) {
            return Optional.empty();
        }
        @Override public List<DecisionRecord> findByPortfolio(String portfolioId, int limit) {
            return List.of();
        }
        @Override public long count() { return 0; }
        @Override public void close() {}
    }
}
