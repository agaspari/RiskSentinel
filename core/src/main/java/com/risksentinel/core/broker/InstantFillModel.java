package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;

import java.time.Instant;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Trivial fill strategy: fill every order in full, at the order's limit price,
 * at the instant {@code now}. Used as the default broker model in Phase 4.
 *
 * <p>Returns {@link Optional#empty()} only as defensive protection when the
 * instrument is missing — {@link PaperBroker} pre-checks the registry and
 * marks the order {@link OrderStatus#REJECTED} before calling the model, so
 * this branch is unreachable in normal operation.
 */
public final class InstantFillModel implements FillModel {

    @Override
    public Optional<FillEvent> simulate(
            Order order,
            Instrument instrument,
            Instant now,
            LongSupplier fillIdGenerator) {
        if (instrument == null) {
            return Optional.empty();
        }
        return Optional.of(new FillEvent(
                fillIdGenerator.getAsLong(),
                order.orderId(),
                order.proposalId(),
                order.quantity(),
                order.limitPrice(),
                now));
    }
}
