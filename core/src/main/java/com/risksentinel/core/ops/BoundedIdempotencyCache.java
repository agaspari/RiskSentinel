package com.risksentinel.core.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrent idempotency record with bounded retention. Replaces the unbounded
 * map that lived inside {@code GatewayState} in Phase 3.
 *
 * <p>Two eviction policies, applied in order on every sweep:
 * <ol>
 *   <li><strong>TTL:</strong> entries whose record timestamp is older than
 *       {@code now - ttl} are removed.</li>
 *   <li><strong>Size cap:</strong> if any entries remain above {@code maxSize},
 *       the oldest by record timestamp are dropped until the size is within
 *       the cap. Size enforcement is O(n log n) but only kicks in when the
 *       sweep is starved or insertion rate exceeds expectation — it is a
 *       guardrail, not the steady-state path.</li>
 * </ol>
 *
 * <p>Concurrency model: backed by a {@link ConcurrentHashMap}. Writers use
 * {@link ConcurrentHashMap#putIfAbsent} which is atomic per key. The sweep
 * uses {@link ConcurrentHashMap#computeIfPresent} with the TTL check inside
 * the lambda, so per-key serialization is provided by the map and no lock is
 * needed.
 */
public final class BoundedIdempotencyCache {

    private static final AtomicInteger INSTANCE_SEQ = new AtomicInteger();

    private final ConcurrentHashMap<String, Instant> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxSize;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    public BoundedIdempotencyCache(Duration ttl, int maxSize, Duration sweepInterval, Clock clock) {
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
            Thread t = new Thread(r, "gateway-idempotency-sweep-" + seq);
            t.setDaemon(true);
            return t;
        });
        long intervalMillis = sweepInterval.toMillis();
        this.scheduler.scheduleAtFixedRate(
                this::safeSweep, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Returns {@code true} iff this proposalId was not previously recorded. */
    public boolean recordIfAbsent(String proposalId, Instant at) {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(at, "at");
        return entries.putIfAbsent(proposalId, at) == null;
    }

    /** Snapshot size — O(1). */
    public int size() {
        return entries.size();
    }

    /**
     * Synchronously evicts expired and overflow entries. Production callers
     * rely on the scheduled sweep; tests use this for determinism.
     *
     * @return number of entries removed by this sweep
     */
    public int sweepNow() {
        Instant cutoff = clock.instant().minus(ttl);
        int removed = 0;

        for (String key : entries.keySet()) {
            Object[] dropped = new Object[1];
            entries.computeIfPresent(key, (k, recordedAt) -> {
                if (recordedAt.isBefore(cutoff)) {
                    dropped[0] = recordedAt;
                    return null;
                }
                return recordedAt;
            });
            if (dropped[0] != null) {
                removed++;
            }
        }

        int currentSize = entries.size();
        if (currentSize > maxSize) {
            int overflow = currentSize - maxSize;
            List<Map.Entry<String, Instant>> sorted = new ArrayList<>(entries.entrySet());
            sorted.sort(Comparator.comparing(Map.Entry::getValue));
            for (int i = 0; i < overflow && i < sorted.size(); i++) {
                Map.Entry<String, Instant> e = sorted.get(i);
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
