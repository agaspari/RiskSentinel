package com.risksentinel.core;

import com.risksentinel.core.broker.FillEvent;
import com.risksentinel.core.broker.FillModel;
import com.risksentinel.core.broker.FillSink;
import com.risksentinel.core.broker.Order;
import com.risksentinel.core.broker.PaperBroker;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Trade;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.BrokerSink;
import com.risksentinel.core.gateway.GatewayDecision;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.ingest.TradeIngestor;
import com.risksentinel.core.positions.ConcurrentPositionBook;
import com.risksentinel.core.positions.PositionBook;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskEngine;
import com.risksentinel.core.risk.RiskSnapshotCache;
import com.risksentinel.core.risk.SimpleRiskEngine;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Wires the ingestion path (trades → position book → snapshot cache) and the
 * decision path (proposals → gateway → broker → fills → ingestion queue) into
 * a single managed object.
 *
 * <p>Two construction modes:
 * <ul>
 *   <li>{@link #RiskPipeline(Map, GatewayLimits, BrokerSink)} — legacy 3-arg
 *       form. The caller supplies any {@link BrokerSink} (e.g. a recording stub
 *       in tests). No broker lifecycle is managed by the pipeline.</li>
 *   <li>{@link #RiskPipeline(Map, GatewayLimits, FillModel, ExecutorService, Clock)} —
 *       full Phase 4 form. The pipeline instantiates a {@link PaperBroker},
 *       routes accepted proposals to it, and feeds emitted fills back through
 *       the trade queue so the position book and snapshot cache reflect them
 *       before the next proposal is evaluated.</li>
 * </ul>
 */
public class RiskPipeline {

    private final PositionBook positionBook;
    private final RiskEngine riskEngine;
    private final RiskSnapshotCache snapshotCache;
    private final BlockingQueue<Trade> tradeQueue;
    private final TradeIngestor ingestor;
    private final PreTradeGateway gateway;
    private final GatewayState gatewayState;
    private final BrokerSink brokerSink;
    private final PaperBroker paperBroker;
    private final ExecutorService brokerExecutor;

    /** Legacy: caller supplies the BrokerSink. The pipeline does not own a broker. */
    public RiskPipeline(
            Map<String, Instrument> instrumentRegistry,
            GatewayLimits limits,
            BrokerSink brokerSink) {
        Objects.requireNonNull(instrumentRegistry, "instrumentRegistry");
        Objects.requireNonNull(limits, "limits");
        this.brokerSink = Objects.requireNonNull(brokerSink, "brokerSink");
        this.paperBroker = null;
        this.brokerExecutor = null;

        this.positionBook = new ConcurrentPositionBook();
        this.riskEngine = new SimpleRiskEngine();
        this.snapshotCache = new ConcurrentRiskSnapshotCache();
        this.tradeQueue = new LinkedBlockingQueue<>();
        this.ingestor = new TradeIngestor(
                tradeQueue, positionBook, riskEngine, snapshotCache, instrumentRegistry);
        this.gatewayState = new GatewayState();
        this.gateway = new PreTradeGateway(snapshotCache, instrumentRegistry, limits, gatewayState);
    }

    /**
     * Full Phase 4 wiring. The pipeline owns a {@link PaperBroker} that runs on
     * the supplied executor; emitted fills are translated to {@link Trade}s and
     * offered to the ingestion queue, closing the proposal-to-snapshot loop.
     */
    public RiskPipeline(
            Map<String, Instrument> instrumentRegistry,
            GatewayLimits limits,
            FillModel fillModel,
            ExecutorService brokerExecutor,
            Clock clock) {
        Objects.requireNonNull(instrumentRegistry, "instrumentRegistry");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(fillModel, "fillModel");
        this.brokerExecutor = Objects.requireNonNull(brokerExecutor, "brokerExecutor");
        Objects.requireNonNull(clock, "clock");

        this.positionBook = new ConcurrentPositionBook();
        this.riskEngine = new SimpleRiskEngine();
        this.snapshotCache = new ConcurrentRiskSnapshotCache();
        this.tradeQueue = new LinkedBlockingQueue<>();
        this.ingestor = new TradeIngestor(
                tradeQueue, positionBook, riskEngine, snapshotCache, instrumentRegistry);
        this.gatewayState = new GatewayState();
        this.gateway = new PreTradeGateway(snapshotCache, instrumentRegistry, limits, gatewayState);

        FillSink fillSink = this::onFill;
        this.paperBroker = new PaperBroker(
                instrumentRegistry, fillModel, fillSink, brokerExecutor, clock);
        this.brokerSink = this.paperBroker;
    }

    public void start() {
        ingestor.start();
    }

    /**
     * Stop the broker first (so no new fills enter the queue), then drain and
     * stop the ingestor.
     */
    public void stop() throws InterruptedException {
        if (brokerExecutor != null) {
            brokerExecutor.shutdown();
            if (!brokerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                brokerExecutor.shutdownNow();
            }
        }
        if (paperBroker != null) {
            paperBroker.shutdown();
        }
        ingestor.stop();
        gatewayState.shutdown();
    }

    public void submit(Trade trade) {
        tradeQueue.offer(trade);
    }

    public GatewayDecision submitProposal(TradeProposal proposal) {
        GatewayDecision decision = gateway.decide(proposal);
        if (decision instanceof GatewayDecision.Accept) {
            brokerSink.submit(proposal);
        }
        return decision;
    }

    private void onFill(FillEvent event) {
        if (paperBroker == null) {
            return;
        }
        Order order = paperBroker.orderForProposal(event.proposalId()).orElseThrow(
                () -> new IllegalStateException(
                        "No order for fill " + event.fillId() + " / proposal " + event.proposalId()));
        Trade fill = new Trade(
                event.fillId(),
                order.portfolioId(),
                order.symbol(),
                order.side(),
                event.filledQuantity(),
                event.filledPrice(),
                event.filledAt());
        submit(fill);
    }

    public Map<String, RiskSnapshot> processBatch(List<Trade> trades) throws InterruptedException {
        for (Trade trade : trades) {
            submit(trade);
        }
        while (!tradeQueue.isEmpty()) {
            Thread.sleep(10);
        }
        Thread.sleep(50);
        return snapshotCache.getAllSnapshots();
    }

    public PositionBook getPositionBook() {
        return positionBook;
    }

    public RiskSnapshotCache getSnapshotCache() {
        return snapshotCache;
    }

    public PreTradeGateway getGateway() {
        return gateway;
    }

    public GatewayState getGatewayState() {
        return gatewayState;
    }

    /** The owned broker, if the Phase 4 constructor was used. Empty for legacy wiring. */
    public Optional<PaperBroker> getBroker() {
        return Optional.ofNullable(paperBroker);
    }
}
