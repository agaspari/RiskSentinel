package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.TradeProposal;

import java.util.List;

/**
 * Reject every proposal when the kill switch is engaged. Cheapest possible
 * check — runs first in the {@link PreTradeGateway} chain so a halted system
 * pays only an {@link java.util.concurrent.atomic.AtomicBoolean} read per proposal.
 */
final class KillSwitchCheck implements RiskCheck {

    static final String NAME = "KillSwitchCheck";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RejectReason> check(TradeProposal proposal, GatewayContext ctx) {
        if (ctx.state().isKillSwitchEngaged()) {
            return List.of(new RejectReason(
                    NAME,
                    RejectCode.KILL_SWITCH_ENGAGED,
                    "Kill switch is engaged; no proposals are accepted"));
        }
        return List.of();
    }
}
