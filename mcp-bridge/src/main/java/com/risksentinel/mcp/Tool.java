package com.risksentinel.mcp;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Transport-agnostic tool contract. Implementations are stateless except for
 * the {@code core/} dependencies they hold (snapshot cache, gateway, audit log).
 *
 * <p>The MCP wire layer (Task 7.3) is the only thing that knows about the SDK;
 * everything in this module deals in {@code Tool} + {@link ToolResult}, so the
 * exact transport can change without touching tool code.
 */
public interface Tool {

    /** Stable identifier surfaced to the agent (e.g. {@code "get_snapshot"}). */
    String name();

    /** Short human-readable description. Shown verbatim to the LLM. */
    String description();

    /**
     * JSON Schema (as a nested Map) describing the expected input. Used by
     * {@link ToolRegistry} for shallow validation and by the MCP transport
     * to advertise the tool to clients.
     */
    Map<String, Object> inputSchema();

    /** Pure invocation. Inputs have already passed shallow schema validation. */
    ToolResult invoke(JsonNode input);
}
