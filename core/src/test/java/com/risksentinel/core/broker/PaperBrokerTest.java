package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaperBrokerTest {

    private static final Instant T0 = Instant.parse("2026-05-18T12:00:00Z");
    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Map<String, Instrument> REGISTRY = Map.of("AAPL", AAPL);

    /** Synchronous executor: tasks run on the submitting thread. */
    private static ExecutorService synchronousExecutor() {
        return new java.util.concurrent.AbstractExecutorService() {
            private volatile boolean shutdown = false;
            @Override public void shutdown() { shutdown = true; }
            @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    private List<FillEvent> recordedFills;
    private FillSink sink;
    private PaperBroker broker;

    @BeforeEach
    void setUp() {
        recordedFills = new ArrayList<>();
        sink = recordedFills::add;
        broker = new PaperBroker(
                REGISTRY,
                new InstantFillModel(),
                sink,
                synchronousExecutor(),
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private TradeProposal proposal(String id, String symbol, Side side, long qty, double price) {
        return new TradeProposal(
                id, "port-1", symbol, side, qty, price, price,
                "t", 0.9, "snap-x", T0);
    }

    @Test
    void shouldEmitFillForAcceptedProposal() {
        broker.submit(proposal("p-1", "AAPL", Side.BUY, 100L, 150.0));

        assertThat(recordedFills).hasSize(1);
        FillEvent fe = recordedFills.get(0);
        assertThat(fe.proposalId()).isEqualTo("p-1");
        assertThat(fe.filledQuantity()).isEqualTo(100L);
        assertThat(fe.filledPrice()).isEqualTo(150.0);
    }

    @Test
    void shouldEmitFill_withFillIdMonotonicallyIncreasing() {
        broker.submit(proposal("p-1", "AAPL", Side.BUY, 1L, 150.0));
        broker.submit(proposal("p-2", "AAPL", Side.BUY, 1L, 150.0));
        broker.submit(proposal("p-3", "AAPL", Side.BUY, 1L, 150.0));

        assertThat(recordedFills).extracting(FillEvent::fillId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void shouldMarkOrderRejected_whenInstrumentUnknown() {
        broker.submit(proposal("p-1", "ZZZZ", Side.BUY, 100L, 150.0));

        assertThat(recordedFills).isEmpty();
        Order order = broker.orderForProposal("p-1").orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void shouldNotDoubleSubmit_forDuplicateProposalId() {
        TradeProposal p = proposal("p-1", "AAPL", Side.BUY, 100L, 150.0);

        broker.submit(p);
        broker.submit(p);

        assertThat(recordedFills).hasSize(1);
        assertThat(broker.orderCount()).isEqualTo(1);
    }

    @Test
    void shouldRecordOrderWithStatusFilled_afterSimulationCompletes() {
        broker.submit(proposal("p-1", "AAPL", Side.BUY, 100L, 150.0));

        Order order = broker.orderForProposal("p-1").orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.orderId()).startsWith("ord-");
        assertThat(order.proposalId()).isEqualTo("p-1");
        assertThat(order.lastUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void shouldReturnEmpty_whenOrderForUnknownProposalId() {
        assertThat(broker.orderForProposal("unknown")).isEmpty();
    }

    @Test
    void shouldRejectAndMarkOrder_whenExecutorSaturated() {
        ExecutorService rejecting = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) { return true; }
            @Override public void execute(Runnable command) {
                throw new RejectedExecutionException("queue full");
            }
        };
        PaperBroker pb = new PaperBroker(
                REGISTRY, new InstantFillModel(), sink, rejecting,
                Clock.fixed(T0, ZoneOffset.UTC));

        TradeProposal p = proposal("p-1", "AAPL", Side.BUY, 100L, 150.0);
        assertThatThrownBy(() -> pb.submit(p))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(recordedFills).isEmpty();
        assertThat(pb.orderForProposal("p-1").orElseThrow().status())
                .isEqualTo(OrderStatus.REJECTED);
    }
}
