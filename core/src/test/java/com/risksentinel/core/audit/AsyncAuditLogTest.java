package com.risksentinel.core.audit;

import com.risksentinel.core.domain.Side;
import com.risksentinel.core.ops.Counter;
import com.risksentinel.core.ops.MetricsRegistry;
import com.risksentinel.core.ops.NoopMetricsRegistry;
import com.risksentinel.core.ops.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncAuditLogTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    private DecisionRecord accept(String pid) {
        return new DecisionRecord(
                pid, "port-1", "AAPL", Side.BUY,
                100L, 150.0, "snap-x",
                DecisionType.ACCEPT, null, "[]", T0);
    }

    /** In-memory AuditLog stand-in for fast, deterministic tests. */
    private static class InMemoryAuditLog implements AuditLog {
        final List<DecisionRecord> records = new ArrayList<>();
        @Override public synchronized void record(DecisionRecord r) { records.add(r); }
        @Override public synchronized Optional<DecisionRecord> findByProposalId(String pid) {
            return records.stream().filter(r -> r.proposalId().equals(pid)).findFirst();
        }
        @Override public synchronized List<DecisionRecord> findByPortfolio(String p, int l) { return List.of(); }
        @Override public synchronized long count() { return records.size(); }
        @Override public void close() {}
    }

    private boolean waitFor(java.util.function.BooleanSupplier cond, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    @Test
    void shouldRecordAsynchronously_andReachDelegate() throws InterruptedException {
        InMemoryAuditLog delegate = new InMemoryAuditLog();
        MetricsRegistry m = new NoopMetricsRegistry();
        try (AuditLog async = new AsyncAuditLog(
                delegate, 64, m.counter("audit_dropped_total", Tags.empty()))) {
            async.record(accept("p-1"));
            async.record(accept("p-2"));
            assertThat(waitFor(() -> delegate.count() == 2, Duration.ofSeconds(2))).isTrue();
        }
    }

    @Test
    void shouldIncrementDroppedCounter_whenQueueSaturated() throws InterruptedException {
        // Block the delegate so the writer can't drain.
        CountDownLatch unblock = new CountDownLatch(1);
        InMemoryAuditLog delegate = new InMemoryAuditLog() {
            @Override public synchronized void record(DecisionRecord r) {
                try { unblock.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                super.record(r);
            }
        };
        MetricsRegistry m = new NoopMetricsRegistry();
        Counter dropped = m.counter("audit_dropped_total", Tags.empty());

        AsyncAuditLog async = new AsyncAuditLog(delegate, 4, dropped);
        try {
            // Writer takes one and parks on unblock; queue capacity is 4. After it
            // pulls one we can offer 4 more before the queue is full.
            for (int i = 0; i < 50; i++) {
                async.record(accept("p-" + i));
            }
            assertThat(dropped.count()).isGreaterThan(0L);
        } finally {
            unblock.countDown();
            async.close();
        }
    }

    @Test
    void shouldDrainPendingWrites_onClose() throws InterruptedException {
        InMemoryAuditLog delegate = new InMemoryAuditLog();
        MetricsRegistry m = new NoopMetricsRegistry();
        AsyncAuditLog async = new AsyncAuditLog(
                delegate, 128, m.counter("audit_dropped_total", Tags.empty()));

        for (int i = 0; i < 100; i++) {
            async.record(accept("p-" + i));
        }
        async.close();
        assertThat(delegate.count()).isEqualTo(100L);
    }

    @Test
    void shouldRecordConcurrently_fromManyProducers() throws InterruptedException {
        InMemoryAuditLog delegate = new InMemoryAuditLog();
        MetricsRegistry m = new NoopMetricsRegistry();
        AsyncAuditLog async = new AsyncAuditLog(
                delegate, 4096, m.counter("audit_dropped_total", Tags.empty()));

        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            async.record(accept("t" + tid + "-i" + i));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        async.close();
        assertThat(delegate.count()).isEqualTo((long) threads * perThread);
    }

    @Test
    void shouldSurviveDelegateFailure_andContinueProcessing() throws InterruptedException {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        InMemoryAuditLog delegate = new InMemoryAuditLog() {
            int calls = 0;
            @Override public synchronized void record(DecisionRecord r) {
                calls++;
                if (calls == 1) throw new RuntimeException("simulated DB failure");
                super.record(r);
            }
        };
        MetricsRegistry m = new NoopMetricsRegistry();
        try (AsyncAuditLog async = new AsyncAuditLog(
                delegate, 64, m.counter("audit_dropped_total", Tags.empty()))) {
            async.record(accept("p-fail"));
            async.record(accept("p-ok-1"));
            async.record(accept("p-ok-2"));
            assertThat(waitFor(() -> delegate.count() == 2, Duration.ofSeconds(2))).isTrue();
        }
    }

    @Test
    void shouldRoundTrip_throughFullSqliteStack(@TempDir Path dir) throws InterruptedException {
        Path db = dir.resolve("audit.db");
        MetricsRegistry m = new NoopMetricsRegistry();
        try (SqliteAuditLog sqlite = new SqliteAuditLog(db);
             AsyncAuditLog async = new AsyncAuditLog(
                     sqlite, 64, m.counter("audit_dropped_total", Tags.empty()))) {
            String pid = UUID.randomUUID().toString();
            async.record(accept(pid));

            assertThat(waitFor(() -> async.findByProposalId(pid).isPresent(), Duration.ofSeconds(2)))
                    .isTrue();
        }
    }
}
