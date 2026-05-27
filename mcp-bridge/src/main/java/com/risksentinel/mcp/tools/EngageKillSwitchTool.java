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

/** Trip the gateway kill switch. Subsequent proposals reject with KILL_SWITCH_ENGAGED. */
public final class EngageKillSwitchTool implements Tool {

    private final GatewayState state;

    public EngageKillSwitchTool(GatewayState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override public ToolPermission permission() { return ToolPermission.ADMIN; }

    @Override public String name() { return "engage_kill_switch"; }

    @Override public String description() {
        return "Engage the global kill switch. Every subsequent submit_proposal call "
                + "will return Reject with code KILL_SWITCH_ENGAGED until the switch is disengaged.";
    }

    @Override public Map<String, Object> inputSchema() {
        return ToolSchemas.object(List.of(), Map.of());
    }

    @Override public ToolResult invoke(JsonNode input, InvocationContext context) {
        state.engageKillSwitch();
        return ToolResult.ok("{\"engaged\":true}");
    }
}
