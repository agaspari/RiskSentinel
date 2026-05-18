package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;

import java.util.ArrayList;
import java.util.List;

/**
 * Reject if the post-trade portfolio gross or net exposure would breach
 * {@link GatewayLimits#maxGrossExposure} or {@link GatewayLimits#maxNetExposure}.
 *
 * <p>Both can fail simultaneously and are reported as separate {@link RejectReason}s.
 * If the proposal's symbol is unknown the check is a no-op — {@code FatFingerCheck}
 * is responsible for emitting {@code UNKNOWN_SYMBOL}.
 */
final class NotionalExposureCheck implements RiskCheck {

    static final String NAME = "NotionalExposureCheck";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RejectReason> check(TradeProposal proposal, GatewayContext ctx) {
        Instrument instrument = ctx.instrument();
        if (instrument == null) {
            return List.of();
        }

        RiskSnapshot snapshot = ctx.snapshot();
        double currentGross = snapshot != null ? snapshot.grossExposure() : 0.0;
        double currentNet = snapshot != null ? snapshot.netExposure() : 0.0;

        long currentSymbolQty = 0L;
        if (snapshot != null) {
            Position p = snapshot.positions().get(proposal.symbol());
            if (p != null) {
                currentSymbolQty = p.quantity();
            }
        }

        long qtyDelta = proposal.side() == Side.BUY ? proposal.quantity() : -proposal.quantity();
        long postSymbolQty = currentSymbolQty + qtyDelta;

        double price = instrument.price();
        double currentSymbolGross = Math.abs(currentSymbolQty * price);
        double postSymbolGross = Math.abs(postSymbolQty * price);

        double postGross = currentGross - currentSymbolGross + postSymbolGross;
        double postNet = currentNet + qtyDelta * price;

        List<RejectReason> reasons = new ArrayList<>(2);

        if (postGross > ctx.limits().maxGrossExposure()) {
            reasons.add(new RejectReason(
                    NAME,
                    RejectCode.GROSS_EXPOSURE_EXCEEDED,
                    String.format(
                            "Post-trade gross exposure %.2f exceeds cap %.2f",
                            postGross, ctx.limits().maxGrossExposure())));
        }

        if (Math.abs(postNet) > ctx.limits().maxNetExposure()) {
            reasons.add(new RejectReason(
                    NAME,
                    RejectCode.NET_EXPOSURE_EXCEEDED,
                    String.format(
                            "Post-trade |net exposure| %.2f exceeds cap %.2f",
                            Math.abs(postNet), ctx.limits().maxNetExposure())));
        }

        return reasons;
    }
}
