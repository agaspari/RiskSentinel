package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PaperBrokerConcurrencyTest {

    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Map<String, Instrument> REGISTRY = Map.of("AAPL", AAPL);

    private TradeProposal proposal(String id) {
        return new TradeProposal(
                id, "port-1", "AAPL", Side.BUY, 1L, 150.0, 150.0,
                "t", 0.9, "snap-x", Instant.now());
    }

    /**
     * 1,000 distinct proposalIds, submitted by 8 producer threads in interleaved order.
     * Every proposal must produce exactly one fill; every fillId must be unique.
     */
    @Test
    void shouldProcessAllProposals_underBurst() throws InterruptedException {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2048),
                new ThreadPoolExecutor.CallerRunsPolicy());

        ConcurrentLinkedQueue<FillEvent> fills = new ConcurrentLinkedQueue<>();
        PaperBroker broker = new PaperBroker(
                REGISTRY, new InstantFillModel(), fills::add, executor, Clock.systemUTC());

        int total = 1_000;
        int producers = 8;
        int perProducer = total / producers;

        ExecutorService submitters = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);
        try {
            for (int p = 0; p < producers; p++) {
                final int producerId = p;
                submitters.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perProducer; i++) {
                            broker.submit(proposal("p-" + producerId + "-" + i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            submitters.shutdownNow();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(fills).hasSize(total);
        Set<Long> uniqueFillIds = new HashSet<>();
        for (FillEvent fe : fills) {
            uniqueFillIds.add(fe.fillId());
        }
        assertThat(uniqueFillIds).hasSize(total);
        assertThat(broker.orderCount()).isEqualTo(total);
    }

    /**
     * Same proposalId submitted concurrently by many threads — only one order created,
     * only one fill emitted.
     */
    @Test
    void shouldNotEmitDuplicateFills_underConcurrentSubmissionOfSameProposalId() throws InterruptedException {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256));
        ConcurrentLinkedQueue<FillEvent> fills = new ConcurrentLinkedQueue<>();
        PaperBroker broker = new PaperBroker(
                REGISTRY, new InstantFillModel(), fills::add, executor, Clock.systemUTC());

        int threads = 32;
        ExecutorService submitters = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        TradeProposal shared = proposal("shared");

        try {
            for (int t = 0; t < threads; t++) {
                submitters.submit(() -> {
                    try {
                        start.await();
                        broker.submit(shared);
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
            submitters.shutdownNow();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(fills).hasSize(1);
        assertThat(broker.orderCount()).isEqualTo(1);
    }

    /**
     * Slow sink should not cause fill loss — every submitted proposal must still
     * end up delivered exactly once. Sink sleeps to amplify any race that might
     * cause double-delivery or skipping.
     */
    @Test
    void shouldNotLoseFills_whenSinkIsSlow() throws InterruptedException {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(512),
                new ThreadPoolExecutor.CallerRunsPolicy());

        AtomicInteger sinkCallCount = new AtomicInteger();
        ConcurrentLinkedQueue<FillEvent> fills = new ConcurrentLinkedQueue<>();
        FillSink slowSink = event -> {
            sinkCallCount.incrementAndGet();
            try {
                Thread.sleep(1);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            fills.add(event);
        };
        PaperBroker broker = new PaperBroker(
                REGISTRY, new InstantFillModel(), slowSink, executor, Clock.systemUTC());

        int total = 200;
        for (int i = 0; i < total; i++) {
            broker.submit(proposal("p-" + i));
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(sinkCallCount.get()).isEqualTo(total);
        assertThat(fills).hasSize(total);
    }

    /**
     * AbortPolicy + tiny bounded queue + slow tasks → some submits must reject loudly.
     * The broker translates that rejection into an order marked REJECTED + a re-thrown
     * RejectedExecutionException. Documented backpressure behavior.
     */
    @Test
    void shouldRejectWithBackpressure_whenQueueFull() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());

        FillSink slowSink = event -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
        PaperBroker broker = new PaperBroker(
                REGISTRY, new InstantFillModel(), slowSink, executor, Clock.systemUTC());

        int rejected = 0;
        List<String> rejectedIds = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            try {
                broker.submit(proposal("p-" + i));
            } catch (RejectedExecutionException ree) {
                rejected++;
                rejectedIds.add("p-" + i);
            }
        }

        executor.shutdownNow();

        assertThat(rejected)
                .as("expected some submissions to be rejected by the bounded queue")
                .isGreaterThan(0);
        for (String pid : rejectedIds) {
            assertThat(broker.orderForProposal(pid).orElseThrow().status())
                    .as("rejected proposal %s should have REJECTED order state", pid)
                    .isEqualTo(OrderStatus.REJECTED);
        }
    }
}
