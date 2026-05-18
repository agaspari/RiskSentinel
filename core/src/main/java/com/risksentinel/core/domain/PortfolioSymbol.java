package com.risksentinel.core.domain;

import java.util.Objects;

public record PortfolioSymbol(String portfolioId, String symbol) {
    public PortfolioSymbol {
        Objects.requireNonNull(portfolioId, "portfolioId cannot be null");
        Objects.requireNonNull(symbol, "symbol cannot be null");
    }
}
