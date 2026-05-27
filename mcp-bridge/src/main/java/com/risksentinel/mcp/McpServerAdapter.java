package com.risksentinel.mcp;

import com.risksentinel.core.audit.Caller;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one place in {@code mcp-bridge} that knows about the MCP SDK. Translates
 * a {@link ToolRegistry} into MCP {@code SyncToolSpecification}s and builds an
 * {@link McpSyncServer} bound to stdio.
 *
 * <p>The adapter is intentionally a thin layer — if the SDK 1.x API churns,
 * this file is the only one that needs to change. Tools and the registry stay
 * transport-agnostic.
 */
public final class McpServerAdapter {

    private final ToolRegistry registry;
    private final Caller caller;
    private final McpJsonMapper mcpJsonMapper;
    private final ObjectMapper jsonMapper;

    public McpServerAdapter(ToolRegistry registry, Caller caller) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.mcpJsonMapper = new JacksonMcpJsonMapperSupplier().get();
        this.jsonMapper = Json.MAPPER;
    }

    /**
     * Build and return the MCP server. Caller is responsible for keeping the
     * JVM alive — stdio transport reads from {@code System.in} on a worker
     * thread; {@code main} just needs to park.
     */
    public McpSyncServer build() {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(mcpJsonMapper);

        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (Tool tool : registry.list()) {
            specs.add(buildSpec(tool));
        }

        return McpServer.sync(transport)
                .serverInfo("risksentinel", "0.1.0")
                .tools(specs)
                .build();
    }

    private McpServerFeatures.SyncToolSpecification buildSpec(Tool tool) {
        McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(toJsonSchema(tool.inputSchema()))
                .build();

        BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange,
                McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
                (exchange, request) -> dispatch(tool.name(), request);

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(mcpTool)
                .callHandler(handler)
                .build();
    }

    private McpSchema.CallToolResult dispatch(String toolName, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        JsonNode input = jsonMapper.valueToTree(args == null ? Map.of() : args);

        ToolResult result = registry.invoke(toolName, input, caller);

        return McpSchema.CallToolResult.builder()
                .addTextContent(result.content())
                .isError(result.isError())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static McpSchema.JsonSchema toJsonSchema(Map<String, Object> schemaMap) {
        Object typeObj = schemaMap.get("type");
        String type = (typeObj instanceof String s) ? s : "object";

        Object propsObj = schemaMap.get("properties");
        Map<String, Object> properties = (propsObj instanceof Map<?, ?>)
                ? (Map<String, Object>) propsObj
                : Map.of();

        Object reqObj = schemaMap.get("required");
        List<String> required = (reqObj instanceof List<?> l)
                ? l.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();

        return new McpSchema.JsonSchema(type, properties, required, null, null, null);
    }
}
