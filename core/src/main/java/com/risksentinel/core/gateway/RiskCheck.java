package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.TradeProposal;

import java.util.List;

/**
 * A single, atomic validation rule applied to a {@link TradeProposal}
 * by the {@link PreTradeGateway}.
 *
 * <p><strong>Implementations must be pure functions</strong> of the proposal
 * and the supplied {@link GatewayContext}. They must not block, perform I/O,
 * or throw. {@code IdempotencyCheck} is the only check permitted to mutate
 * state (a single CAS in {@link GatewayState}).
 */
public interface RiskCheck {

    /** Stable identifier surfaced in {@link RejectReason#checkName()}. */
    String name();

    /**
     * Evaluate the proposal. Return an empty list if the proposal passes
     * this check, otherwise one or more {@link RejectReason}s describing
     * every distinct failure mode this check observed.
     */
    List<RejectReason> check(TradeProposal proposal, GatewayContext ctx);
}
