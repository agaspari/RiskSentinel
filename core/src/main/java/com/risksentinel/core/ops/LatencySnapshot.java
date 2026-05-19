package com.risksentinel.core.ops;

/**
 * Immutable view of a {@link LatencyRecorder}'s cumulative percentile state at
 * the moment {@link LatencyRecorder#snapshot()} was called.
 */
public record LatencySnapshot(
        String probe,
        long count,
        long p50Nanos,
        long p95Nanos,
        long p99Nanos,
        long p999Nanos,
        long maxNanos
) {
    public LatencySnapshot {
        java.util.Objects.requireNonNull(probe, "probe");
    }
}
