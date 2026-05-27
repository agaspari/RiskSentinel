package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.risk.RiskSnapshotCache;
import com.risksentinel.mcp.InvocationContext;
import com.risksentinel.mcp.Json;
import com.risksentinel.mcp.Tool;
import com.risksentinel.mcp.ToolPermission;
import com.risksentinel.mcp.ToolResult;
import com.risksentinel.mcp.ToolSchemas;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Returns the latest {@link RiskSnapshot} for a portfolio. Reads through a
 * lock-free {@code AtomicReference}; the snapshot can be one ingestion update
 * behind the position book.
 */
public final class GetSnapshotTool implements Tool {

    private final RiskSnapshotCache cache;

    public GetSnapshotTool(RiskSnapshotCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public ToolPermission permission() { return ToolPermission.READ_ONLY; }

    @Override public String name() { return "get_snapshot"; }

    @Override public String description() {
        return "Return the latest risk snapshot for a portfolio. May lag the position "
                + "book by one ingestion cycle; returns an empty object if no snapshot "
                + "has been computed yet.";
    }

    @Override public Map<String, Object> inputSchema() {
        return ToolSchemas.object(
                List.of("portfolioId"),
                Map.of("portfolioId", ToolSchemas.field("string", "Portfolio identifier")));
    }

    @Override public ToolResult invoke(JsonNode input, InvocationContext context) {
        String portfolioId = input.path("portfolioId").asText();
        Optional<RiskSnapshot> snap = cache.getSnapshot(portfolioId);
        if (snap.isEmpty()) {
            return ToolResult.ok("{\"portfolioId\":\"" + portfolioId + "\",\"snapshot\":null}");
        }
        return ToolResult.ok(Json.writeOrError(snap.get()));
    }
}
