package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;

import java.util.List;

/**
 * Reject if the post-trade per-symbol position quantity would exceed
 * {@link GatewayLimits#maxPositionQty}, in absolute value. Current quantity
 * is read from the snapshot's positions map; a null snapshot or missing
 * symbol is treated as zero current quantity.
 */
final class PositionSizeCheck implements RiskCheck {

    static final String NAME = "PositionSizeCheck";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RejectReason> check(TradeProposal proposal, GatewayContext ctx) {
        long currentQty = currentQuantity(ctx.snapshot(), proposal.symbol());
        long delta = proposal.side() == Side.BUY ? proposal.quantity() : -proposal.quantity();
        long postQty = currentQty + delta;

        long limit = ctx.limits().maxPositionQty();
        if (Math.abs(postQty) > limit) {
            return List.of(new RejectReason(
                    NAME,
                    RejectCode.POSITION_SIZE_EXCEEDED,
                    "Post-trade position " + postQty + " exceeds per-symbol limit " + limit));
        }
        return List.of();
    }

    private static long currentQuantity(RiskSnapshot snapshot, String symbol) {
        if (snapshot == null) {
            return 0L;
        }
        Position p = snapshot.positions().get(symbol);
        return p == null ? 0L : p.quantity();
    }
}
