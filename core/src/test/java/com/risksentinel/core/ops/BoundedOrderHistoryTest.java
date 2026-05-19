package com.risksentinel.core.ops;

import com.risksentinel.core.broker.Order;
import com.risksentinel.core.broker.OrderStatus;
import com.risksentinel.core.domain.Side;
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

class BoundedOrderHistoryTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;
        TestClock(Instant initial) { this.now = new AtomicReference<>(initial); }
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private Order orderAt(String proposalId, Instant at) {
        return new Order(
                "ord-" + proposalId, proposalId, "port-1", "AAPL", Side.BUY,
                100L, 150.0, OrderStatus.NEW, at, at);
    }

    private BoundedOrderHistory history(Duration ttl, int maxSize, TestClock clock) {
        return new BoundedOrderHistory(ttl, maxSize, Duration.ofHours(1), clock);
    }

    @Test
    void shouldReject_whenTtlNotPositive() {
        assertThatThrownBy(() -> new BoundedOrderHistory(
                Duration.ZERO, 100, Duration.ofSeconds(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenMaxSizeNotPositive() {
        assertThatThrownBy(() -> new BoundedOrderHistory(
                Duration.ofSeconds(1), -1, Duration.ofSeconds(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldStoreAndRetrieveOrder() {
        TestClock clock = new TestClock(T0);
        BoundedOrderHistory h = history(Duration.ofHours(1), 100, clock);
        try {
            Order created = h.computeIfAbsent("p-1", pid -> orderAt(pid, T0));

            assertThat(created.proposalId()).isEqualTo("p-1");
            assertThat(h.get("p-1")).isPresent()
                    .get().isEqualTo(created);
            assertThat(h.size()).isEqualTo(1);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void shouldReturnEmpty_forUnknownProposalId() {
        TestClock clock = new TestClock(T0);
        BoundedOrderHistory h = history(Duration.ofHours(1), 100, clock);
        try {
            assertThat(h.get("nope")).isEmpty();
        } finally {
            h.shutdown();
        }
    }

    @Test
    void shouldBeIdempotent_oncomputeIfAbsentSameKey() {
        TestClock clock = new TestClock(T0);
        BoundedOrderHistory h = history(Duration.ofHours(1), 100, clock);
        try {
            Order first = h.computeIfAbsent("p-1", pid -> orderAt(pid, T0));
            Order second = h.computeIfAbsent("p-1", pid -> orderAt(pid, T0.plusSeconds(60)));

            assertThat(first).isSameAs(second);
            assertThat(h.size()).isEqualTo(1);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void shouldRetainRecentOrders_andEvictOld() {
        TestClock clock = new TestClock(T0);
        BoundedOrderHistory h = history(Duration.ofMinutes(5), 1000, clock);
        try {
            h.computeIfAbsent("p-old", pid -> orderAt(pid, T0));
            clock.advance(Duration.ofMinutes(6));
            h.computeIfAbsent("p-new", pid -> orderAt(pid, clock.instant()));

            int removed = h.sweepNow();

            assertThat(removed).isEqualTo(1);
            assertThat(h.get("p-old")).isEmpty();
            assertThat(h.get("p-new")).isPresent();
        } finally {
            h.shutdown();
        }
    }

    @Test
    void shouldRefreshRetention_onCompute() {
        TestClock clock = new TestClock(T0);
        BoundedOrderHistory h = history(Duration.ofMinutes(5), 1000, clock);
        try {
            h.computeIfAbsent("p-1", pid -> orderAt(pid, T0));
            clock.advance(Duration.ofMinutes(4));
            // Transition: lastUpdatedAt becomes the new "now", refreshing retention.
            h.compute("p-1", (pid, existing) ->
                    existing.withStatus(OrderStatus.FILLED, clock.instant()));
            clock.advance(Duration.ofMinutes(3));

            int removed = h.sweepNow();

            assertThat(removed).isZero();
            assertThat(h.get("p-1"))
                    .isPresent()
                    .get()
                    .matches(o -> o.status() == OrderStatus.FILLED);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void shouldEvictLeastRecentlyUpdated_whenSizeExceedsMax() {
        TestClock clock = new TestClock(T0);
        BoundedOrderHistory h = history(Duration.ofDays(1), 3, clock);
        try {
            h.computeIfAbsent("p-1", pid -> orderAt(pid, T0));
            clock.advance(Duration.ofSeconds(1));
            h.computeIfAbsent("p-2", pid -> orderAt(pid, clock.instant()));
            clock.advance(Duration.ofSeconds(1));
            h.computeIfAbsent("p-3", pid -> orderAt(pid, clock.instant()));
            clock.advance(Duration.ofSeconds(1));
            h.computeIfAbsent("p-4", pid -> orderAt(pid, clock.instant()));
            clock.advance(Duration.ofSeconds(1));
            h.computeIfAbsent("p-5", pid -> orderAt(pid, clock.instant()));

            int removed = h.sweepNow();

            assertThat(h.size()).isEqualTo(3);
            assertThat(removed).isEqualTo(2);
            assertThat(h.get("p-1")).isEmpty();
            assertThat(h.get("p-2")).isEmpty();
            assertThat(h.get("p-3")).isPresent();
            assertThat(h.get("p-4")).isPresent();
            assertThat(h.get("p-5")).isPresent();
        } finally {
            h.shutdown();
        }
    }

    @Test
    void shouldStoreConcurrently_underBurst() throws InterruptedException {
        TestClock clock = new TestClock(T0);
        BoundedOrderHistory h = history(Duration.ofDays(1), 100_000, clock);
        try {
            int threads = 8;
            int perThread = 125;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger created = new AtomicInteger();
            try {
                for (int t = 0; t < threads; t++) {
                    final int threadId = t;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                String pid = "t" + threadId + "-i" + i;
                                Order before = h.get(pid).orElse(null);
                                h.computeIfAbsent(pid, p -> orderAt(p, T0));
                                if (before == null) {
                                    created.incrementAndGet();
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
            assertThat(h.size()).isEqualTo(threads * perThread);
            assertThat(created.get()).isEqualTo(threads * perThread);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void shouldRemainAtomic_underContendedSameProposalId() throws InterruptedException {
        TestClock clock = new TestClock(T0);
        BoundedOrderHistory h = history(Duration.ofDays(1), 1000, clock);
        try {
            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int trial = 0; trial < 20; trial++) {
                    CountDownLatch start = new CountDownLatch(1);
                    CountDownLatch done = new CountDownLatch(threads);
                    AtomicInteger creations = new AtomicInteger();
                    String id = "race-" + trial;
                    Set<Order> distinctResults = java.util.concurrent.ConcurrentHashMap.newKeySet();

                    for (int t = 0; t < threads; t++) {
                        pool.submit(() -> {
                            try {
                                start.await();
                                Order result = h.computeIfAbsent(id, pid -> {
                                    creations.incrementAndGet();
                                    return orderAt(pid, T0);
                                });
                                distinctResults.add(result);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                done.countDown();
                            }
                        });
                    }
                    start.countDown();
                    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(creations.get())
                            .as("trial %d: mapping function must run exactly once", trial)
                            .isEqualTo(1);
                    assertThat(distinctResults)
                            .as("trial %d: every thread must see the same Order instance", trial)
                            .hasSize(1);
                }
            } finally {
                pool.shutdownNow();
            }
        } finally {
            h.shutdown();
        }
    }

    @Test
    void shouldNotRaceWithSweep_duringConcurrentCompute() throws InterruptedException {
        TestClock clock = new TestClock(T0);
        BoundedOrderHistory h = history(Duration.ofMillis(1), 1000, clock);
        try {
            for (int i = 0; i < 100; i++) {
                h.computeIfAbsent("p-" + i, pid -> orderAt(pid, T0));
            }
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch done = new CountDownLatch(4);
            try {
                pool.submit(() -> {
                    try {
                        clock.advance(Duration.ofSeconds(1));
                        for (int i = 0; i < 50; i++) {
                            h.sweepNow();
                        }
                    } finally {
                        done.countDown();
                    }
                });
                for (int w = 0; w < 3; w++) {
                    pool.submit(() -> {
                        try {
                            for (int i = 0; i < 200; i++) {
                                String pid = "p-" + (i % 100);
                                h.compute(pid, (k, existing) ->
                                        existing == null
                                                ? orderAt(k, clock.instant())
                                                : existing.withStatus(OrderStatus.FILLED, clock.instant()));
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }
            // No exceptions raised; map remains queryable.
            assertThat(h.size()).isBetween(0, 100);
            Set<String> aliveBefore = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                h.get("p-" + i).ifPresent(o -> aliveBefore.add(o.proposalId()));
            }
            assertThat(aliveBefore.size()).isEqualTo(h.size());
        } finally {
            h.shutdown();
        }
    }
}
