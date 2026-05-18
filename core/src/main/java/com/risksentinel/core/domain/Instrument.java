package com.risksentinel.core.domain;

import java.util.Objects;

public record Instrument(
        String symbol,
        String sector,
        String region,
        double price
) {
    public Instrument {
        Objects.requireNonNull(symbol, "symbol cannot be null");
        Objects.requireNonNull(sector, "sector cannot be null");
        Objects.requireNonNull(region, "region cannot be null");
        
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be empty");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("price must be > 0");
        }
    }
}
