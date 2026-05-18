package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reject if the post-trade portfolio would breach the HHI cap or the
 * per-sector weight cap. Computes the post-trade per-symbol notionals and
 * per-sector exposures by rolling the proposal forward against the snapshot.
 *
 * <p>If the snapshot is null (first trade for the portfolio) or the symbol
 * is unknown, the check is a no-op — {@code FatFingerCheck} handles unknown
 * symbols. A first-trade portfolio has trivially one position; whether to
 * apply HHI then is a policy decision deferred to ops.
 */
final class ConcentrationCheck implements RiskCheck {

    static final String NAME = "ConcentrationCheck";

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
        if (snapshot == null) {
            return List.of();
        }

        // Compute post-trade per-symbol notionals.
        Map<String, Double> postNotionals = new HashMap<>();
        long currentSymbolQty = 0L;
        for (Map.Entry<String, Position> e : snapshot.positions().entrySet()) {
            Position p = e.getValue();
            if (p.symbol().equals(proposal.symbol())) {
                currentSymbolQty = p.quantity();
                continue; // will overwrite below
            }
            // Use marketValue as the snapshot's per-symbol notional contribution.
            postNotionals.put(p.symbol(), Math.abs(p.marketValue()));
        }
        long qtyDelta = proposal.side() == Side.BUY ? proposal.quantity() : -proposal.quantity();
        long postSymbolQty = currentSymbolQty + qtyDelta;
        double postSymbolNotional = Math.abs(postSymbolQty * instrument.price());
        if (postSymbolNotional > 0.0) {
            postNotionals.put(proposal.symbol(), postSymbolNotional);
        }

        double postGross = postNotionals.values().stream().mapToDouble(Double::doubleValue).sum();
        if (postGross <= 0.0) {
            return List.of();
        }

        // Post-trade HHI.
        double hhi = 0.0;
        for (double n : postNotionals.values()) {
            double w = n / postGross;
            hhi += w * w;
        }

        // Post-trade sector weights — proposal's sector gets the symbol's new notional,
        // other sectors keep their existing exposure (we don't know per-symbol mapping
        // for non-proposal symbols beyond what the snapshot already aggregated).
        Map<String, Double> postSector = new HashMap<>(snapshot.sectorExposure());
        double currentSymbolNotional = Math.abs(currentSymbolQty * instrument.price());
        postSector.merge(instrument.sector(),
                postSymbolNotional - currentSymbolNotional,
                Double::sum);

        List<RejectReason> reasons = new ArrayList<>(2);

        if (hhi > ctx.limits().maxHHI()) {
            reasons.add(new RejectReason(
                    NAME,
                    RejectCode.CONCENTRATION_EXCEEDED,
                    String.format("Post-trade HHI %.4f exceeds cap %.4f", hhi, ctx.limits().maxHHI())));
        }

        double maxWeight = ctx.limits().maxSectorWeight();
        for (Map.Entry<String, Double> e : postSector.entrySet()) {
            double weight = Math.max(e.getValue(), 0.0) / postGross;
            if (weight > maxWeight) {
                reasons.add(new RejectReason(
                        NAME,
                        RejectCode.SECTOR_CAP_EXCEEDED,
                        String.format(
                                "Post-trade sector '%s' weight %.4f exceeds cap %.4f",
                                e.getKey(), weight, maxWeight)));
            }
        }

        return reasons;
    }
}
