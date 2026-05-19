package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaperBrokerCountersTest {

    private static final Instant T0 = Instant.parse("2026-05-18T12:00:00Z");
    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Map<String, Instrument> REGISTRY = Map.of("AAPL", AAPL);

    private static ExecutorService sync() {
        return new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    private TradeProposal proposal(String id, String symbol) {
        return new TradeProposal(
                id, "port-1", symbol, Side.BUY, 100L, 150.0, 150.0,
                "t", 0.9, "snap-x", T0);
    }

    @Test
    void shouldIncrementCounters_underNormalFillFlow() {
        PaperBroker broker = new PaperBroker(
                REGISTRY, new InstantFillModel(), event -> {}, sync(),
                Clock.fixed(T0, ZoneOffset.UTC));

        broker.submit(proposal("p-1", "AAPL"));
        broker.submit(proposal("p-2", "AAPL"));
        broker.submit(proposal("p-3", "AAPL"));

        assertThat(broker.submittedCount()).isEqualTo(3);
        assertThat(broker.filledCount()).isEqualTo(3);
        assertThat(broker.rejectedCount()).isEqualTo(0);
    }

    @Test
    void shouldIncrementRejectedCount_whenInstrumentUnknown() {
        PaperBroker broker = new PaperBroker(
                REGISTRY, new InstantFillModel(), event -> {}, sync(),
                Clock.fixed(T0, ZoneOffset.UTC));

        broker.submit(proposal("p-1", "ZZZZ"));

        assertThat(broker.submittedCount()).isEqualTo(1);
        assertThat(broker.filledCount()).isEqualTo(0);
        assertThat(broker.rejectedCount()).isEqualTo(1);
    }

    @Test
    void shouldNotDoubleCountSubmitted_forDuplicateProposalId() {
        PaperBroker broker = new PaperBroker(
                REGISTRY, new InstantFillModel(), event -> {}, sync(),
                Clock.fixed(T0, ZoneOffset.UTC));

        TradeProposal p = proposal("p-1", "AAPL");
        broker.submit(p);
        broker.submit(p);
        broker.submit(p);

        assertThat(broker.submittedCount()).isEqualTo(1);
        assertThat(broker.filledCount()).isEqualTo(1);
    }

    @Test
    void shouldIncrementRejectedCount_whenExecutorRejectsTask() {
        ExecutorService rejecting = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
            @Override public void execute(Runnable command) {
                throw new RejectedExecutionException("queue full");
            }
        };
        PaperBroker broker = new PaperBroker(
                REGISTRY, new InstantFillModel(), event -> {}, rejecting,
                Clock.fixed(T0, ZoneOffset.UTC));

        assertThatThrownBy(() -> broker.submit(proposal("p-1", "AAPL")))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(broker.submittedCount()).isEqualTo(1);
        assertThat(broker.filledCount()).isEqualTo(0);
        assertThat(broker.rejectedCount()).isEqualTo(1);
    }
}
