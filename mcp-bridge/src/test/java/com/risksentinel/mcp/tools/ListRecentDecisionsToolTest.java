package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.audit.AuditLog;
import com.risksentinel.core.audit.DecisionRecord;
import com.risksentinel.core.audit.DecisionType;
import com.risksentinel.core.audit.SqliteAuditLog;
import com.risksentinel.core.domain.Side;
import com.risksentinel.mcp.InvocationContext;
import com.risksentinel.mcp.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ListRecentDecisionsToolTest {

    private DecisionRecord accept(String pid, String portfolio, Instant at) {
        return new DecisionRecord(
                pid, portfolio, "AAPL", Side.BUY,
                100L, 150.0, "snap-x",
                DecisionType.ACCEPT, null, "[]", at);
    }

    @Test
    void shouldReturnEmptyArray_whenAuditEmpty(@TempDir Path dir) {
        try (AuditLog log = new SqliteAuditLog(dir.resolve("audit.db"))) {
            ListRecentDecisionsTool tool = new ListRecentDecisionsTool(log);

            ToolResult r = tool.invoke(BridgeFixtures.parse(
                    "{\"portfolioId\":\"port-1\",\"limit\":10}"), InvocationContext.forSystem());

            assertThat(r.isError()).isFalse();
            assertThat(r.content()).isEqualTo("[]");
        }
    }

    @Test
    void shouldReturnRecentDecisions_newestFirst(@TempDir Path dir) {
        try (AuditLog log = new SqliteAuditLog(dir.resolve("audit.db"))) {
            Instant t0 = Instant.parse("2026-05-19T12:00:00Z");
            log.record(accept("p-1", "port-1", t0));
            log.record(accept("p-2", "port-1", t0.plusSeconds(1)));
            log.record(accept("p-3", "port-1", t0.plusSeconds(2)));
            log.record(accept("other", "port-2", t0.plusSeconds(3)));

            ListRecentDecisionsTool tool = new ListRecentDecisionsTool(log);
            ToolResult r = tool.invoke(BridgeFixtures.parse(
                    "{\"portfolioId\":\"port-1\",\"limit\":10}"), InvocationContext.forSystem());

            assertThat(r.isError()).isFalse();
            JsonNode arr = BridgeFixtures.parse(r.content());
            assertThat(arr.size()).isEqualTo(3);
            assertThat(arr.get(0).path("proposalId").asText()).isEqualTo("p-3");
            assertThat(arr.get(2).path("proposalId").asText()).isEqualTo("p-1");
        }
    }

    @Test
    void shouldRejectNonPositiveLimit(@TempDir Path dir) {
        try (AuditLog log = new SqliteAuditLog(dir.resolve("audit.db"))) {
            ListRecentDecisionsTool tool = new ListRecentDecisionsTool(log);
            ToolResult r = tool.invoke(BridgeFixtures.parse(
                    "{\"portfolioId\":\"port-1\",\"limit\":0}"), InvocationContext.forSystem());

            assertThat(r.isError()).isTrue();
            assertThat(r.content()).contains("limit");
        }
    }
}
