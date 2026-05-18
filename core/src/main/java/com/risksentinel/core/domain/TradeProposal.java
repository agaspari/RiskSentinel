package com.risksentinel.core.domain;

import java.time.Instant;
import java.util.Objects;

public record TradeProposal(
        String proposalId,
        String portfolioId,
        String symbol,
        Side side,
        long quantity,
        double limitPrice,
        double expectedPrice,
        String rationale,
        double confidence,
        String snapshotId,
        Instant proposedAt
) {
    public TradeProposal {
        Objects.requireNonNull(proposalId, "proposalId cannot be null");
        Objects.requireNonNull(portfolioId, "portfolioId cannot be null");
        Objects.requireNonNull(symbol, "symbol cannot be null");
        Objects.requireNonNull(side, "side cannot be null");
        Objects.requireNonNull(snapshotId, "snapshotId cannot be null");
        Objects.requireNonNull(proposedAt, "proposedAt cannot be null");
        
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0]");
        }
    }
}
