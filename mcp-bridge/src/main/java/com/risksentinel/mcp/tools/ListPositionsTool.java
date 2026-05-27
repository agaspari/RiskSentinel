package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.positions.PositionBook;
import com.risksentinel.mcp.InvocationContext;
import com.risksentinel.mcp.Json;
import com.risksentinel.mcp.Tool;
import com.risksentinel.mcp.ToolPermission;
import com.risksentinel.mcp.ToolResult;
import com.risksentinel.mcp.ToolSchemas;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Returns all open positions for a portfolio. */
public final class ListPositionsTool implements Tool {

    private final PositionBook positionBook;

    public ListPositionsTool(PositionBook positionBook) {
        this.positionBook = Objects.requireNonNull(positionBook, "positionBook");
    }

    @Override public ToolPermission permission() { return ToolPermission.READ_ONLY; }

    @Override public String name() { return "list_positions"; }

    @Override public String description() {
        return "Return every open position for the given portfolio. Empty array if the portfolio is flat.";
    }

    @Override public Map<String, Object> inputSchema() {
        return ToolSchemas.object(
                List.of("portfolioId"),
                Map.of("portfolioId", ToolSchemas.field("string", "Portfolio identifier")));
    }

    @Override public ToolResult invoke(JsonNode input, InvocationContext context) {
        String portfolioId = input.path("portfolioId").asText();
        Collection<Position> positions = positionBook.getPositions(portfolioId);
        return ToolResult.ok(Json.writeOrError(positions));
    }
}
