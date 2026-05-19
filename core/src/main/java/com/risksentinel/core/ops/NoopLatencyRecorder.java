package com.risksentinel.core.ops;

import java.util.Objects;

/**
 * Drops every sample. Used as the default recorder anywhere instrumentation
 * is wired up before metrics are enabled, so the hot path stays branch-free.
 */
public final class NoopLatencyRecorder implements LatencyRecorder {

    private final String name;

    NoopLatencyRecorder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public void recordNanos(long nanos) {
        // intentional no-op
    }

    @Override
    public LatencySnapshot snapshot() {
        return new LatencySnapshot(name, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    @Override
    public String name() {
        return name;
    }
}
