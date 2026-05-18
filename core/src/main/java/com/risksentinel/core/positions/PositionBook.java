package com.risksentinel.core.positions;

import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.Trade;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface PositionBook {
    void apply(Trade trade);
    Optional<Position> getPosition(String portfolioId, String symbol);
    Collection<Position> getPositions(String portfolioId);
    Set<String> getPortfolioIds();
}
