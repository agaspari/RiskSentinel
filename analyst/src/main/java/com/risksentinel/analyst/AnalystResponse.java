package com.risksentinel.analyst;

import java.util.List;
import java.util.Objects;

/**
 * One turn of output from an {@link AnalystAgent}.
 *
 * <p>The {@code toolCalls} list is the authoritative audit trail of what the
 * agent actually did during this turn, in order. Downstream consumers (UI,
 * logging) should prefer it over the {@code summary} when reporting effects on
 * the system — the gateway has already filtered any trade proposals, but the
 * summary is still untrusted natural language.
 */
public record AnalystResponse(
        String summary,
        List<ToolCall> toolCalls,
        Outcome outcome
) {

    public AnalystResponse {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(toolCalls, "toolCalls");
        Objects.requireNonNull(outcome, "outcome");
        toolCalls = List.copyOf(toolCalls);
    }

    /**
     * High-level result of the turn. {@link #ANSWERED} means the agent
     * completed normally; the other values are loud signals that something
     * cut the turn short.
     */
    public enum Outcome {
        /** The agent produced a final answer within its budgets. */
        ANSWERED,
        /** The agent explicitly declined to act on the request. */
        REFUSED,
        /** Hit {@code maxToolCalls} or {@code maxThinkingTime} before answering. */
        BUDGET_EXHAUSTED,
        /** An unrecoverable error occurred (model failure, tool dispatch crash). */
        ERROR
    }

    /**
     * One tool invocation as it actually happened.
     *
     * <p>{@code inputJson} and {@code outputJson} are the raw JSON strings
     * exchanged with the tool — useful for replaying or diffing turns. The
     * agent is responsible for not putting sensitive data in tool inputs.
     */
    public record ToolCall(String name, String inputJson, String outputJson) {

        public ToolCall {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(inputJson, "inputJson");
            Objects.requireNonNull(outputJson, "outputJson");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }
    }
}
