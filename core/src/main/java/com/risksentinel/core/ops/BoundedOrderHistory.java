package com.risksentinel.core.ops;

import com.risksentinel.core.broker.Order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Concurrent {@code proposalId -> Order} map with bounded retention. Replaces the
 * unbounded map that lived inside {@code PaperBroker} in Phase 4.
 *
 * <p>Eviction policy is keyed off {@link Order#lastUpdatedAt()}, not insertion
 * time: an order that just transitioned (e.g. {@code NEW → FILLED}) resets its
 * retention clock. This matches the operational reality that "recent activity"
 * is what we care about, not "earliest created."
 *
 * <p>Concurrency model: backed by a {@link ConcurrentHashMap}. Writers use
 * {@code computeIfAbsent} or {@code compute}, which serialize per-key. The
 * sweep uses {@link ConcurrentHashMap#computeIfPresent} with the TTL check
 * inside the lambda, so a sweep cannot remove an order that a concurrent
 * compute is rewriting — per-key serialization is provided by the map.
 */
public final class BoundedOrderHistory {

    private static final AtomicInteger INSTANCE_SEQ = new AtomicInteger();

    private final ConcurrentHashMap<String, Order> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxSize;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    public BoundedOrderHistory(Duration ttl, int maxSize, Duration sweepInterval, Clock clock) {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(sweepInterval, "sweepInterval");
        Objects.requireNonNull(clock, "clock");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (sweepInterval.isNegative() || sweepInterval.isZero()) {
            throw new IllegalArgumentException("sweepInterval must be positive");
        }
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.ttl = ttl;
        this.maxSize = maxSize;
        this.clock = clock;

        int seq = INSTANCE_SEQ.incrementAndGet();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "broker-order-history-sweep-" + seq);
            t.setDaemon(true);
            return t;
        });
        long intervalMillis = sweepInterval.toMillis();
        this.scheduler.scheduleAtFixedRate(
                this::safeSweep, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Atomic per-key: invokes the mapping function only if absent. */
    public Order computeIfAbsent(String proposalId, Function<String, Order> mappingFunction) {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(mappingFunction, "mappingFunction");
        return entries.computeIfAbsent(proposalId, mappingFunction);
    }

    /** Atomic per-key compute. The remapping function may return null to remove. */
    public Order compute(String proposalId, BiFunction<String, Order, Order> remappingFunction) {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(remappingFunction, "remappingFunction");
        return entries.compute(proposalId, remappingFunction);
    }

    public Optional<Order> get(String proposalId) {
        return Optional.ofNullable(entries.get(proposalId));
    }

    public int size() {
        return entries.size();
    }

    /**
     * Synchronously evict orders whose {@link Order#lastUpdatedAt()} predates
     * {@code now - ttl}, then enforce {@code maxSize} by dropping the
     * least-recently-updated entries.
     *
     * @return number of entries removed by this sweep
     */
    public int sweepNow() {
        Instant cutoff = clock.instant().minus(ttl);
        int removed = 0;

        for (String key : entries.keySet()) {
            boolean[] dropped = {false};
            entries.computeIfPresent(key, (k, order) -> {
                if (order.lastUpdatedAt().isBefore(cutoff)) {
                    dropped[0] = true;
                    return null;
                }
                return order;
            });
            if (dropped[0]) {
                removed++;
            }
        }

        int currentSize = entries.size();
        if (currentSize > maxSize) {
            int overflow = currentSize - maxSize;
            List<Map.Entry<String, Order>> sorted = new ArrayList<>(entries.entrySet());
            sorted.sort(Comparator.comparing(e -> e.getValue().lastUpdatedAt()));
            for (int i = 0; i < overflow && i < sorted.size(); i++) {
                Map.Entry<String, Order> e = sorted.get(i);
                if (entries.remove(e.getKey(), e.getValue())) {
                    removed++;
                }
            }
        }

        return removed;
    }

    public void shutdown() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void safeSweep() {
        try {
            sweepNow();
        } catch (Throwable ignored) {
            // Never let a sweep failure kill the scheduler.
        }
    }
}
