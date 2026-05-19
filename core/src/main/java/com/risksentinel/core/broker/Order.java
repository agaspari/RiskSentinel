package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Side;

import java.time.Instant;
import java.util.Objects;

/**
 * Broker-internal record of a proposal that has been accepted into the order book.
 *
 * <p>Distinct from {@link com.risksentinel.core.domain.TradeProposal} (the agent's
 * intent) and {@link com.risksentinel.core.domain.Trade} (a realized fill on the
 * ingestion queue). An order links a proposalId to the lifecycle state the broker
 * is responsible for transitioning.
 */
public record Order(
        String orderId,
        String proposalId,
        String portfolioId,
        String symbol,
        Side side,
        long quantity,
        double limitPrice,
        OrderStatus status,
        Instant submittedAt,
        Instant lastUpdatedAt
) {
    public Order {
        Objects.requireNonNull(orderId, "orderId cannot be null");
        Objects.requireNonNull(proposalId, "proposalId cannot be null");
        Objects.requireNonNull(portfolioId, "portfolioId cannot be null");
        Objects.requireNonNull(symbol, "symbol cannot be null");
        Objects.requireNonNull(side, "side cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(submittedAt, "submittedAt cannot be null");
        Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt cannot be null");

        if (orderId.isBlank()) {
            throw new IllegalArgumentException("orderId cannot be empty");
        }
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
        if (lastUpdatedAt.isBefore(submittedAt)) {
            throw new IllegalArgumentException("lastUpdatedAt cannot precede submittedAt");
        }
    }

    /** Returns a copy with the given status and updated timestamp. */
    public Order withStatus(OrderStatus newStatus, Instant at) {
        Objects.requireNonNull(newStatus, "newStatus cannot be null");
        Objects.requireNonNull(at, "at cannot be null");
        return new Order(orderId, proposalId, portfolioId, symbol, side,
                quantity, limitPrice, newStatus, submittedAt, at);
    }
}
