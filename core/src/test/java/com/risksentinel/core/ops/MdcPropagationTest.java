package com.risksentinel.core.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcPropagationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldCarryMdc_acrossThreadBoundary() throws InterruptedException {
        MDC.put("portfolioId", "p-1");
        MDC.put("proposalId", "x-1");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> observedPortfolio = new AtomicReference<>();
        AtomicReference<String> observedProposal = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try {
            pool.execute(MdcPropagation.wrap(() -> {
                observedPortfolio.set(MDC.get("portfolioId"));
                observedProposal.set(MDC.get("proposalId"));
                done.countDown();
            }));
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(observedPortfolio.get()).isEqualTo("p-1");
        assertThat(observedProposal.get()).isEqualTo("x-1");
    }

    @Test
    void shouldNotLeakMdc_betweenSequentialTasksOnSameThread() throws InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> secondTaskObserved = new AtomicReference<>("present-not-set");
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        try {
            MDC.put("portfolioId", "p-first");
            pool.execute(MdcPropagation.wrap(() -> {
                firstDone.countDown();
            }));
            assertThat(firstDone.await(2, TimeUnit.SECONDS)).isTrue();
            MDC.clear();

            pool.execute(MdcPropagation.wrap(() -> {
                secondTaskObserved.set(MDC.get("portfolioId"));
                secondDone.countDown();
            }));
            assertThat(secondDone.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(secondTaskObserved.get())
                .as("second task must not inherit first task's MDC")
                .isNull();
    }

    @Test
    void shouldRestoreWorkerThreadMdc_afterTaskCompletes() throws InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch seedDone = new CountDownLatch(1);
            pool.execute(() -> {
                MDC.put("workerLocal", "stay");
                seedDone.countDown();
            });
            assertThat(seedDone.await(2, TimeUnit.SECONDS)).isTrue();

            MDC.put("portfolioId", "p-1");
            CountDownLatch wrappedDone = new CountDownLatch(1);
            AtomicReference<String> insideWrapped = new AtomicReference<>();
            pool.execute(MdcPropagation.wrap(() -> {
                insideWrapped.set(MDC.get("portfolioId"));
                wrappedDone.countDown();
            }));
            assertThat(wrappedDone.await(2, TimeUnit.SECONDS)).isTrue();
            MDC.clear();

            CountDownLatch checkDone = new CountDownLatch(1);
            AtomicReference<String> workerStateAfter = new AtomicReference<>();
            pool.execute(() -> {
                workerStateAfter.set(MDC.get("workerLocal"));
                checkDone.countDown();
            });
            assertThat(checkDone.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(insideWrapped.get()).isEqualTo("p-1");
            assertThat(workerStateAfter.get())
                    .as("worker's pre-existing MDC must survive the wrapped task")
                    .isEqualTo("stay");
        } finally {
            pool.shutdownNow();
        }
    }
}
