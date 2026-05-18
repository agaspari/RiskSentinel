package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.RiskSnapshot;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable bundle of state materialized once per {@link PreTradeGateway#decide}
 * call and passed to every {@link RiskCheck}. Decouples checks from the cache
 * and registry — they see only this frozen view.
 *
 * @param snapshot     latest risk snapshot for the portfolio, or null if none yet
 * @param instrument   instrument metadata for the proposal's symbol, or null if unknown
 * @param limits       configured thresholds
 * @param state        kill switch + idempotency record
 * @param evaluatedAt  wall-clock time the decision started
 */
public record GatewayContext(
        RiskSnapshot snapshot,
        Instrument instrument,
        GatewayLimits limits,
        GatewayState state,
        Instant evaluatedAt
) {
    public GatewayContext {
        // snapshot and instrument may legitimately be null (see Javadoc).
        Objects.requireNonNull(limits, "limits cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt cannot be null");
    }
}
