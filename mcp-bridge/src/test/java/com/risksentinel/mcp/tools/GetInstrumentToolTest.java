package com.risksentinel.mcp.tools;

import com.risksentinel.mcp.InvocationContext;
import com.risksentinel.mcp.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GetInstrumentToolTest {

    @Test
    void shouldReturnInstrumentJson_whenSymbolKnown() {
        GetInstrumentTool tool = new GetInstrumentTool(BridgeFixtures.REGISTRY);

        ToolResult r = tool.invoke(BridgeFixtures.parse("{\"symbol\":\"AAPL\"}"), InvocationContext.forSystem());

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("AAPL").contains("Technology").contains("150.0");
    }

    @Test
    void shouldReturnNotFound_whenSymbolUnknown() {
        GetInstrumentTool tool = new GetInstrumentTool(BridgeFixtures.REGISTRY);

        ToolResult r = tool.invoke(BridgeFixtures.parse("{\"symbol\":\"ZZZZ\"}"), InvocationContext.forSystem());

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("not_found").contains("ZZZZ");
    }
}
