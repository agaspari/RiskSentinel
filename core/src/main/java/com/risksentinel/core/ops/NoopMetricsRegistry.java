package com.risksentinel.core.ops;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Default {@link MetricsRegistry}. Counters are backed by {@link LongAdder} so
 * tests and in-process callers can still query {@link Counter#count()}; timers
 * and gauges are zero-cost sinks. {@link #scrapeText()} returns the empty string.
 */
public final class NoopMetricsRegistry implements MetricsRegistry {

    private record Key(String name, Tags tags) {}

    private final ConcurrentHashMap<Key, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Gauge> gauges = new ConcurrentHashMap<>();

    @Override
    public Counter counter(String name, Tags tags) {
        return counters.computeIfAbsent(new Key(name, tags), k -> new LongAdderCounter());
    }

    @Override
    public Timer timer(String name, Tags tags) {
        return timers.computeIfAbsent(new Key(name, tags), k -> new LongAdderTimer());
    }

    @Override
    public Gauge gauge(String name, Tags tags, Supplier<Number> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return gauges.computeIfAbsent(new Key(name, tags),
                k -> () -> supplier.get().doubleValue());
    }

    @Override
    public String scrapeText() {
        return "";
    }

    private static final class LongAdderCounter implements Counter {
        private final LongAdder adder = new LongAdder();
        @Override public void increment() { adder.increment(); }
        @Override public void increment(long amount) { adder.add(amount); }
        @Override public long count() { return adder.sum(); }
    }

    private static final class LongAdderTimer implements Timer {
        private final LongAdder count = new LongAdder();
        @Override public void recordNanos(long nanos) { count.increment(); }
        @Override public long count() { return count.sum(); }
    }
}
