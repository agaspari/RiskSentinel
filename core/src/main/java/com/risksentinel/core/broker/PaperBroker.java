package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.BrokerSink;
import com.risksentinel.core.ops.BoundedOrderHistory;
import com.risksentinel.core.ops.Counter;
import com.risksentinel.core.ops.LatencyRecorder;
import com.risksentinel.core.ops.MdcPropagation;
import com.risksentinel.core.ops.MdcScope;
import com.risksentinel.core.ops.MetricsRegistry;
import com.risksentinel.core.ops.NoopMetricsRegistry;
import com.risksentinel.core.ops.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process simulated broker. Accepts gateway-approved {@link TradeProposal}s,
 * delegates fill timing to a {@link FillModel}, and emits the resulting
 * {@link FillEvent}s to a {@link FillSink} on its own executor.
 *
 * <p>Concurrency contract:
 * <ul>
 *   <li>{@link #submit(TradeProposal)} is non-blocking. Order creation is
 *       atomic per key via {@link BoundedOrderHistory#computeIfAbsent}.</li>
 *   <li>Order-state transitions use {@link BoundedOrderHistory#compute}, which
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

    private static final Logger log = LoggerFactory.getLogger(PaperBroker.class);

    /** Default retention: 24h TTL, 100k entry cap, sweep every minute. */
    private static final Duration DEFAULT_HISTORY_TTL = Duration.ofHours(24);
    private static final int DEFAULT_HISTORY_MAX_SIZE = 100_000;
    private static final Duration DEFAULT_HISTORY_SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final Map<String, Instrument> instrumentRegistry;
    private final FillModel fillModel;
    private final FillSink fillSink;
    private final ExecutorService executor;
    private final Clock clock;

    private final BoundedOrderHistory orders;
    private final AtomicLong fillIdSeq = new AtomicLong();
    private final AtomicLong orderIdSeq = new AtomicLong();
    private final Counter submitted;
    private final Counter filled;
    private final Counter rejected;
    private final LatencyRecorder submitLatency;
    private final LatencyRecorder endToEndLatency;

    public PaperBroker(
            Map<String, Instrument> instrumentRegistry,
            FillModel fillModel,
            FillSink fillSink,
            ExecutorService executor,
            Clock clock) {
        this(instrumentRegistry, fillModel, fillSink, executor, clock,
                new BoundedOrderHistory(
                        DEFAULT_HISTORY_TTL,
                        DEFAULT_HISTORY_MAX_SIZE,
                        DEFAULT_HISTORY_SWEEP_INTERVAL,
                        clock),
                LatencyRecorder.noop("paper-broker-submit-nanos"),
                LatencyRecorder.noop("paper-broker-end-to-end-nanos"),
                new NoopMetricsRegistry());
    }

    /** Constructor with explicit history — useful for tests that want fast sweeps. */
    public PaperBroker(
            Map<String, Instrument> instrumentRegistry,
            FillModel fillModel,
            FillSink fillSink,
            ExecutorService executor,
            Clock clock,
            BoundedOrderHistory orders) {
        this(instrumentRegistry, fillModel, fillSink, executor, clock, orders,
                LatencyRecorder.noop("paper-broker-submit-nanos"),
                LatencyRecorder.noop("paper-broker-end-to-end-nanos"),
                new NoopMetricsRegistry());
    }

    /** Full constructor — explicit history, latency recorders, and metrics registry. */
    public PaperBroker(
            Map<String, Instrument> instrumentRegistry,
            FillModel fillModel,
            FillSink fillSink,
            ExecutorService executor,
            Clock clock,
            BoundedOrderHistory orders,
            LatencyRecorder submitLatency,
            LatencyRecorder endToEndLatency,
            MetricsRegistry metrics) {
        this.instrumentRegistry = Objects.requireNonNull(instrumentRegistry, "instrumentRegistry");
        this.fillModel = Objects.requireNonNull(fillModel, "fillModel");
        this.fillSink = Objects.requireNonNull(fillSink, "fillSink");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.submitLatency = Objects.requireNonNull(submitLatency, "submitLatency");
        this.endToEndLatency = Objects.requireNonNull(endToEndLatency, "endToEndLatency");
        Objects.requireNonNull(metrics, "metrics");
        this.submitted = metrics.counter("paper_broker_submitted_total", Tags.empty());
        this.filled = metrics.counter("paper_broker_filled_total", Tags.empty());
        this.rejected = metrics.counter("paper_broker_rejected_total", Tags.empty());
    }

    public LatencyRecorder submitLatency() {
        return submitLatency;
    }

    public LatencyRecorder endToEndLatency() {
        return endToEndLatency;
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
        long submitStart = System.nanoTime();
        try (MdcScope ignored = MdcScope.of(
                "portfolioId", acceptedProposal.portfolioId(),
                "proposalId", acceptedProposal.proposalId())) {
            submitInternal(acceptedProposal);
        } finally {
            submitLatency.recordNanos(System.nanoTime() - submitStart);
        }
    }

    private void submitInternal(TradeProposal acceptedProposal) {
        Instant now = clock.instant();
        boolean[] created = {false};
        orders.computeIfAbsent(
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
        submitted.increment();

        try {
            executor.execute(MdcPropagation.wrap(
                    () -> simulate(acceptedProposal.proposalId())));
        } catch (RejectedExecutionException ree) {
            orders.compute(acceptedProposal.proposalId(), (pid, existing) ->
                    existing == null ? null : existing.withStatus(OrderStatus.REJECTED, clock.instant()));
            rejected.increment();
            throw ree;
        }
    }

    private void simulate(String proposalId) {
        Order order = orders.get(proposalId).orElse(null);
        if (order == null || order.status() != OrderStatus.NEW) {
            return;
        }

        Instrument instrument = instrumentRegistry.get(order.symbol());
        Instant now = clock.instant();

        if (instrument == null) {
            orders.compute(proposalId, (pid, existing) ->
                    existing == null ? null : existing.withStatus(OrderStatus.REJECTED, now));
            rejected.increment();
            return;
        }

        Optional<FillEvent> maybeFill = fillModel.simulate(order, instrument, now, fillIdSeq::incrementAndGet);
        if (maybeFill.isEmpty()) {
            return;
        }

        FillEvent fill = maybeFill.get();
        Order updated = orders.compute(proposalId, (pid, existing) -> {
            if (existing == null || existing.status() != OrderStatus.NEW) {
                return existing;
            }
            return existing.withStatus(OrderStatus.FILLED, fill.filledAt());
        });

        if (updated == null || updated.status() != OrderStatus.FILLED) {
            return;
        }
        filled.increment();
        long e2eNanos = Duration.between(updated.submittedAt(), fill.filledAt()).toNanos();
        endToEndLatency.recordNanos(e2eNanos);
        log.debug("Order filled fillId={} orderId={}", fill.fillId(), fill.orderId());
        fillSink.onFill(fill);
    }

    public Optional<Order> orderForProposal(String proposalId) {
        return orders.get(proposalId);
    }

    public int orderCount() {
        return orders.size();
    }

    public long submittedCount() {
        return submitted.count();
    }

    public long filledCount() {
        return filled.count();
    }

    public long rejectedCount() {
        return rejected.count();
    }

    /** Releases the order-history sweep thread. */
    public void shutdown() {
        orders.shutdown();
    }
}
