package com.risksentinel.core.ops;

/**
 * Records elapsed durations in nanoseconds. Created by {@link MetricsRegistry}.
 *
 * <p>Distinct from {@link LatencyRecorder}: a {@code Timer} is the
 * Micrometer-routed publication surface; {@code LatencyRecorder} is the
 * HdrHistogram-backed in-process probe. The broker and gateway use both —
 * one for in-process percentile reads, one for dashboard publication.
 */
public interface Timer {

    void recordNanos(long nanos);

    long count();
}
