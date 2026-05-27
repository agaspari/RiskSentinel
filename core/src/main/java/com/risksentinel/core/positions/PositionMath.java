package com.risksentinel.core.positions;

import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.Trade;

/**
 * Position-accounting math shared by {@link SimplePositionBook} and
 * {@link ConcurrentPositionBook}. Pure functions; no state.
 */
final class PositionMath {

    private PositionMath() {}

    /** Signed delta for a trade — BUY is positive, SELL is negative. */
    static long deltaQuantity(Trade trade) {
        return trade.side() == Side.BUY ? trade.quantity() : -trade.quantity();
    }

    /**
     * The new average cost after {@code trade} is applied to a position whose
     * current state is {@code (oldQty, oldAvgCost)}, given the precomputed
     * {@code newQty = oldQty + deltaQuantity(trade)}.
     *
     * <p>Rules:
     * <ol>
     *   <li>{@code newQty == 0} → position closed; cost is moot, return 0.0.</li>
     *   <li>{@code oldQty == 0} OR the sign of position flipped → the new
     *       position's basis is the trade price (fresh open or cross-through-zero).</li>
     *   <li>Same direction, position grows in absolute size → weighted
     *       average of the old basis and the trade's basis.</li>
     *   <li>Same direction, position shrinks in absolute size → basis
     *       unchanged (a partial close does not move the average cost).</li>
     * </ol>
     */
    static double newAvgCost(long oldQty, double oldAvgCost, Trade trade, long newQty) {
        if (newQty == 0L) {
            return 0.0;
        }
        if (oldQty == 0L || Long.signum(oldQty) != Long.signum(newQty)) {
            return trade.price();
        }
        if (Math.abs(newQty) > Math.abs(oldQty)) {
            double oldBasis = Math.abs(oldQty) * oldAvgCost;
            double tradeBasis = (double) trade.quantity() * trade.price();
            return (oldBasis + tradeBasis) / Math.abs(newQty);
        }
        return oldAvgCost;
    }
}
