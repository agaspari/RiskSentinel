package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;

import java.time.Instant;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Strategy for deciding whether (and how) a working {@link Order} produces a
 * {@link FillEvent} at a given instant.
 *
 * <p>Implementations must be pure functions: no executor scheduling, no I/O,
 * no mutation of shared state. The broker owns all order-state transitions.
 *
 * <p>Returning {@link Optional#empty()} signals "no fill yet — leave the order
 * working." Phase 4 ships only {@link InstantFillModel}, which always fills.
 */
public interface FillModel {

    Optional<FillEvent> simulate(
            Order order,
            Instrument instrument,
            Instant now,
            LongSupplier fillIdGenerator);
}
