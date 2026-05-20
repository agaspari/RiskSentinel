package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.GatewayDecision;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.mcp.Json;
import com.risksentinel.mcp.Tool;
import com.risksentinel.mcp.ToolResult;
import com.risksentinel.mcp.ToolSchemas;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Submit a {@link TradeProposal} through {@link PreTradeGateway} and return the
 * resulting {@link GatewayDecision}.
 *
 * <p><strong>Trust boundary:</strong> every invocation goes through the gateway.
 * There is no admin flag, no fast path, and no input that bypasses checks.
 * Malformed or oversized proposals are rejected by the gateway's existing
 * {@code RiskCheck} chain — not by this tool.
 */
public final class SubmitProposalTool implements Tool {

    private final PreTradeGateway gateway;
    private final Clock clock;

    public SubmitProposalTool(PreTradeGateway gateway, Clock clock) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String name() { return "submit_proposal"; }

    @Override public String description() {
        return "Submit a trade proposal through the pre-trade risk gateway. "
                + "Returns Accept or Reject (with reason codes). Every call is audited "
                + "and counted in metrics; the gateway is the trust boundary.";
    }

    @Override public Map<String, Object> inputSchema() {
        LinkedHashMap<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("proposalId", ToolSchemas.field("string", "Unique proposal id (idempotency key)"));
        props.put("portfolioId", ToolSchemas.field("string", "Target portfolio"));
        props.put("symbol", ToolSchemas.field("string", "Instrument symbol, e.g. AAPL"));
        props.put("side", ToolSchemas.field("string", "BUY or SELL"));
        props.put("quantity", ToolSchemas.field("integer", "Quantity > 0"));
        props.put("limitPrice", ToolSchemas.field("number", "Limit price > 0"));
        props.put("snapshotId", ToolSchemas.field("string", "Snapshot the proposal was reasoned against"));
        props.put("rationale", ToolSchemas.field("string", "Free-text rationale"));
        props.put("confidence", ToolSchemas.field("number", "Confidence in [0,1]"));
        return ToolSchemas.object(
                List.of("proposalId", "portfolioId", "symbol", "side",
                        "quantity", "limitPrice", "snapshotId"),
                props);
    }

    @Override public ToolResult invoke(JsonNode input) {
        TradeProposal proposal;
        try {
            String sideStr = input.path("side").asText();
            Side side = Side.valueOf(sideStr);
            double confidence = input.has("confidence") ? input.path("confidence").asDouble() : 0.5;
            String rationale = input.has("rationale") ? input.path("rationale").asText() : "";
            proposal = new TradeProposal(
                    input.path("proposalId").asText(),
                    input.path("portfolioId").asText(),
                    input.path("symbol").asText(),
                    side,
                    input.path("quantity").asLong(),
                    input.path("limitPrice").asDouble(),
                    input.path("limitPrice").asDouble(),
                    rationale,
                    confidence,
                    input.path("snapshotId").asText(),
                    Instant.now(clock));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid proposal: " + e.getMessage());
        }

        GatewayDecision decision = gateway.decide(proposal);
        return ToolResult.ok(Json.writeOrError(envelope(decision)));
    }

    private static Map<String, Object> envelope(GatewayDecision d) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (d instanceof GatewayDecision.Accept accept) {
            out.put("decisionType", "ACCEPT");
            out.put("proposalId", accept.proposalId());
            out.put("snapshotId", accept.snapshotId());
            out.put("decidedAt", accept.decidedAt());
        } else if (d instanceof GatewayDecision.Reject reject) {
            out.put("decisionType", "REJECT");
            out.put("proposalId", reject.proposalId());
            out.put("reasons", reject.reasons());
            out.put("decidedAt", reject.decidedAt());
        }
        return out;
    }
}
