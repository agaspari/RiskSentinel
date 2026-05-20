package com.risksentinel.analyst;

import java.time.Duration;
import java.util.Objects;

/**
 * One turn of input for an {@link AnalystAgent}.
 *
 * <p>{@code maxThinkingTime} bounds wall-clock time for the whole turn;
 * {@code maxToolCalls} bounds the number of tool invocations the agent may
 * perform within it. Both budgets are hard caps — implementations must
 * terminate at the limit and surface a {@code BUDGET_EXHAUSTED} outcome rather
 * than throwing or looping.
 */
public record AnalystRequest(
        String portfolioId,
        String userMessage,
        Duration maxThinkingTime,
        int maxToolCalls
) {

    public AnalystRequest {
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(maxThinkingTime, "maxThinkingTime");
        if (portfolioId.isBlank()) {
            throw new IllegalArgumentException("portfolioId must not be blank");
        }
        if (userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        if (maxThinkingTime.isZero() || maxThinkingTime.isNegative()) {
            throw new IllegalArgumentException("maxThinkingTime must be positive");
        }
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("maxToolCalls must be >= 0");
        }
    }
}
