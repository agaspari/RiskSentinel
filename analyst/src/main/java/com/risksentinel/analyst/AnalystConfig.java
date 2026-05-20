package com.risksentinel.analyst;

import java.time.Duration;

/**
 * Static configuration for a {@link LangChain4jAnalyst} instance. These are
 * implementation-level safety caps; the per-turn budgets on
 * {@link AnalystRequest} layer on top and may be tighter, never looser.
 *
 * <p>{@code systemPromptOverride} is the test seam — production code passes
 * {@code null} and the agent loads the prompt from the {@code analyst-system.md}
 * classpath resource.
 */
public record AnalystConfig(
        int maxTotalToolCalls,
        int maxIterations,
        Duration perCallTimeout,
        String systemPromptOverride
) {

    public AnalystConfig {
        if (maxTotalToolCalls < 0) {
            throw new IllegalArgumentException("maxTotalToolCalls must be >= 0");
        }
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1");
        }
        if (perCallTimeout == null || perCallTimeout.isZero() || perCallTimeout.isNegative()) {
            throw new IllegalArgumentException("perCallTimeout must be positive");
        }
    }

    /** Defaults sized for an evenings-and-weekends dev loop. */
    public static AnalystConfig defaults() {
        return new AnalystConfig(12, 6, Duration.ofSeconds(30), null);
    }
}
