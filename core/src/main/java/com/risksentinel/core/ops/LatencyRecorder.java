package com.risksentinel.core.ops;

/**
 * Multi-writer latency capture with cumulative percentile snapshotting.
 *
 * <p>Each instance tracks one logical probe (e.g. {@code "gateway-decide-nanos"}).
 * Writers call {@link #recordNanos(long)} on the hot path — implementations are
 * CAS-based and lock-free. Readers call {@link #snapshot()} to obtain an
 * immutable percentile view; readers and writers do not block each other.
 *
 * <p>Snapshot semantics are <strong>cumulative</strong>: every sample recorded
 * since construction is reflected in the next {@code snapshot()} call. Active
 * implementations do this by merging each new interval into a cumulative
 * histogram inside {@code snapshot()}.
 */
public sealed interface LatencyRecorder permits ActiveLatencyRecorder, NoopLatencyRecorder {

    void recordNanos(long nanos);

    LatencySnapshot snapshot();

    String name();

    /** Active recorder backed by HdrHistogram. */
    static LatencyRecorder active(String name, long highestTrackableNanos, int significantDigits) {
        return new ActiveLatencyRecorder(name, highestTrackableNanos, significantDigits);
    }

    /** Zero-overhead default — drops every sample. */
    static LatencyRecorder noop(String name) {
        return new NoopLatencyRecorder(name);
    }
}
