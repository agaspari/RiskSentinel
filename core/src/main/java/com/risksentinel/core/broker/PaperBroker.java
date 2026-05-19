package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.BrokerSink;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process simulated broker. Accepts gateway-approved {@link TradeProposal}s,
 * delegates fill timing to a {@link FillModel}, and emits the resulting
 * {@link FillEvent}s to a {@link FillSink} on its own executor.
 *
 * <p>Concurrency contract:
 * <ul>
 *   <li>{@link #submit(TradeProposal)} is non-blocking. It performs
 *       order-state mutation via {@link ConcurrentHashMap#computeIfAbsent}
 *       (per-key atomic) and schedules simulation on the broker executor.</li>
 *   <li>Order-state transitions use {@link ConcurrentHashMap#compute}, which
 *       serializes per-key writers without any explicit lock.</li>
 *   <li>{@link #orderForProposal(String)} is a wait-free read.</li>
 *   <li>The broker holds no monitors. Callers must not hold ingestor or
 *       gateway locks across {@link #submit(TradeProposal)} — but neither path
 *       does that today.</li>
 * </ul>
 *
 * <p>Backpressure is loud: the default executor uses a bounded queue and
 * {@link ThreadPoolExecutor.AbortPolicy}. A saturated broker raises
 * {@link RejectedExecutionException} from {@link #submit(TradeProposal)} after
 * marking the in-flight order {@link OrderStatus#REJECTED}.
 */
public final class PaperBroker implements BrokerSink {

    private final Map<String, Instrument> instrumentRegistry;
    private final FillModel fillModel;
    private final FillSink fillSink;
    private final ExecutorService executor;
    private final Clock clock;

    private final ConcurrentHashMap<String, Order> ordersByProposalId = new ConcurrentHashMap<>();
    private final AtomicLong fillIdSeq = new AtomicLong();
    private final AtomicLong orderIdSeq = new AtomicLong();
    private final LongAdder submittedCount = new LongAdder();
    private final LongAdder filledCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();

    public PaperBroker(
            Map<String, Instrument> instrumentRegistry,
            FillModel fillModel,
            FillSink fillSink,
            ExecutorService executor,
            Clock clock) {
        this.instrumentRegistry = Objects.requireNonNull(instrumentRegistry, "instrumentRegistry");
        this.fillModel = Objects.requireNonNull(fillModel, "fillModel");
        this.fillSink = Objects.requireNonNull(fillSink, "fillSink");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Default platform-thread executor: bounded queue, AbortPolicy, named threads.
     * Tests should prefer a deterministic substitute (e.g. {@code Runnable::run}).
     */
    public static ExecutorService defaultExecutor() {
        ThreadFactory factory = Thread.ofPlatform().name("paper-broker-", 0).factory();
        return new ThreadPoolExecutor(
                2, 4,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1024),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void submit(TradeProposal acceptedProposal) {
        Objects.requireNonNull(acceptedProposal, "acceptedProposal");

        Instant now = clock.instant();
        boolean[] created = {false};
        ordersByProposalId.computeIfAbsent(
                acceptedProposal.proposalId(),
                pid -> {
                    created[0] = true;
                    return new Order(
                            "ord-" + orderIdSeq.incrementAndGet(),
                            pid,
                            acceptedProposal.portfolioId(),
                            acceptedProposal.symbol(),
                            acceptedProposal.side(),
                            acceptedProposal.quantity(),
                            acceptedProposal.limitPrice(),
                            OrderStatus.NEW,
                            now, now);
                });

        if (!created[0]) {
            return;
        }
        submittedCount.increment();

        try {
            executor.execute(() -> simulate(acceptedProposal.proposalId()));
        } catch (RejectedExecutionException ree) {
            ordersByProposalId.compute(acceptedProposal.proposalId(), (pid, existing) ->
                    existing == null ? null : existing.withStatus(OrderStatus.REJECTED, clock.instant()));
            rejectedCount.increment();
            throw ree;
        }
    }

    private void simulate(String proposalId) {
        Order order = ordersByProposalId.get(proposalId);
        if (order == null || order.status() != OrderStatus.NEW) {
            return;
        }

        Instrument instrument = instrumentRegistry.get(order.symbol());
        Instant now = clock.instant();

        if (instrument == null) {
            ordersByProposalId.compute(proposalId, (pid, existing) ->
                    existing == null ? null : existing.withStatus(OrderStatus.REJECTED, now));
            rejectedCount.increment();
            return;
        }

        Optional<FillEvent> maybeFill = fillModel.simulate(order, instrument, now, fillIdSeq::incrementAndGet);
        if (maybeFill.isEmpty()) {
            return;
        }

        FillEvent fill = maybeFill.get();
        Order updated = ordersByProposalId.compute(proposalId, (pid, existing) -> {
            if (existing == null || existing.status() != OrderStatus.NEW) {
                return existing;
            }
            return existing.withStatus(OrderStatus.FILLED, fill.filledAt());
        });

        if (updated == null || updated.status() != OrderStatus.FILLED) {
            return;
        }
        filledCount.increment();
        fillSink.onFill(fill);
    }

    public Optional<Order> orderForProposal(String proposalId) {
        return Optional.ofNullable(ordersByProposalId.get(proposalId));
    }

    public int orderCount() {
        return ordersByProposalId.size();
    }

    public long submittedCount() {
        return submittedCount.sum();
    }

    public long filledCount() {
        return filledCount.sum();
    }

    public long rejectedCount() {
        return rejectedCount.sum();
    }
}
