package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.TradeProposal;

import java.util.List;

/**
 * Reject proposals whose {@code proposalId} has been seen before. Records
 * new ids atomically via {@link GatewayState#recordProposalIfAbsent}; under
 * concurrent submission of the same id, exactly one call records and passes
 * — the rest are rejected as duplicates.
 */
final class IdempotencyCheck implements RiskCheck {

    static final String NAME = "IdempotencyCheck";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RejectReason> check(TradeProposal proposal, GatewayContext ctx) {
        boolean recorded = ctx.state().recordProposalIfAbsent(proposal.proposalId(), ctx.evaluatedAt());
        if (!recorded) {
            return List.of(new RejectReason(
                    NAME,
                    RejectCode.DUPLICATE_PROPOSAL,
                    "Proposal id has already been decided"));
        }
        return List.of();
    }
}
