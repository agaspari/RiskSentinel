package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.positions.ConcurrentPositionBook;
import com.risksentinel.mcp.InvocationContext;
import com.risksentinel.mcp.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ListPositionsToolTest {

    @Test
    void shouldReturnEmptyArray_whenPortfolioFlat() {
        ListPositionsTool tool = new ListPositionsTool(new ConcurrentPositionBook());

        ToolResult r = tool.invoke(BridgeFixtures.parse("{\"portfolioId\":\"port-empty\"}"), InvocationContext.forSystem());

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).isEqualTo("[]");
    }

    @Test
    void shouldReturnOpenPositions() {
        BridgeFixtures.SystemUnderTest sut = BridgeFixtures.withFills("port-1",
                BridgeFixtures.trade(1L, "port-1", "AAPL", Side.BUY, 100, 150.0),
                BridgeFixtures.trade(2L, "port-1", "AAPL", Side.BUY, 50, 160.0),
                BridgeFixtures.trade(3L, "port-1", "JPM", Side.BUY, 25, 200.0));

        ListPositionsTool tool = new ListPositionsTool(sut.positionBook());

        ToolResult r = tool.invoke(BridgeFixtures.parse("{\"portfolioId\":\"port-1\"}"), InvocationContext.forSystem());

        assertThat(r.isError()).isFalse();
        JsonNode arr = BridgeFixtures.parse(r.content());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(2);
    }
}
