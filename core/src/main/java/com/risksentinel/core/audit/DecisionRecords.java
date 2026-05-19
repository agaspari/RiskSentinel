package com.risksentinel.core.audit;

import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.GatewayDecision;
import com.risksentinel.core.gateway.RejectReason;

import java.util.List;

/**
 * Factory + JSON serialization helpers for {@link DecisionRecord}. Reasons are
 * serialized as a JSON array of {@code {checkName, code, message}} objects.
 * We avoid pulling in a JSON library for this — the format is small, fixed,
 * and produced/consumed only by audit-log code.
 */
public final class DecisionRecords {

    private DecisionRecords() {}

    public static DecisionRecord fromDecision(GatewayDecision decision, TradeProposal proposal) {
        if (decision instanceof GatewayDecision.Accept accept) {
            return new DecisionRecord(
                    proposal.proposalId(),
                    proposal.portfolioId(),
                    proposal.symbol(),
                    proposal.side(),
                    proposal.quantity(),
                    proposal.limitPrice(),
                    proposal.snapshotId(),
                    DecisionType.ACCEPT,
                    null,
                    "[]",
                    accept.decidedAt());
        }
        if (decision instanceof GatewayDecision.Reject reject) {
            List<RejectReason> reasons = reject.reasons();
            String firstCode = reasons.isEmpty()
                    ? "UNKNOWN"
                    : reasons.get(0).code().name();
            return new DecisionRecord(
                    proposal.proposalId(),
                    proposal.portfolioId(),
                    proposal.symbol(),
                    proposal.side(),
                    proposal.quantity(),
                    proposal.limitPrice(),
                    proposal.snapshotId(),
                    DecisionType.REJECT,
                    firstCode,
                    encodeReasons(reasons),
                    reject.decidedAt());
        }
        throw new IllegalStateException("Unknown GatewayDecision subtype: " + decision.getClass());
    }

    static String encodeReasons(List<RejectReason> reasons) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < reasons.size(); i++) {
            RejectReason r = reasons.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"checkName\":\"").append(escape(r.checkName()))
                    .append("\",\"code\":\"").append(r.code().name())
                    .append("\",\"message\":\"").append(escape(r.message()))
                    .append("\"}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
