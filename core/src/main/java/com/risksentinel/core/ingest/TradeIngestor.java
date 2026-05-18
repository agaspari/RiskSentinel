package com.risksentinel.core.ingest;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Trade;
import com.risksentinel.core.positions.PositionBook;
import com.risksentinel.core.risk.RiskEngine;
import com.risksentinel.core.risk.RiskSnapshotCache;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TradeIngestor {
    private final BlockingQueue<Trade> tradeQueue;
    private final PositionBook positionBook;
    private final RiskEngine riskEngine;
    private final RiskSnapshotCache snapshotCache;
    private final Map<String, Instrument> instrumentRegistry;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TradeIngestor(
            BlockingQueue<Trade> tradeQueue,
            PositionBook positionBook,
            RiskEngine riskEngine,
            RiskSnapshotCache snapshotCache,
            Map<String, Instrument> instrumentRegistry
    ) {
        this.tradeQueue = tradeQueue;
        this.positionBook = positionBook;
        this.riskEngine = riskEngine;
        this.snapshotCache = snapshotCache;
        this.instrumentRegistry = instrumentRegistry;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TradeIngestor");
            t.setDaemon(false);
            return t;
        });
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::ingestLoop);
        }
    }

    public void stop() throws InterruptedException {
        running.set(false);
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }

    private void ingestLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Trade trade = tradeQueue.poll(1, TimeUnit.SECONDS);
                if (trade != null) {
                    processTrade(trade);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log and swallow so ingest doesn't die on one bad trade
            }
        }
    }

    private void processTrade(Trade trade) {
        positionBook.apply(trade);
        String portfolioId = trade.portfolioId();
        Collection<Position> positions = positionBook.getPositions(portfolioId);
        RiskSnapshot snapshot = riskEngine.compute(portfolioId, positions, instrumentRegistry);
        snapshotCache.updateSnapshots(Map.of(portfolioId, snapshot));
    }
}
