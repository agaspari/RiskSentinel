package com.risksentinel.core.gateway;

import java.util.Objects;

/**
 * Single, structured reason emitted by a {@link RiskCheck} when a proposal fails.
 *
 * @param checkName the {@link RiskCheck#name()} that produced this reason
 * @param code      the stable, machine-readable rejection code
 * @param message   a human-readable description, safe to surface to operators / agents
 */
public record RejectReason(String checkName, RejectCode code, String message) {
    public RejectReason {
        Objects.requireNonNull(checkName, "checkName cannot be null");
        Objects.requireNonNull(code, "code cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        if (checkName.isBlank()) {
            throw new IllegalArgumentException("checkName cannot be blank");
        }
    }
}
