package com.risksentinel.analyst.tools;

import com.risksentinel.core.audit.Caller;
import com.risksentinel.mcp.InvocationContext;
import com.risksentinel.mcp.Tool;
import com.risksentinel.mcp.ToolPermission;
import com.risksentinel.mcp.ToolRegistry;
import com.risksentinel.mcp.ToolResult;
import com.risksentinel.mcp.ToolSchemas;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jToolBridgeTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final class StubTool implements Tool {
        private final String name;
        private final Map<String, Object> schema;
        private final java.util.function.Function<JsonNode, ToolResult> handler;

        StubTool(String name, Map<String, Object> schema,
                 java.util.function.Function<JsonNode, ToolResult> handler) {
            this.name = name;
            this.schema = schema;
            this.handler = handler;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "stub " + name; }
        @Override public Map<String, Object> inputSchema() { return schema; }
        @Override public ToolResult invoke(JsonNode input, InvocationContext context) { return handler.apply(input); }
    }

    private static ToolRegistry registryOf(Tool... tools) {
        return new ToolRegistry(List.of(tools));
    }

    @Test
    void shouldTranslateSchema_forEachRegisteredTool() {
        StubTool a = new StubTool(
                "alpha",
                ToolSchemas.object(
                        List.of("portfolioId"),
                        Map.of("portfolioId", ToolSchemas.field("string", "portfolio id"))),
                in -> ToolResult.ok("{}"));
        StubTool b = new StubTool(
                "beta",
                ToolSchemas.object(
                        List.of("limit"),
                        Map.of("limit", ToolSchemas.field("integer", "row cap"))),
                in -> ToolResult.ok("{}"));

        LangChain4jToolBridge bridge = new LangChain4jToolBridge(registryOf(a, b), Caller.system(), JSON);

        List<ToolSpecification> specs = bridge.specifications();

        assertThat(specs).hasSize(2);
        assertThat(specs).extracting(ToolSpecification::name)
                .containsExactly("alpha", "beta");
        assertThat(specs.get(0).description()).isEqualTo("stub alpha");

        JsonObjectSchema alphaParams = specs.get(0).parameters();
        assertThat(alphaParams.properties()).containsKey("portfolioId");
        assertThat(alphaParams.properties().get("portfolioId"))
                .isInstanceOf(JsonStringSchema.class);
        assertThat(alphaParams.required()).containsExactly("portfolioId");

        JsonObjectSchema betaParams = specs.get(1).parameters();
        assertThat(betaParams.properties().get("limit"))
                .isInstanceOf(JsonIntegerSchema.class);
    }

    @Test
    void shouldHandleEmptySchema_withoutThrowing() {
        StubTool tool = new StubTool(
                "ping",
                ToolSchemas.object(List.of(), Map.of()),
                in -> ToolResult.ok("{}"));

        LangChain4jToolBridge bridge = new LangChain4jToolBridge(registryOf(tool), Caller.system(), JSON);

        List<ToolSpecification> specs = bridge.specifications();

        assertThat(specs).hasSize(1);
        JsonObjectSchema params = specs.get(0).parameters();
        assertThat(params.properties()).isEmpty();
        assertThat(params.required()).isNullOrEmpty();
    }

    @Test
    void shouldExecuteToolCall_andReturnContent() {
        StubTool tool = new StubTool(
                "echo",
                ToolSchemas.object(List.of(), Map.of()),
                in -> ToolResult.ok("{\"got\":\"" + in.toString() + "\"}"));

        LangChain4jToolBridge bridge = new LangChain4jToolBridge(registryOf(tool), Caller.system(), JSON);

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("req-1")
                .name("echo")
                .arguments("{\"value\":42}")
                .build();

        ToolExecutionResultMessage result = bridge.execute(req);

        assertThat(result.id()).isEqualTo("req-1");
        assertThat(result.toolName()).isEqualTo("echo");
        assertThat(result.text()).contains("\"got\"");
        assertThat(result.text()).contains("\"value\":42");
    }

    @Test
    void shouldExecuteToolCall_withEmptyArguments() {
        StubTool tool = new StubTool(
                "ping",
                ToolSchemas.object(List.of(), Map.of()),
                in -> ToolResult.ok("{\"ok\":true,\"empty\":" + in.isEmpty() + "}"));

        LangChain4jToolBridge bridge = new LangChain4jToolBridge(registryOf(tool), Caller.system(), JSON);

        ToolExecutionResultMessage result = bridge.execute(
                ToolExecutionRequest.builder().id("r").name("ping").arguments("").build());

        assertThat(result.text()).contains("\"empty\":true");
    }

    @Test
    void shouldReturnErrorResult_whenTargetToolErrors() {
        StubTool tool = new StubTool(
                "boom",
                ToolSchemas.object(List.of(), Map.of()),
                in -> { throw new RuntimeException("blew up"); });

        LangChain4jToolBridge bridge = new LangChain4jToolBridge(registryOf(tool), Caller.system(), JSON);

        ToolExecutionResultMessage result = bridge.execute(
                ToolExecutionRequest.builder().id("r").name("boom").arguments("{}").build());

        assertThat(result.text()).contains("error");
        assertThat(result.text()).contains("blew up");
    }

    @Test
    void shouldHandleMalformedArgumentsJson() {
        StubTool tool = new StubTool(
                "echo",
                ToolSchemas.object(List.of(), Map.of()),
                in -> ToolResult.ok("{\"ok\":true}"));

        LangChain4jToolBridge bridge = new LangChain4jToolBridge(registryOf(tool), Caller.system(), JSON);

        ToolExecutionResultMessage result = bridge.execute(
                ToolExecutionRequest.builder().id("r").name("echo").arguments("not json").build());

        assertThat(result.text()).contains("error");
        assertThat(result.text()).containsIgnoringCase("malformed");
    }

    @Test
    void shouldReturnErrorResult_forUnknownTool() {
        LangChain4jToolBridge bridge = new LangChain4jToolBridge(registryOf(), Caller.system(), JSON);

        ToolExecutionResultMessage result = bridge.execute(
                ToolExecutionRequest.builder().id("r").name("nope").arguments("{}").build());

        assertThat(result.text()).contains("error");
        assertThat(result.text()).contains("nope");
    }

    @Test
    void shouldPropagateCaller_toRegistry() {
        // ADMIN tool: agent caller is denied, operator caller is allowed.
        // The bridge is the only thing supplying the caller, so divergent
        // outcomes here mean the caller field is actually being threaded
        // through to ToolRegistry.invoke.
        Tool adminTool = new Tool() {
            @Override public String name() { return "admin_op"; }
            @Override public String description() { return "admin only"; }
            @Override public Map<String, Object> inputSchema() {
                return ToolSchemas.object(List.of(), Map.of());
            }
            @Override public ToolPermission permission() { return ToolPermission.ADMIN; }
            @Override public ToolResult invoke(JsonNode input, InvocationContext context) { return ToolResult.ok("{\"ran\":true}"); }
        };
        ToolRegistry registry = new ToolRegistry(List.of(adminTool));
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("r").name("admin_op").arguments("{}").build();

        ToolExecutionResultMessage agentResult =
                new LangChain4jToolBridge(registry, Caller.agent("a"), JSON).execute(req);
        ToolExecutionResultMessage operatorResult =
                new LangChain4jToolBridge(registry, Caller.operator("alice"), JSON).execute(req);

        assertThat(agentResult.text()).contains("Permission denied");
        assertThat(operatorResult.text()).contains("\"ran\":true");
    }

    @Test
    void shouldSurfaceMissingRequiredField_asErrorResult() {
        StubTool tool = new StubTool(
                "needs_id",
                ToolSchemas.object(
                        List.of("portfolioId"),
                        Map.of("portfolioId", ToolSchemas.field("string", "id"))),
                in -> ToolResult.ok("{\"ok\":true}"));

        LangChain4jToolBridge bridge = new LangChain4jToolBridge(registryOf(tool), Caller.system(), JSON);

        ToolExecutionResultMessage result = bridge.execute(
                ToolExecutionRequest.builder().id("r").name("needs_id").arguments("{}").build());

        assertThat(result.text()).contains("portfolioId");
    }
}
