package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.mcp.Json;
import com.risksentinel.mcp.Tool;
import com.risksentinel.mcp.ToolResult;
import com.risksentinel.mcp.ToolSchemas;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Returns instrument metadata for a single symbol. */
public final class GetInstrumentTool implements Tool {

    private final Map<String, Instrument> registry;

    public GetInstrumentTool(Map<String, Instrument> registry) {
        this.registry = Map.copyOf(Objects.requireNonNull(registry, "registry"));
    }

    @Override public String name() { return "get_instrument"; }

    @Override public String description() {
        return "Return metadata (sector, region, price) for a single symbol, or a not_found shape if absent.";
    }

    @Override public Map<String, Object> inputSchema() {
        return ToolSchemas.object(
                List.of("symbol"),
                Map.of("symbol", ToolSchemas.field("string", "Instrument symbol, e.g. AAPL")));
    }

    @Override public ToolResult invoke(JsonNode input) {
        String symbol = input.path("symbol").asText();
        Instrument instrument = registry.get(symbol);
        if (instrument == null) {
            return ToolResult.ok("{\"symbol\":\"" + symbol + "\",\"status\":\"not_found\"}");
        }
        return ToolResult.ok(Json.writeOrError(instrument));
    }
}
