package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.mcp.InvocationContext;
import com.risksentinel.mcp.Tool;
import com.risksentinel.mcp.ToolPermission;
import com.risksentinel.mcp.ToolResult;
import com.risksentinel.mcp.ToolSchemas;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Release the gateway kill switch. Submissions resume normal evaluation. */
public final class DisengageKillSwitchTool implements Tool {

    private final GatewayState state;

    public DisengageKillSwitchTool(GatewayState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override public ToolPermission permission() { return ToolPermission.ADMIN; }

    @Override public String name() { return "disengage_kill_switch"; }

    @Override public String description() {
        return "Release the global kill switch. Subsequent submit_proposal calls "
                + "resume normal evaluation through the gateway's check chain.";
    }

    @Override public Map<String, Object> inputSchema() {
        return ToolSchemas.object(List.of(), Map.of());
    }

    @Override public ToolResult invoke(JsonNode input, InvocationContext context) {
        state.disengageKillSwitch();
        return ToolResult.ok("{\"engaged\":false}");
    }
}
