package com.risksentinel.eval.runner;

import com.risksentinel.core.broker.BarPriceFillModel;
import com.risksentinel.core.broker.FillEvent;
import com.risksentinel.core.broker.FillSink;
import com.risksentinel.core.broker.Order;
import com.risksentinel.core.broker.PaperBroker;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.Trade;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.ops.MutableClock;
import com.risksentinel.core.positions.ConcurrentPositionBook;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskEngine;
import com.risksentinel.core.risk.SimpleRiskEngine;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Wires the core components required for a backtest:
 * {@link ConcurrentPositionBook}, {@link ConcurrentRiskSnapshotCache},
 * {@link PreTradeGateway}, {@link PaperBroker}, a {@link MutableClock}, and
 * a {@link BarPriceFillModel} fed from a per-symbol "current close" map that
 * the runner updates each bar.
 *
 * <p>The broker runs on a {@link DirectExecutorService} so that
 * {@code broker.submit} is fully synchronous — by the time it returns, the
 * fill has been applied and the snapshot refreshed.
 */
public final class BacktestSystem {

    private final MutableClock clock;
    private final ConcurrentPositionBook positions;
    private final ConcurrentRiskSnapshotCache snapshots;
    private final Map<String, Instrument> instruments;
    private final GatewayState gatewayState;
    private final PreTradeGateway gateway;
    private final PaperBroker broker;
    private final RiskEngine engine;

    private final AtomicReference<Map<String, Double>> currentClosesRef =
            new AtomicReference<>(Map.of());
    private final AtomicLong tradeSeq = new AtomicLong(1L);
    private double cashFlow = 0.0; // single-threaded: only mutated under DirectExecutorService
    private volatile Consumer<FillEvent> fillObserver = e -> {};

    public BacktestSystem(
            Map<String, Instrument> instruments,
            GatewayLimits limits,
            Instant startAt) {
        Objects.requireNonNull(instruments, "instruments");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(startAt, "startAt");
        this.instruments = Map.copyOf(instruments);
        this.clock = new MutableClock(startAt);
        this.positions = new ConcurrentPositionBook();
        this.snapshots = new ConcurrentRiskSnapshotCache();
        this.gatewayState = new GatewayState();
        this.engine = new SimpleRiskEngine(clock);

        BarPriceFillModel fillModel = new BarPriceFillModel(currentClosesRef::get);
        BacktestFillSink sink = new BacktestFillSink();
        ExecutorService directExec = new DirectExecutorService();
        this.broker = new PaperBroker(this.instruments, fillModel, sink, directExec, clock);
        sink.attach(this);
        this.gateway = new PreTradeGateway(snapshots, this.instruments, limits, gatewayState, clock);
    }

    public MutableClock clock() { return clock; }
    public ConcurrentPositionBook positions() { return positions; }
    public ConcurrentRiskSnapshotCache snapshots() { return snapshots; }
    public PreTradeGateway gateway() { return gateway; }
    public PaperBroker broker() { return broker; }
    public GatewayState gatewayState() { return gatewayState; }
    public Map<String, Instrument> instruments() { return instruments; }
    public double cashFlow() { return cashFlow; }

    /**
     * Registers a callback invoked after every fill has been applied to the
     * position book and the snapshot refreshed. Useful for invariant checks
     * (property tests) that want to assert mid-run state.
     */
    public void setFillObserver(Consumer<FillEvent> observer) {
        this.fillObserver = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Updates the current-close map with the latest close for {@code symbol}.
     * Called by the runner once per bar before strategy invocation so the
     * {@link BarPriceFillModel} fills against the active bar.
     */
    public void updateCurrentClose(String symbol, double close) {
        currentClosesRef.updateAndGet(prev -> {
            Map<String, Double> next = new HashMap<>(prev);
            next.put(symbol, close);
            return Map.copyOf(next);
        });
    }

    void applyFill(Order order, FillEvent fill) {
        Trade trade = new Trade(
                tradeSeq.getAndIncrement(),
                order.portfolioId(),
                order.symbol(),
                order.side(),
                fill.filledQuantity(),
                fill.filledPrice(),
                fill.filledAt());
        positions.apply(trade);
        double flow = (trade.side() == Side.BUY ? -1.0 : 1.0) * trade.quantity() * trade.price();
        cashFlow += flow;
        RiskSnapshot snap = engine.compute(
                trade.portfolioId(),
                positions.getPositions(trade.portfolioId()),
                instruments);
        snapshots.updateSnapshots(Map.of(trade.portfolioId(), snap));
        fillObserver.accept(fill);
    }

    /**
     * Bridge between the broker's {@link FillSink} contract and
     * {@link BacktestSystem#applyFill}. Decoupled so the system's constructor
     * can wire the broker and the sink in either order — the sink resolves
     * the order via the broker after attachment.
     */
    private static final class BacktestFillSink implements FillSink {
        private BacktestSystem system;

        void attach(BacktestSystem s) {
            this.system = s;
        }

        @Override
        public void onFill(FillEvent event) {
            system.broker.orderForProposal(event.proposalId())
                    .ifPresent(order -> system.applyFill(order, event));
        }
    }
}
