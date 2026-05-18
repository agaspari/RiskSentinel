package com.risksentinel.core.domain;

import java.time.Instant;
import java.util.Objects;

public record Trade(
        long tradeId,
        String portfolioId,
        String symbol,
        Side side,
        long quantity,
        double price,
        Instant timestamp
) {
    public Trade {
        Objects.requireNonNull(portfolioId, "portfolioId cannot be null");
        Objects.requireNonNull(symbol, "symbol cannot be null");
        Objects.requireNonNull(side, "side cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        if (portfolioId.isBlank()) {
            throw new IllegalArgumentException("portfolioId cannot be empty");
        }
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be empty");
        }
        if (tradeId <= 0) {
            throw new IllegalArgumentException("tradeId must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("price must be > 0");
        }
    }
}
