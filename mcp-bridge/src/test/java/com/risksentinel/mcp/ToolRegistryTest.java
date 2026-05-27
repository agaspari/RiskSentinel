package com.risksentinel.mcp;

import com.risksentinel.core.audit.Caller;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Trivial tool returning the input JSON back. */
    private static final class EchoTool implements Tool {
        private final String name;
        private final Map<String, Object> schema;
        EchoTool(String name, Map<String, Object> schema) {
            this.name = name;
            this.schema = schema;
        }
        @Override public String name() { return name; }
        @Override public String description() { return "echo " + name; }
        @Override public Map<String, Object> inputSchema() { return schema; }
        @Override public ToolResult invoke(JsonNode input, InvocationContext context) {
            return ToolResult.ok(input.toString());
        }
    }

    private JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldListRegisteredToolsInRegistrationOrder() {
        ToolRegistry r = new ToolRegistry(List.of(
                new EchoTool("a", ToolSchemas.object(List.of(), Map.of())),
                new EchoTool("b", ToolSchemas.object(List.of(), Map.of()))));

        assertThat(r.list()).extracting(Tool::name).containsExactly("a", "b");
        assertThat(r.contains("a")).isTrue();
        assertThat(r.contains("z")).isFalse();
    }

    @Test
    void shouldRejectDuplicateToolNames() {
        Tool a1 = new EchoTool("a", ToolSchemas.object(List.of(), Map.of()));
        Tool a2 = new EchoTool("a", ToolSchemas.object(List.of(), Map.of()));
        assertThatThrownBy(() -> new ToolRegistry(List.of(a1, a2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
    }

    @Test
    void shouldInvokeRegisteredTool() {
        ToolRegistry r = new ToolRegistry(List.of(
                new EchoTool("echo", ToolSchemas.object(List.of(), Map.of()))));

        ToolResult result = r.invoke("echo", parse("{\"x\":1}"), Caller.system());

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("\"x\":1");
    }

    @Test
    void shouldErrorOnUnknownToolName() {
        ToolRegistry r = new ToolRegistry(List.of(
                new EchoTool("echo", ToolSchemas.object(List.of(), Map.of()))));

        ToolResult result = r.invoke("nope", parse("{}"), Caller.system());

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown tool");
        assertThat(result.content()).contains("nope");
    }

    @Test
    void shouldErrorOnMissingRequiredField() {
        Map<String, Object> schema = ToolSchemas.object(
                List.of("portfolioId"),
                Map.of("portfolioId", ToolSchemas.field("string", "id")));
        ToolRegistry r = new ToolRegistry(List.of(new EchoTool("t", schema)));

        ToolResult result = r.invoke("t", parse("{}"), Caller.system());

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("portfolioId");
    }

    @Test
    void shouldErrorOnTypeMismatch() {
        Map<String, Object> schema = ToolSchemas.object(
                List.of("count"),
                Map.of("count", ToolSchemas.field("integer", "n")));
        ToolRegistry r = new ToolRegistry(List.of(new EchoTool("t", schema)));

        ToolResult result = r.invoke("t", parse("{\"count\":\"not-an-integer\"}"), Caller.system());

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("integer");
    }

    @Test
    void shouldAcceptValidInput_passingSchemaValidation() {
        Map<String, Object> schema = ToolSchemas.object(
                List.of("portfolioId"),
                Map.of("portfolioId", ToolSchemas.field("string", "id")));
        ToolRegistry r = new ToolRegistry(List.of(new EchoTool("t", schema)));

        ToolResult result = r.invoke("t", parse("{\"portfolioId\":\"port-1\"}"), Caller.system());

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("port-1");
    }

    @Test
    void shouldCatchHandlerExceptions_andReturnErrorResult() {
        Tool throwing = new Tool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return ""; }
            @Override public Map<String, Object> inputSchema() {
                return ToolSchemas.object(List.of(), Map.of());
            }
            @Override public ToolResult invoke(JsonNode input, InvocationContext context) {
                throw new IllegalStateException("internal failure");
            }
        };
        ToolRegistry r = new ToolRegistry(List.of(throwing));

        ToolResult result = r.invoke("boom", parse("{}"), Caller.system());

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("boom");
        assertThat(result.content()).contains("internal failure");
    }

    @Test
    void shouldHandleNullInput_byTreatingAsEmptyObject() {
        Map<String, Object> schema = ToolSchemas.object(List.of(), Map.of());
        ToolRegistry r = new ToolRegistry(List.of(new EchoTool("t", schema)));

        ToolResult result = r.invoke("t", null, Caller.system());

        assertThat(result.isError()).isFalse();
    }

    @Test
    void toolResultErrorShouldEscapeQuotesInMessage() {
        ToolResult r = ToolResult.error("bad input: \"foo\"");
        assertThat(r.content()).contains("\\\"foo\\\"");
    }
}
