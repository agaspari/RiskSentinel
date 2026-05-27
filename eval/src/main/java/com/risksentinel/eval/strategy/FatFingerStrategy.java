package com.risksentinel.eval.strategy;

import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.eval.data.Bar;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Adversarial strategy: on every bar for {@link #symbol}, propose a BUY of
 * an obviously-too-large quantity. The gateway must reject 100% of these
 * with {@code FAT_FINGER_QUANTITY}. If any get through, the gateway is
 * broken.
 */
public final class FatFingerStrategy implements Strategy {

    private final String portfolioId;
    private final String symbol;
    private final long quantity;
    private long sequence = 0L;

    public FatFingerStrategy(String portfolioId, String symbol, long obviouslyTooLargeQuantity) {
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(symbol, "symbol");
        if (portfolioId.isBlank() || symbol.isBlank()) {
            throw new IllegalArgumentException("portfolioId/symbol cannot be blank");
        }
        if (obviouslyTooLargeQuantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.portfolioId = portfolioId;
        this.symbol = symbol;
        this.quantity = obviouslyTooLargeQuantity;
    }

    @Override
    public String name() {
        return "FatFinger[" + symbol + ":" + quantity + "]";
    }

    @Override
    public String portfolioId() {
        return portfolioId;
    }

    @Override
    public List<TradeProposal> onBar(Bar bar, RiskSnapshot snapshot, Clock clock) {
        if (!bar.symbol().equals(symbol)) {
            return List.of();
        }
        sequence++;
        String snapshotId = snapshot != null ? snapshot.snapshotId() : "no-snapshot";
        return List.of(new TradeProposal(
                ProposalIds.next(name(), bar, sequence),
                portfolioId,
                symbol,
                Side.BUY,
                quantity,
                bar.close(),
                bar.close(),
                "adversarial: probe fat-finger check",
                1.0,
                snapshotId,
                clock.instant()));
    }
}
