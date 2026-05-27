package com.risksentinel.mcp;

import com.risksentinel.core.audit.Caller;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.util.stream.Stream;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerAdapterTest {

    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub " + name; }
        @Override public Map<String, Object> inputSchema() {
            return ToolSchemas.object(List.of(), Map.of());
        }
        @Override public ToolResult invoke(JsonNode input, InvocationContext context) {
            return ToolResult.ok("{\"ok\":\"" + name + "\"}");
        }
    }

    @Test
    void shouldBuildSyncServer_fromRegistry() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new StubTool("alpha"),
                new StubTool("beta")));

        McpSyncServer server = new McpServerAdapter(registry, Caller.system()).build();

        assertThat(server).isNotNull();
        try {
            assertThat(server.getServerInfo()).isNotNull();
            assertThat(server.getServerInfo().name()).isEqualTo("risksentinel");
        } finally {
            server.closeGracefully();
        }
    }

    @Test
    void shouldBuildEmptyServer_whenRegistryHasNoTools() {
        ToolRegistry registry = new ToolRegistry(List.of());

        McpSyncServer server = new McpServerAdapter(registry, Caller.system()).build();

        assertThat(server).isNotNull();
        server.closeGracefully();
    }

    static Stream<Caller> callers() {
        return Stream.of(Caller.agent("a"), Caller.operator("alice"), Caller.system());
    }

    @ParameterizedTest
    @MethodSource("callers")
    void shouldBuildServer_forEachCallerKind(Caller caller) {
        ToolRegistry registry = new ToolRegistry(List.of(
                new StubTool("alpha"),
                new StubTool("beta")));

        McpSyncServer server = new McpServerAdapter(registry, caller).build();

        try {
            // Tool catalogue is the registry's, regardless of caller kind —
            // caller affects invocation, not advertisement.
            assertThat(server).isNotNull();
            assertThat(server.getServerInfo()).isNotNull();
        } finally {
            server.closeGracefully();
        }
    }
}
