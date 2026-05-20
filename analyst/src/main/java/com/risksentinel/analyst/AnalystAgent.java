package com.risksentinel.analyst;

/**
 * Transport-agnostic contract for the analyst agent.
 *
 * <p>
 * Implementations may be LLM-backed (see {@code LangChain4jAnalyst} in Task
 * 8.2), scripted for testing, or no-ops. Callers must treat every response as
 * untrusted: the {@code summary} is human-facing text, and the
 * {@code toolCalls}
 * audit trail is what actually happened against the system — which the
 * pre-trade gateway has already filtered.
 *
 * <p>
 * An {@code AnalystAgent} owns no state that {@code core} relies on. If the
 * agent crashes or hangs, the position book and risk engine remain consistent.
 */
public interface AnalystAgent {

    /**
     * Run one turn of the agent against the given request. Implementations must
     * honor {@link AnalystRequest#maxToolCalls()} and finish in bounded time —
     * a runaway agent is a bug, not a configuration.
     *
     * @param request the user prompt plus per-turn budgets
     * @return the agent's reply, including the tool-call audit trail
     */
    AnalystResponse handle(AnalystRequest request);
}
