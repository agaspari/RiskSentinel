package com.risksentinel.core.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of a {@link PreTradeGateway#decide} call. Sealed so all consumers
 * must handle both branches at compile time.
 */
public sealed interface GatewayDecision permits GatewayDecision.Accept, GatewayDecision.Reject {

    String proposalId();

    Instant decidedAt();

    /**
     * The proposal passed every check. Carries the id of the snapshot used
     * to make the decision so the audit log can replay against it.
     */
    record Accept(String proposalId, String snapshotId, Instant decidedAt) implements GatewayDecision {
        public Accept {
            Objects.requireNonNull(proposalId, "proposalId cannot be null");
            Objects.requireNonNull(snapshotId, "snapshotId cannot be null");
            Objects.requireNonNull(decidedAt, "decidedAt cannot be null");
        }
    }

    /**
     * The proposal failed one or more checks. The reasons list is non-empty
     * and defensively copied; callers cannot mutate it.
     */
    record Reject(String proposalId, List<RejectReason> reasons, Instant decidedAt) implements GatewayDecision {
        public Reject {
            Objects.requireNonNull(proposalId, "proposalId cannot be null");
            Objects.requireNonNull(reasons, "reasons cannot be null");
            Objects.requireNonNull(decidedAt, "decidedAt cannot be null");
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("Reject must carry at least one reason");
            }
            reasons = List.copyOf(reasons);
        }
    }
}
