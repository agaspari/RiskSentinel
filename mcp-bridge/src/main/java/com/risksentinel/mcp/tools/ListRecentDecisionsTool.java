package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.audit.AuditLog;
import com.risksentinel.core.audit.DecisionRecord;
import com.risksentinel.mcp.Json;
import com.risksentinel.mcp.Tool;
import com.risksentinel.mcp.ToolResult;
import com.risksentinel.mcp.ToolSchemas;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Most-recent-first window of decisions for a portfolio, from the audit log. */
public final class ListRecentDecisionsTool implements Tool {

    private static final int MAX_LIMIT = 200;

    private final AuditLog auditLog;

    public ListRecentDecisionsTool(AuditLog auditLog) {
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    @Override public String name() { return "list_recent_decisions"; }

    @Override public String description() {
        return "Return the most recent gateway decisions for a portfolio (newest first). "
                + "Reads through the synchronous SQLite audit log; limit is capped at " + MAX_LIMIT + ".";
    }

    @Override public Map<String, Object> inputSchema() {
        return ToolSchemas.object(
                List.of("portfolioId", "limit"),
                Map.of(
                        "portfolioId", ToolSchemas.field("string", "Portfolio identifier"),
                        "limit", ToolSchemas.field("integer", "Maximum rows to return (1-" + MAX_LIMIT + ")")));
    }

    @Override public ToolResult invoke(JsonNode input) {
        String portfolioId = input.path("portfolioId").asText();
        int limit = input.path("limit").asInt();
        if (limit <= 0) {
            return ToolResult.error("limit must be positive");
        }
        int bounded = Math.min(limit, MAX_LIMIT);
        List<DecisionRecord> recent = auditLog.findByPortfolio(portfolioId, bounded);
        return ToolResult.ok(Json.writeOrError(recent));
    }
}
