package com.risksentinel.core.positions;

import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.Trade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ConcurrentPositionBook implements PositionBook {

    private static final int STRIPES = 64;
    private final Object[] locks = new Object[STRIPES];
    
    // Map of portfolioId -> (Map of symbol -> Position)
    @SuppressWarnings("unchecked")
    private final Map<String, Map<String, Position>>[] stripedPositions = new Map[STRIPES];

    public ConcurrentPositionBook() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
            stripedPositions[i] = new HashMap<>();
        }
    }

    private int getStripe(String portfolioId) {
        return portfolioId.hashCode() & (STRIPES - 1);
    }

    @Override
    public void apply(Trade trade) {
        String portfolioId = trade.portfolioId();
        String symbol = trade.symbol();
        int stripe = getStripe(portfolioId);

        synchronized (locks[stripe]) {
            Map<String, Map<String, Position>> positions = stripedPositions[stripe];
            positions.putIfAbsent(portfolioId, new HashMap<>());
            Map<String, Position> portfolio = positions.get(portfolioId);
            
            Position current = portfolio.getOrDefault(symbol, new Position(portfolioId, symbol, 0, 0.0, 0.0));

            long newQty = current.quantity() + PositionMath.deltaQuantity(trade);
            double newAvgCost = PositionMath.newAvgCost(current.quantity(), current.avgCost(), trade, newQty);

            portfolio.put(symbol, new Position(portfolioId, symbol, newQty, newAvgCost, 0.0));
        }
    }

    @Override
    public Optional<Position> getPosition(String portfolioId, String symbol) {
        int stripe = getStripe(portfolioId);
        synchronized (locks[stripe]) {
            Map<String, Map<String, Position>> positions = stripedPositions[stripe];
            Map<String, Position> portfolio = positions.get(portfolioId);
            if (portfolio == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(portfolio.get(symbol));
        }
    }

    @Override
    public Collection<Position> getPositions(String portfolioId) {
        int stripe = getStripe(portfolioId);
        synchronized (locks[stripe]) {
            Map<String, Map<String, Position>> positions = stripedPositions[stripe];
            Map<String, Position> portfolio = positions.get(portfolioId);
            if (portfolio == null) {
                return List.of();
            }
            // Return a copy so iterating outside the lock is safe
            return new ArrayList<>(portfolio.values());
        }
    }

    @Override
    public Set<String> getPortfolioIds() {
        Set<String> allIds = new HashSet<>();
        for (int i = 0; i < STRIPES; i++) {
            synchronized (locks[i]) {
                allIds.addAll(stripedPositions[i].keySet());
            }
        }
        return allIds;
    }
}
