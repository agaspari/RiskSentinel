package com.risksentinel.core.positions;

import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.Trade;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SimplePositionBook implements PositionBook {
    
    private final Map<String, Map<String, Position>> positions = new HashMap<>();

    @Override
    public void apply(Trade trade) {
        String portfolioId = trade.portfolioId();
        String symbol = trade.symbol();
        
        positions.putIfAbsent(portfolioId, new HashMap<>());
        Map<String, Position> portfolio = positions.get(portfolioId);
        
        Position current = portfolio.getOrDefault(symbol, new Position(portfolioId, symbol, 0, 0.0, 0.0));
        
        long newQty;
        double newAvgCost = current.avgCost();
        
        if (trade.side() == Side.BUY) {
            newQty = current.quantity() + trade.quantity();
            if (newQty > 0) {
                newAvgCost = ((current.quantity() * current.avgCost()) + (trade.quantity() * trade.price())) / newQty;
            }
        } else {
            newQty = current.quantity() - trade.quantity();
            // Avg cost doesn't change on sells.
        }
        
        portfolio.put(symbol, new Position(portfolioId, symbol, newQty, newAvgCost, 0.0));
    }

    @Override
    public Optional<Position> getPosition(String portfolioId, String symbol) {
        Map<String, Position> portfolio = positions.get(portfolioId);
        if (portfolio == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(portfolio.get(symbol));
    }

    @Override
    public Collection<Position> getPositions(String portfolioId) {
        Map<String, Position> portfolio = positions.get(portfolioId);
        if (portfolio == null) {
            return Collections.emptyList();
        }
        return portfolio.values();
    }

    @Override
    public Set<String> getPortfolioIds() {
        return positions.keySet();
    }
}
