package com.risksentinel.core.broker;

import java.time.Instant;
import java.util.Objects;

/**
 * Broker-emitted notification that an {@link Order} has been filled (in whole or
 * in part). A {@code FillEvent} is translated into a
 * {@link com.risksentinel.core.domain.Trade} when it crosses the boundary back
 * into the ingestion path; this record exists so the broker can carry the
 * orderId/proposalId linkage that {@code Trade} does not model.
 */
public record FillEvent(
        long fillId,
        String orderId,
        String proposalId,
        long filledQuantity,
        double filledPrice,
        Instant filledAt
) {
    public FillEvent {
        Objects.requireNonNull(orderId, "orderId cannot be null");
        Objects.requireNonNull(proposalId, "proposalId cannot be null");
        Objects.requireNonNull(filledAt, "filledAt cannot be null");

        if (orderId.isBlank()) {
            throw new IllegalArgumentException("orderId cannot be empty");
        }
        if (proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId cannot be empty");
        }
        if (fillId <= 0) {
            throw new IllegalArgumentException("fillId must be positive");
        }
        if (filledQuantity <= 0) {
            throw new IllegalArgumentException("filledQuantity must be > 0");
        }
        if (filledPrice <= 0) {
            throw new IllegalArgumentException("filledPrice must be > 0");
        }
    }
}
