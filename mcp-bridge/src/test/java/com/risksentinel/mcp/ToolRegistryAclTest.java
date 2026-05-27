package com.risksentinel.mcp;

import com.risksentinel.core.audit.Caller;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryAclTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Tool that records whether {@link #invoke(JsonNode)} was actually called. */
    private static final class SpyTool implements Tool {
        private final String name;
        private final ToolPermission permission;
        private final Map<String, Object> schema;
        private final AtomicBoolean invoked = new AtomicBoolean(false);

        SpyTool(String name, ToolPermission permission, Map<String, Object> schema) {
            this.name = name;
            this.permission = permission;
            this.schema = schema;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "spy " + name; }
        @Override public Map<String, Object> inputSchema() { return schema; }
        @Override public ToolPermission permission() { return permission; }
        @Override public ToolResult invoke(JsonNode input, InvocationContext context) {
            invoked.set(true);
            return ToolResult.ok("{}");
        }
    }

    private JsonNode parse(String json) {
        try { return MAPPER.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void shouldDispatch_whenCallerHasReadOnlyPermission() {
        SpyTool readOnly = new SpyTool("r", ToolPermission.READ_ONLY,
                ToolSchemas.object(List.of(), Map.of()));
        ToolRegistry registry = new ToolRegistry(List.of(readOnly));

        ToolResult result = registry.invoke("r", parse("{}"), Caller.agent("a"));

        assertThat(result.isError()).isFalse();
        assertThat(readOnly.invoked).isTrue();
    }

    @Test
    void shouldDispatch_whenCallerHasWritePermission() {
        SpyTool write = new SpyTool("w", ToolPermission.WRITE,
                ToolSchemas.object(List.of(), Map.of()));
        ToolRegistry registry = new ToolRegistry(List.of(write));

        ToolResult result = registry.invoke("w", parse("{}"), Caller.agent("a"));

        assertThat(result.isError()).isFalse();
        assertThat(write.invoked).isTrue();
    }

    @Test
    void shouldDispatch_whenOperatorCallsAdminTool() {
        SpyTool admin = new SpyTool("kill", ToolPermission.ADMIN,
                ToolSchemas.object(List.of(), Map.of()));
        ToolRegistry registry = new ToolRegistry(List.of(admin));

        ToolResult result = registry.invoke("kill", parse("{}"), Caller.operator("alice"));

        assertThat(result.isError()).isFalse();
        assertThat(admin.invoked).isTrue();
    }

    @Test
    void shouldDenyWithError_whenAgentCallsAdminTool() {
        SpyTool admin = new SpyTool("kill", ToolPermission.ADMIN,
                ToolSchemas.object(List.of(), Map.of()));
        ToolRegistry registry = new ToolRegistry(List.of(admin));

        ToolResult result = registry.invoke("kill", parse("{}"), Caller.agent("a"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Permission denied");
        assertThat(result.content()).contains("kill");
        assertThat(result.content()).contains("ADMIN");
        assertThat(admin.invoked).as("tool handler must not run on deny").isFalse();
    }

    @Test
    void shouldEmitDenialBeforeSchemaValidation() {
        // Tool requires ADMIN but our caller is an agent. Input is missing a
        // required field — but we should *not* see a schema-validation error,
        // because the ACL check fires first. This prevents an unauthorized
        // caller from probing the schema.
        SpyTool admin = new SpyTool("kill", ToolPermission.ADMIN,
                ToolSchemas.object(List.of("portfolioId"),
                        Map.of("portfolioId", ToolSchemas.field("string", "id"))));
        ToolRegistry registry = new ToolRegistry(List.of(admin));

        ToolResult result = registry.invoke("kill", parse("{}"), Caller.agent("a"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Permission denied");
        assertThat(result.content()).doesNotContain("portfolioId");
    }

    @Test
    void shouldRejectNullCaller() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new SpyTool("r", ToolPermission.READ_ONLY,
                        ToolSchemas.object(List.of(), Map.of()))));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> registry.invoke("r", parse("{}"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDenyBeforeReportingUnknownTool_isNotARequirement_unknownStillTakesPrecedence() {
        // Unknown tool name takes precedence over ACL — an unknown tool has
        // no permission level to check against, and the caller learns nothing
        // about the system beyond what they already knew.
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolResult result = registry.invoke("nope", parse("{}"), Caller.agent("a"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown tool");
    }
}
