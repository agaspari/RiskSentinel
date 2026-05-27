package com.risksentinel.eval.strategy;

import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.eval.data.Bar;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * On the first bar matching {@link #symbol}, emits one BUY for the configured
 * quantity at the bar's close. Thereafter, emits nothing.
 *
 * <p>Useful as a baseline: a sane gateway should accept this; ending PnL is
 * a hand-checkable {@code (lastClose - firstClose) * quantity}.
 */
public final class BuyAndHoldStrategy implements Strategy {

    private final String portfolioId;
    private final String symbol;
    private final long quantity;
    private boolean bought = false;

    public BuyAndHoldStrategy(String portfolioId, String symbol, long quantity) {
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(symbol, "symbol");
        if (portfolioId.isBlank()) {
            throw new IllegalArgumentException("portfolioId cannot be blank");
        }
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.portfolioId = portfolioId;
        this.symbol = symbol;
        this.quantity = quantity;
    }

    @Override
    public String name() {
        return "BuyAndHold[" + portfolioId + ":" + symbol + ":" + quantity + "]";
    }

    @Override
    public String portfolioId() {
        return portfolioId;
    }

    @Override
    public List<TradeProposal> onBar(Bar bar, RiskSnapshot snapshot, Clock clock) {
        if (bought || !bar.symbol().equals(symbol)) {
            return List.of();
        }
        bought = true;
        String snapshotId = snapshot != null ? snapshot.snapshotId() : "no-snapshot";
        return List.of(new TradeProposal(
                ProposalIds.next(name(), bar, 1L),
                portfolioId,
                symbol,
                Side.BUY,
                quantity,
                bar.close(),
                bar.close(),
                "buy-and-hold initial entry",
                1.0,
                snapshotId,
                clock.instant()));
    }
}
