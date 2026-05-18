package com.risksentinel.core.gateway;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayStateTest {

    @Test
    void shouldDefaultKillSwitchToDisengaged() {
        assertThat(new GatewayState().isKillSwitchEngaged()).isFalse();
    }

    @Test
    void shouldEngageAndDisengageKillSwitch() {
        GatewayState state = new GatewayState();
        state.engageKillSwitch();
        assertThat(state.isKillSwitchEngaged()).isTrue();
        state.disengageKillSwitch();
        assertThat(state.isKillSwitchEngaged()).isFalse();
    }

    @Test
    void shouldReturnTrue_onFirstRecord() {
        GatewayState state = new GatewayState();
        assertThat(state.recordProposalIfAbsent("p-1", Instant.now())).isTrue();
    }

    @Test
    void shouldReturnFalse_onReplay() {
        GatewayState state = new GatewayState();
        state.recordProposalIfAbsent("p-1", Instant.now());
        assertThat(state.recordProposalIfAbsent("p-1", Instant.now())).isFalse();
    }

    @Test
    void shouldTrackDistinctProposalsIndependently() {
        GatewayState state = new GatewayState();
        state.recordProposalIfAbsent("p-1", Instant.now());
        state.recordProposalIfAbsent("p-2", Instant.now());
        assertThat(state.seenProposalCount()).isEqualTo(2);
    }

    /**
     * 32 threads race to record the same proposalId. Exactly one must observe
     * {@code true}; all 31 others must observe {@code false}. This proves
     * {@code recordProposalIfAbsent} is atomic under contention.
     */
    @Test
    void shouldBeAtomic_underConcurrentSubmissionOfSameProposalId() throws InterruptedException {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int trial = 0; trial < 25; trial++) {
                GatewayState state = new GatewayState();
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(threads);
                AtomicInteger winners = new AtomicInteger();

                String contendedId = "race-" + trial;

                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            if (state.recordProposalIfAbsent(contendedId, Instant.now())) {
                                winners.incrementAndGet();
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
                assertThat(winners.get())
                        .as("trial %d: exactly one thread must win the CAS", trial)
                        .isEqualTo(1);
                assertThat(state.seenProposalCount()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
