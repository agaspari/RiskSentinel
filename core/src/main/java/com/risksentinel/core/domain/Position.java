package com.risksentinel.core.domain;

import java.util.Objects;

public record Position(
        String portfolioId,
        String symbol,
        long quantity,
        double avgCost,
        double marketValue
) {
    public Position {
        Objects.requireNonNull(portfolioId, "portfolioId cannot be null");
        Objects.requireNonNull(symbol, "symbol cannot be null");

        if (portfolioId.isBlank()) {
            throw new IllegalArgumentException("portfolioId cannot be empty");
        }
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be empty");
        }
        if (avgCost < 0) {
            throw new IllegalArgumentException("avgCost must be >= 0");
        }
    }
}
