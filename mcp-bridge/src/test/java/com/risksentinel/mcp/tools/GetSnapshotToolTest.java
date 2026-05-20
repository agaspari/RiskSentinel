package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.mcp.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GetSnapshotToolTest {

    @Test
    void shouldReturnNullSnapshot_whenPortfolioHasNoSnapshot() {
        GetSnapshotTool tool = new GetSnapshotTool(new ConcurrentRiskSnapshotCache());

        ToolResult r = tool.invoke(BridgeFixtures.parse("{\"portfolioId\":\"port-empty\"}"));

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("\"snapshot\":null");
    }

    @Test
    void shouldReturnSnapshotJson_whenPresent() {
        BridgeFixtures.SystemUnderTest sut = BridgeFixtures.withFills("port-1",
                BridgeFixtures.trade(1L, "port-1", "AAPL", Side.BUY, 100, 150.0),
                BridgeFixtures.trade(2L, "port-1", "JPM", Side.BUY, 50, 200.0));

        GetSnapshotTool tool = new GetSnapshotTool(sut.snapshotCache());

        ToolResult r = tool.invoke(BridgeFixtures.parse("{\"portfolioId\":\"port-1\"}"));

        assertThat(r.isError()).isFalse();
        JsonNode body = BridgeFixtures.parse(r.content());
        assertThat(body.path("portfolioId").asText()).isEqualTo("port-1");
        assertThat(body.path("netExposure").asDouble()).isEqualTo(15_000.0 + 10_000.0);
        assertThat(body.path("positions").isObject()).isTrue();
        assertThat(body.path("positions").has("AAPL")).isTrue();
        assertThat(body.path("computedAt").isTextual()).isTrue();
    }

    @Test
    void shouldDeclareInputSchemaWithPortfolioId() {
        GetSnapshotTool tool = new GetSnapshotTool(new ConcurrentRiskSnapshotCache());
        Object required = tool.inputSchema().get("required");
        assertThat(required.toString()).contains("portfolioId");
    }
}
