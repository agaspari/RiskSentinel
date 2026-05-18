package com.risksentinel.core;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Trade;
import com.risksentinel.core.ingest.TradeIngestor;
import com.risksentinel.core.positions.ConcurrentPositionBook;
import com.risksentinel.core.positions.PositionBook;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskEngine;
import com.risksentinel.core.risk.RiskSnapshotCache;
import com.risksentinel.core.risk.SimpleRiskEngine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class RiskPipeline {

    private final PositionBook positionBook;
    private final RiskEngine riskEngine;
    private final RiskSnapshotCache snapshotCache;
    private final BlockingQueue<Trade> tradeQueue;
    private final TradeIngestor ingestor;

    public RiskPipeline(Map<String, Instrument> instrumentRegistry) {
        this.positionBook = new ConcurrentPositionBook();
        this.riskEngine = new SimpleRiskEngine();
        this.snapshotCache = new ConcurrentRiskSnapshotCache();
        this.tradeQueue = new LinkedBlockingQueue<>();
        this.ingestor = new TradeIngestor(
                tradeQueue, positionBook, riskEngine, snapshotCache, instrumentRegistry
        );
    }

    public void start() {
        ingestor.start();
    }

    public void stop() throws InterruptedException {
        ingestor.stop();
    }

    public void submit(Trade trade) {
        tradeQueue.offer(trade);
    }
    
    // Legacy synchronous batch processor for Phase 1 tests, if needed
    public Map<String, RiskSnapshot> processBatch(List<Trade> trades) throws InterruptedException {
        for (Trade trade : trades) {
            submit(trade);
        }
        // Very basic wait to let async ingestor finish if testing
        while (!tradeQueue.isEmpty()) {
            Thread.sleep(10);
        }
        Thread.sleep(50); // slight delay to ensure processing finishes
        return snapshotCache.getAllSnapshots();
    }

    public PositionBook getPositionBook() {
        return positionBook;
    }

    public RiskSnapshotCache getSnapshotCache() {
        return snapshotCache;
    }
}
