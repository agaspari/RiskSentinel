package com.risksentinel.core;

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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Wires the ingestion path (trades → position book → snapshot cache) and
 * the decision path (proposals → gateway → broker sink) into a single
 * managed object.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #submit(Trade)} — enqueues a broker fill for asynchronous
 *       application by {@link TradeIngestor}. Used by the fill-handling path.</li>
 *   <li>{@link #submitProposal(TradeProposal)} — synchronous gateway decision.
 *       Accepted proposals are forwarded to the configured {@link BrokerSink};
 *       rejected ones are returned to the caller for inspection.</li>
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

    public RiskPipeline(
            Map<String, Instrument> instrumentRegistry,
            GatewayLimits limits,
            BrokerSink brokerSink) {
        Objects.requireNonNull(instrumentRegistry, "instrumentRegistry");
        Objects.requireNonNull(limits, "limits");
        this.brokerSink = Objects.requireNonNull(brokerSink, "brokerSink");

        this.positionBook = new ConcurrentPositionBook();
        this.riskEngine = new SimpleRiskEngine();
        this.snapshotCache = new ConcurrentRiskSnapshotCache();
        this.tradeQueue = new LinkedBlockingQueue<>();
        this.ingestor = new TradeIngestor(
                tradeQueue, positionBook, riskEngine, snapshotCache, instrumentRegistry);
        this.gatewayState = new GatewayState();
        this.gateway = new PreTradeGateway(snapshotCache, instrumentRegistry, limits, gatewayState);
    }

    public void start() {
        ingestor.start();
    }

    public void stop() throws InterruptedException {
        ingestor.stop();
    }

    /** Enqueue a broker fill for asynchronous application. */
    public void submit(Trade trade) {
        tradeQueue.offer(trade);
    }

    /**
     * Synchronously route a proposal through the gateway. Accepted proposals
     * are forwarded to the configured {@link BrokerSink}; the decision is
     * always returned so callers can inspect rejections.
     */
    public GatewayDecision submitProposal(TradeProposal proposal) {
        GatewayDecision decision = gateway.decide(proposal);
        if (decision instanceof GatewayDecision.Accept) {
            brokerSink.submit(proposal);
        }
        return decision;
    }

    /** Legacy synchronous batch processor retained for Phase 1 tests. */
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
}
