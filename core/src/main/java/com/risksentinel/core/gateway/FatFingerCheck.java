package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.TradeProposal;

import java.util.ArrayList;
import java.util.List;

/**
 * Catches obviously wrong proposals before they consume real state: unknown
 * symbols, absurd quantities, and limit prices that deviate too far from the
 * last known market price. Can emit multiple reasons (e.g. both bad qty and
 * bad price in one go).
 */
final class FatFingerCheck implements RiskCheck {

    static final String NAME = "FatFingerCheck";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RejectReason> check(TradeProposal proposal, GatewayContext ctx) {
        List<RejectReason> reasons = new ArrayList<>(2);

        if (proposal.quantity() > ctx.limits().fatFingerMaxQty()) {
            reasons.add(new RejectReason(
                    NAME,
                    RejectCode.FAT_FINGER_QUANTITY,
                    "Quantity " + proposal.quantity()
                            + " exceeds fat-finger ceiling " + ctx.limits().fatFingerMaxQty()));
        }

        Instrument instrument = ctx.instrument();
        if (instrument == null) {
            reasons.add(new RejectReason(
                    NAME,
                    RejectCode.UNKNOWN_SYMBOL,
                    "Symbol " + proposal.symbol() + " is not in the instrument registry"));
            return reasons;
        }

        double market = instrument.price();
        double deviation = Math.abs(proposal.limitPrice() - market) / market;
        if (deviation > ctx.limits().fatFingerPriceDevPct()) {
            reasons.add(new RejectReason(
                    NAME,
                    RejectCode.FAT_FINGER_PRICE_DEVIATION,
                    String.format(
                            "Limit price %.4f deviates %.2f%% from market %.4f (cap %.2f%%)",
                            proposal.limitPrice(),
                            deviation * 100.0,
                            market,
                            ctx.limits().fatFingerPriceDevPct() * 100.0)));
        }

        return reasons;
    }
}
