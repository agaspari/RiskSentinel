package com.risksentinel.eval.report;

import org.HdrHistogram.Histogram;

import java.util.Objects;

/**
 * Percentile snapshot from an {@link Histogram}. Nanos units, matching the
 * gateway's {@code LatencyRecorder}. {@code count == 0} when no decisions
 * were made (e.g. an empty data source).
 */
public record LatencyStats(
        long count,
        long p50Nanos,
        long p95Nanos,
        long p99Nanos,
        long maxNanos
) {
    public LatencyStats {
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative");
        }
    }

    public static LatencyStats fromHistogram(Histogram h) {
        Objects.requireNonNull(h, "h");
        if (h.getTotalCount() == 0) {
            return new LatencyStats(0L, 0L, 0L, 0L, 0L);
        }
        return new LatencyStats(
                h.getTotalCount(),
                h.getValueAtPercentile(50.0),
                h.getValueAtPercentile(95.0),
                h.getValueAtPercentile(99.0),
                h.getMaxValue());
    }
}
