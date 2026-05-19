package com.risksentinel.core.audit;

import com.risksentinel.core.domain.Side;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, persistable representation of a gateway decision.
 *
 * <p>Captures everything needed to reconstruct the decision after the fact:
 * the proposal context, the outcome, the first rejection code (for quick
 * indexing), and the full reasons list as JSON.
 */
public record DecisionRecord(
        String proposalId,
        String portfolioId,
        String symbol,
        Side side,
        long quantity,
        double limitPrice,
        String snapshotId,
        DecisionType type,
        String firstRejectCode,
        String reasonsJson,
        Instant decidedAt
) {
    public DecisionRecord {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reasonsJson, "reasonsJson");
        Objects.requireNonNull(decidedAt, "decidedAt");

        if (proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId cannot be empty");
        }
        if (portfolioId.isBlank()) {
            throw new IllegalArgumentException("portfolioId cannot be empty");
        }
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be empty");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (limitPrice <= 0) {
            throw new IllegalArgumentException("limitPrice must be > 0");
        }
        if (type == DecisionType.ACCEPT && firstRejectCode != null) {
            throw new IllegalArgumentException("ACCEPT records must not have a firstRejectCode");
        }
        if (type == DecisionType.REJECT && firstRejectCode == null) {
            throw new IllegalArgumentException("REJECT records must include a firstRejectCode");
        }
    }
}
