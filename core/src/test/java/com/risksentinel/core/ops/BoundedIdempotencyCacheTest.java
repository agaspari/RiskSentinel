package com.risksentinel.core.ops;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedIdempotencyCacheTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    /** A clock backed by a mutable AtomicReference so tests can advance time deterministically. */
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;
        TestClock(Instant initial) { this.now = new AtomicReference<>(initial); }
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private BoundedIdempotencyCache cache(Duration ttl, int maxSize, TestClock clock) {
        return new BoundedIdempotencyCache(ttl, maxSize, Duration.ofHours(1), clock);
    }

    @Test
    void shouldReject_whenTtlNotPositive() {
        assertThatThrownBy(() -> new BoundedIdempotencyCache(
                Duration.ZERO, 100, Duration.ofSeconds(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenMaxSizeNotPositive() {
        assertThatThrownBy(() -> new BoundedIdempotencyCache(
                Duration.ofSeconds(1), 0, Duration.ofSeconds(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnTrueOnFirstRecord_falseOnReplay() {
        TestClock clock = new TestClock(T0);
        BoundedIdempotencyCache c = cache(Duration.ofMinutes(10), 1000, clock);
        try {
            assertThat(c.recordIfAbsent("p-1", T0)).isTrue();
            assertThat(c.recordIfAbsent("p-1", T0)).isFalse();
        } finally {
            c.shutdown();
        }
    }

    @Test
    void shouldEvictEntry_whenOlderThanTtl() {
        TestClock clock = new TestClock(T0);
        BoundedIdempotencyCache c = cache(Duration.ofMinutes(5), 1000, clock);
        try {
            c.recordIfAbsent("p-old", T0);
            c.recordIfAbsent("p-fresh", T0);
            clock.advance(Duration.ofMinutes(6));
            c.recordIfAbsent("p-fresh", clock.instant()); // re-record fresh after clock advances
            // p-fresh's stored timestamp is the original T0 (putIfAbsent keeps the first),
            // so it would also be evicted. Make a truly fresh entry instead:
            c.recordIfAbsent("p-new", clock.instant());

            int removed = c.sweepNow();

            assertThat(removed).isEqualTo(2); // p-old and p-fresh both stale
            assertThat(c.size()).isEqualTo(1);
        } finally {
            c.shutdown();
        }
    }

    @Test
    void shouldEvictOldestEntries_whenSizeExceedsMax() {
        TestClock clock = new TestClock(T0);
        BoundedIdempotencyCache c = cache(Duration.ofDays(1), 3, clock);
        try {
            c.recordIfAbsent("p-1", T0);
            clock.advance(Duration.ofSeconds(1));
            c.recordIfAbsent("p-2", clock.instant());
            clock.advance(Duration.ofSeconds(1));
            c.recordIfAbsent("p-3", clock.instant());
            clock.advance(Duration.ofSeconds(1));
            c.recordIfAbsent("p-4", clock.instant());
            clock.advance(Duration.ofSeconds(1));
            c.recordIfAbsent("p-5", clock.instant());

            int removed = c.sweepNow();

            assertThat(c.size()).isEqualTo(3);
            assertThat(removed).isEqualTo(2);
            // p-1 and p-2 were the oldest; only p-3, p-4, p-5 should remain.
            assertThat(c.recordIfAbsent("p-1", clock.instant())).isTrue();
            assertThat(c.recordIfAbsent("p-2", clock.instant())).isTrue();
            assertThat(c.recordIfAbsent("p-3", clock.instant())).isFalse();
        } finally {
            c.shutdown();
        }
    }

    @Test
    void shouldCleanShutdown_withoutLeakingScheduler() throws InterruptedException {
        TestClock clock = new TestClock(T0);

        int before = sweepThreadCount();
        BoundedIdempotencyCache c = new BoundedIdempotencyCache(
                Duration.ofMinutes(1), 100, Duration.ofMillis(50), clock);
        c.recordIfAbsent("p-1", T0);
        Thread.sleep(120); // let the sweep tick at least once
        int duringConstruction = sweepThreadCount();
        c.shutdown();

        // Briefly wait for the scheduler thread to wind down after shutdownNow.
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline && sweepThreadCount() >= duringConstruction) {
            Thread.sleep(20);
        }
        int after = sweepThreadCount();

        assertThat(duringConstruction).isGreaterThan(before);
        assertThat(after).isLessThan(duringConstruction);
    }

    private static int sweepThreadCount() {
        Thread[] all = new Thread[Thread.activeCount() * 4];
        int n = Thread.enumerate(all);
        int count = 0;
        for (int i = 0; i < n; i++) {
            Thread t = all[i];
            if (t != null && t.isAlive() && t.getName().startsWith("gateway-idempotency-sweep-")) {
                count++;
            }
        }
        return count;
    }

    @Test
    void shouldRecordConcurrently_underBurst() throws InterruptedException {
        TestClock clock = new TestClock(T0);
        BoundedIdempotencyCache c = cache(Duration.ofMinutes(10), 100_000, clock);
        try {
            int threads = 32;
            int perThread = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger wins = new AtomicInteger();
            try {
                for (int t = 0; t < threads; t++) {
                    final int threadId = t;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                if (c.recordIfAbsent("t" + threadId + "-i" + i, T0)) {
                                    wins.incrementAndGet();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(wins.get()).isEqualTo(threads * perThread);
            assertThat(c.size()).isEqualTo(threads * perThread);
        } finally {
            c.shutdown();
        }
    }

    @Test
    void shouldRemainAtomic_underContendedSameProposalId() throws InterruptedException {
        TestClock clock = new TestClock(T0);
        BoundedIdempotencyCache c = cache(Duration.ofMinutes(10), 1000, clock);
        try {
            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int trial = 0; trial < 20; trial++) {
                    CountDownLatch start = new CountDownLatch(1);
                    CountDownLatch done = new CountDownLatch(threads);
                    AtomicInteger wins = new AtomicInteger();
                    String id = "race-" + trial;

                    for (int t = 0; t < threads; t++) {
                        pool.submit(() -> {
                            try {
                                start.await();
                                if (c.recordIfAbsent(id, T0)) {
                                    wins.incrementAndGet();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                done.countDown();
                            }
                        });
                    }
                    start.countDown();
                    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(wins.get())
                            .as("trial %d: exactly one thread must win the CAS", trial)
                            .isEqualTo(1);
                }
            } finally {
                pool.shutdownNow();
            }
        } finally {
            c.shutdown();
        }
    }

    @Test
    void shouldRespectSizeCap_evenUnderInsertionBurst() {
        TestClock clock = new TestClock(T0);
        BoundedIdempotencyCache c = cache(Duration.ofDays(1), 50, clock);
        try {
            for (int i = 0; i < 500; i++) {
                clock.advance(Duration.ofMillis(1));
                c.recordIfAbsent("p-" + i, clock.instant());
            }
            c.sweepNow();
            assertThat(c.size()).isEqualTo(50);
        } finally {
            c.shutdown();
        }
    }

    @Test
    void shouldKeepDistinctIds_afterEviction() {
        TestClock clock = new TestClock(T0);
        BoundedIdempotencyCache c = cache(Duration.ofDays(1), 3, clock);
        try {
            for (int i = 0; i < 10; i++) {
                clock.advance(Duration.ofSeconds(1));
                c.recordIfAbsent("p-" + i, clock.instant());
            }
            c.sweepNow();
            Set<String> remaining = new HashSet<>();
            for (int i = 0; i < 10; i++) {
                if (!c.recordIfAbsent("p-" + i, clock.instant())) {
                    remaining.add("p-" + i);
                }
            }
            assertThat(remaining).hasSize(3);
            assertThat(remaining).containsExactlyInAnyOrder("p-7", "p-8", "p-9");
        } finally {
            c.shutdown();
        }
    }
}
