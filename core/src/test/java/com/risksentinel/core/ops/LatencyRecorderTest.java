package com.risksentinel.core.ops;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatencyRecorderTest {

    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    @Test
    void shouldRejectConstruction_whenHighestTrackableTooLow() {
        assertThatThrownBy(() -> LatencyRecorder.active("x", 0L, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectConstruction_whenSignificantDigitsOutOfRange() {
        assertThatThrownBy(() -> LatencyRecorder.active("x", ONE_SECOND_NANOS, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LatencyRecorder.active("x", ONE_SECOND_NANOS, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRecordNanosAndExposeSnapshot() {
        LatencyRecorder r = LatencyRecorder.active("probe", ONE_SECOND_NANOS, 3);
        r.recordNanos(1_000L);
        r.recordNanos(2_000L);
        r.recordNanos(3_000L);

        LatencySnapshot snap = r.snapshot();

        assertThat(snap.probe()).isEqualTo("probe");
        assertThat(snap.count()).isEqualTo(3);
        assertThat(snap.maxNanos()).isGreaterThanOrEqualTo(3_000L);
    }

    @Test
    void shouldAccumulateAcrossSnapshots_cumulativeSemantics() {
        LatencyRecorder r = LatencyRecorder.active("probe", ONE_SECOND_NANOS, 3);
        for (int i = 0; i < 10; i++) {
            r.recordNanos(1_000L);
        }
        LatencySnapshot first = r.snapshot();
        assertThat(first.count()).isEqualTo(10);

        for (int i = 0; i < 5; i++) {
            r.recordNanos(2_000L);
        }
        LatencySnapshot second = r.snapshot();
        assertThat(second.count()).isEqualTo(15);
    }

    @Test
    void shouldClampValuesAboveHighestTrackable() {
        // Verify we don't blow up on values above the cap. HdrHistogram with
        // 3 significant digits reports getMaxValue() at the bucket's upper bound,
        // which can slightly exceed the recorded value (~0.1% tolerance).
        long cap = 1_000_000L;
        LatencyRecorder r = LatencyRecorder.active("probe", cap, 3);
        r.recordNanos(cap * 100);

        LatencySnapshot snap = r.snapshot();
        assertThat(snap.count()).isEqualTo(1);
        assertThat(snap.maxNanos()).isLessThan(cap * 2);
    }

    @Test
    void shouldIgnoreNegativeValues() {
        LatencyRecorder r = LatencyRecorder.active("probe", ONE_SECOND_NANOS, 3);
        r.recordNanos(-1L);
        r.recordNanos(-1000L);

        LatencySnapshot snap = r.snapshot();
        assertThat(snap.count()).isZero();
    }

    @Test
    void shouldReportAccuratePercentiles_underUniformLoad() {
        // Uniform distribution from 1000 to 10000 nanos. p50 ≈ 5500, p99 ≈ 9910.
        LatencyRecorder r = LatencyRecorder.active("probe", ONE_SECOND_NANOS, 3);
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            long value = 1000L + (i % 9001);
            r.recordNanos(value);
        }

        LatencySnapshot snap = r.snapshot();

        assertThat(snap.count()).isEqualTo(n);
        assertThat(snap.p50Nanos()).isBetween(5_000L, 6_000L);
        assertThat(snap.p99Nanos()).isBetween(9_500L, 10_500L);
        assertThat(snap.maxNanos()).isBetween(9_900L, 10_100L);
    }

    @Test
    void shouldBeSafeUnderConcurrentRecording() throws InterruptedException {
        LatencyRecorder r = LatencyRecorder.active("probe", ONE_SECOND_NANOS, 3);

        int writers = 16;
        int perWriter = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(writers);
        AtomicBoolean stopReader = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong readerSnapshots = new AtomicLong();

        try {
            for (int w = 0; w < writers; w++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perWriter; i++) {
                            r.recordNanos(1_000L + (i % 100));
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        writersDone.countDown();
                    }
                });
            }
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stopReader.get()) {
                        r.snapshot();
                        readerSnapshots.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            start.countDown();
            assertThat(writersDone.await(30, TimeUnit.SECONDS)).isTrue();
            stopReader.set(true);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failure.get()).as("no exception in writer or reader").isNull();
        assertThat(readerSnapshots.get()).as("reader made progress").isGreaterThan(0);
        // Final snapshot must observe every recorded sample.
        LatencySnapshot finalSnap = r.snapshot();
        assertThat(finalSnap.count()).isEqualTo((long) writers * perWriter);
    }

    @Test
    void noopShouldReturnZeroSnapshot() {
        LatencyRecorder r = LatencyRecorder.noop("probe");
        r.recordNanos(1_000L);
        r.recordNanos(2_000L);

        LatencySnapshot snap = r.snapshot();
        assertThat(snap.probe()).isEqualTo("probe");
        assertThat(snap.count()).isZero();
        assertThat(snap.p99Nanos()).isZero();
        assertThat(snap.maxNanos()).isZero();
    }
}
