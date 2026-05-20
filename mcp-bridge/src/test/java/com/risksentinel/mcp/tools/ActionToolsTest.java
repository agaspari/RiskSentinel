package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.mcp.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActionToolsTest {

    private GatewayState state;
    private PreTradeGateway gateway;
    private SubmitProposalTool submitTool;
    private EngageKillSwitchTool engageTool;
    private DisengageKillSwitchTool disengageTool;

    @BeforeEach
    void setUp() {
        state = new GatewayState();
        GatewayLimits limits = new GatewayLimits(
                10_000L, 1_000_000.0, 1_000_000.0,
                1.0, 1.0, 0.10, 100_000L, Duration.ofMinutes(5));
        gateway = new PreTradeGateway(
                new ConcurrentRiskSnapshotCache(),
                BridgeFixtures.REGISTRY,
                limits,
                state);
        Clock clock = Clock.systemUTC();
        submitTool = new SubmitProposalTool(gateway, clock);
        engageTool = new EngageKillSwitchTool(state);
        disengageTool = new DisengageKillSwitchTool(state);
    }

    @AfterEach
    void tearDown() {
        state.shutdown();
    }

    private JsonNode validProposal() {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("proposalId", UUID.randomUUID().toString());
        fields.put("portfolioId", "port-1");
        fields.put("symbol", "AAPL");
        fields.put("side", "BUY");
        fields.put("quantity", 100);
        fields.put("limitPrice", 150.0);
        fields.put("snapshotId", "snap-x");
        fields.put("rationale", "test");
        fields.put("confidence", 0.9);
        return BridgeFixtures.parseInput(fields);
    }

    @Test
    void shouldAcceptValidProposal() {
        ToolResult r = submitTool.invoke(validProposal());

        assertThat(r.isError()).isFalse();
        JsonNode body = BridgeFixtures.parse(r.content());
        assertThat(body.path("decisionType").asText()).isEqualTo("ACCEPT");
        assertThat(body.path("proposalId").isTextual()).isTrue();
        assertThat(body.path("snapshotId").isTextual()).isTrue();
    }

    @Test
    void shouldRejectFatFingerQuantity() {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("proposalId", UUID.randomUUID().toString());
        fields.put("portfolioId", "port-1");
        fields.put("symbol", "AAPL");
        fields.put("side", "BUY");
        fields.put("quantity", 200_000); // above fatFingerMaxQty
        fields.put("limitPrice", 150.0);
        fields.put("snapshotId", "snap-x");
        JsonNode input = BridgeFixtures.parseInput(fields);

        ToolResult r = submitTool.invoke(input);

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("FAT_FINGER_QUANTITY");
    }

    @Test
    void shouldRejectInvalidSide() {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("proposalId", UUID.randomUUID().toString());
        fields.put("portfolioId", "port-1");
        fields.put("symbol", "AAPL");
        fields.put("side", "HOLD"); // invalid
        fields.put("quantity", 100);
        fields.put("limitPrice", 150.0);
        fields.put("snapshotId", "snap-x");

        ToolResult r = submitTool.invoke(BridgeFixtures.parseInput(fields));

        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Invalid");
    }

    @Test
    void engageKillSwitch_shouldFlipState() {
        ToolResult r = engageTool.invoke(BridgeFixtures.parse("{}"));

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("\"engaged\":true");
        assertThat(state.isKillSwitchEngaged()).isTrue();
    }

    @Test
    void disengageKillSwitch_shouldFlipState() {
        state.engageKillSwitch();

        ToolResult r = disengageTool.invoke(BridgeFixtures.parse("{}"));

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("\"engaged\":false");
        assertThat(state.isKillSwitchEngaged()).isFalse();
    }

    /**
     * Trust-boundary regression: there is no MCP path that lets the agent skip
     * the gateway. Engaging the kill switch via the tool must cause subsequent
     * submit_proposal calls — also via tool — to reject with KILL_SWITCH_ENGAGED.
     */
    @Test
    void shouldRejectAllProposals_whenKillSwitchEngagedViaTool() {
        engageTool.invoke(BridgeFixtures.parse("{}"));

        ToolResult r = submitTool.invoke(validProposal());

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("KILL_SWITCH_ENGAGED");
    }

    /**
     * Regression test ensuring no admin-bypass field is silently honored.
     * The schema does not declare it; even if the client sends it, it must
     * be ignored and the gateway still must reject because of the kill switch.
     */
    @Test
    void shouldNotBypassGateway_evenWithUnknownAdminLikeField() {
        engageTool.invoke(BridgeFixtures.parse("{}"));

        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("proposalId", UUID.randomUUID().toString());
        fields.put("portfolioId", "port-1");
        fields.put("symbol", "AAPL");
        fields.put("side", "BUY");
        fields.put("quantity", 100);
        fields.put("limitPrice", 150.0);
        fields.put("snapshotId", "snap-x");
        fields.put("admin", true);
        fields.put("force", true);
        fields.put("skipChecks", true);

        ToolResult r = submitTool.invoke(BridgeFixtures.parseInput(fields));

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("KILL_SWITCH_ENGAGED");
    }
}
