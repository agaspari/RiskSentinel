package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.risksentinel.core.gateway.CheckTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyCheckTest {

    private final RiskCheck check = new IdempotencyCheck();

    @Test
    void shouldPass_whenProposalIdUnseen() {
        TradeProposal p = proposal("AAPL", Side.BUY, 10, 150.0);
        assertThat(check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()))).isEmpty();
    }

    @Test
    void shouldReject_whenProposalIdReplayed() {
        GatewayState state = new GatewayState();
        TradeProposal p = proposal("AAPL", Side.BUY, 10, 150.0);
        check.check(p, ctx(null, AAPL, defaultLimits(), state));

        var reasons = check.check(p, ctx(null, AAPL, defaultLimits(), state));
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0).code()).isEqualTo(RejectCode.DUPLICATE_PROPOSAL);
    }

    @Test
    void shouldBeAtomic_underConcurrentSubmissionOfSameProposalId() throws InterruptedException {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            GatewayState state = new GatewayState();
            TradeProposal contended = proposal("AAPL", Side.BUY, 10, 150.0);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger passes = new AtomicInteger();
            AtomicInteger duplicates = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        List<RejectReason> r = check.check(contended,
                                ctx(null, AAPL, defaultLimits(), state));
                        if (r.isEmpty()) {
                            passes.incrementAndGet();
                        } else if (r.get(0).code() == RejectCode.DUPLICATE_PROPOSAL) {
                            duplicates.incrementAndGet();
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
            assertThat(passes.get()).isEqualTo(1);
            assertThat(duplicates.get()).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }
}
