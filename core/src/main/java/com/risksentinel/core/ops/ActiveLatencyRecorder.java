package com.risksentinel.core.ops;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.Objects;

/**
 * HdrHistogram-backed latency recorder.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li>Writers call {@link #recordNanos(long)} which delegates to
 *       {@link Recorder#recordValue(long)} — a CAS / thread-local write that
 *       does not block other writers.</li>
 *   <li>Readers call {@link #snapshot()}, which is the only mutator of the
 *       cumulative histogram. The snapshot method is synchronized: snapshots
 *       are infrequent and we serialize them to make cumulative-merge
 *       deterministic. Writers are never blocked by snapshot calls.</li>
 * </ul>
 *
 * <p>Cumulative semantics: each {@code snapshot()} call pulls the latest
 * interval from the {@link Recorder} (which atomically resets the recorder's
 * internal buckets) and merges it into a cumulative {@link Histogram}. The
 * snapshot returns percentiles read from that cumulative histogram.
 */
public final class ActiveLatencyRecorder implements LatencyRecorder {

    private final String name;
    private final Recorder recorder;
    private final Histogram cumulative;
    private Histogram intervalReuse;

    ActiveLatencyRecorder(String name, long highestTrackableNanos, int significantDigits) {
        this.name = Objects.requireNonNull(name, "name");
        if (highestTrackableNanos < 2) {
            throw new IllegalArgumentException("highestTrackableNanos must be >= 2");
        }
        if (significantDigits < 0 || significantDigits > 5) {
            throw new IllegalArgumentException("significantDigits must be in [0, 5]");
        }
        this.recorder = new Recorder(highestTrackableNanos, significantDigits);
        this.cumulative = new Histogram(highestTrackableNanos, significantDigits);
    }

    @Override
    public void recordNanos(long nanos) {
        if (nanos < 0) {
            return;
        }
        long clamped = Math.min(nanos, cumulative.getHighestTrackableValue());
        recorder.recordValue(clamped);
    }

    @Override
    public synchronized LatencySnapshot snapshot() {
        intervalReuse = recorder.getIntervalHistogram(intervalReuse);
        cumulative.add(intervalReuse);
        return new LatencySnapshot(
                name,
                cumulative.getTotalCount(),
                cumulative.getValueAtPercentile(50.0),
                cumulative.getValueAtPercentile(95.0),
                cumulative.getValueAtPercentile(99.0),
                cumulative.getValueAtPercentile(99.9),
                cumulative.getMaxValue());
    }

    @Override
    public String name() {
        return name;
    }
}
