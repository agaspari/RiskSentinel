package com.risksentinel.core.ops;

import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@link MetricsRegistry} backed by a Micrometer {@link PrometheusMeterRegistry}.
 * The {@code io.micrometer.*} imports are confined to this file; the rest of
 * the codebase only sees the {@code core/ops/} façade types.
 */
public final class MicrometerMetricsRegistry implements MetricsRegistry {

    private record Key(String name, Tags tags) {}

    private final PrometheusMeterRegistry delegate;
    private final ConcurrentHashMap<Key, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Gauge> gauges = new ConcurrentHashMap<>();

    public MicrometerMetricsRegistry() {
        this(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    public MicrometerMetricsRegistry(PrometheusMeterRegistry delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Counter counter(String name, Tags tags) {
        return counters.computeIfAbsent(new Key(name, tags), k -> {
            io.micrometer.core.instrument.Counter mm = delegate.counter(name, toMicrometerTags(tags));
            return new MicrometerCounter(mm);
        });
    }

    @Override
    public Timer timer(String name, Tags tags) {
        return timers.computeIfAbsent(new Key(name, tags), k -> {
            io.micrometer.core.instrument.Timer mm = delegate.timer(name, toMicrometerTags(tags));
            return new MicrometerTimer(mm);
        });
    }

    @Override
    public Gauge gauge(String name, Tags tags, Supplier<Number> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return gauges.computeIfAbsent(new Key(name, tags), k -> {
            io.micrometer.core.instrument.Gauge.builder(name, supplier)
                    .tags(toMicrometerTags(tags))
                    .register(delegate);
            return () -> supplier.get().doubleValue();
        });
    }

    @Override
    public String scrapeText() {
        return delegate.scrape();
    }

    /** Exposed for tests and Phase 5.5 HTTP wiring. */
    public PrometheusMeterRegistry prometheusRegistry() {
        return delegate;
    }

    private static List<Tag> toMicrometerTags(Tags tags) {
        Map<String, String> map = tags.asMap();
        List<Tag> out = new ArrayList<>(map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            out.add(Tag.of(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static final class MicrometerCounter implements Counter {
        private final io.micrometer.core.instrument.Counter delegate;
        MicrometerCounter(io.micrometer.core.instrument.Counter delegate) { this.delegate = delegate; }
        @Override public void increment() { delegate.increment(); }
        @Override public void increment(long amount) { delegate.increment(amount); }
        @Override public long count() { return (long) delegate.count(); }
    }

    private static final class MicrometerTimer implements Timer {
        private final io.micrometer.core.instrument.Timer delegate;
        MicrometerTimer(io.micrometer.core.instrument.Timer delegate) { this.delegate = delegate; }
        @Override public void recordNanos(long nanos) { delegate.record(nanos, TimeUnit.NANOSECONDS); }
        @Override public long count() { return delegate.count(); }
    }
}
