package com.risksentinel.eval.runner;

import com.risksentinel.core.audit.Caller;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.GatewayDecision;
import com.risksentinel.eval.data.Bar;
import com.risksentinel.eval.data.MarketDataSource;
import com.risksentinel.eval.report.BacktestReport;
import com.risksentinel.eval.report.LatencyStats;
import com.risksentinel.eval.strategy.Strategy;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic backtest driver. For each bar:
 * <ol>
 *   <li>Advances the {@link com.risksentinel.core.ops.MutableClock} to the bar's timestamp.</li>
 *   <li>Updates the current-close map so the {@link com.risksentinel.core.broker.BarPriceFillModel}
 *       prices fills against the active bar.</li>
 *   <li>Calls {@link Strategy#onBar} with the latest snapshot.</li>
 *   <li>For each emitted proposal: times {@link com.risksentinel.core.gateway.PreTradeGateway#decide}
 *       under {@link Caller#system()}, counts the outcome, and on {@code Accept} submits to
 *       the broker. Because the broker runs on a {@link DirectExecutorService}, the fill is
 *       applied and the snapshot refreshed before {@code submit} returns.</li>
 * </ol>
 *
 * <p>Hard cap: a single run is rejected if it would exceed
 * {@value #MAX_PROPOSALS_PER_RUN} proposals. A backtest that wants to emit that
 * many is almost certainly a runaway strategy.
 */
public final class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);
    private static final int MAX_PROPOSALS_PER_RUN = 1_000_000;

    private final BacktestSystem system;

    public BacktestRunner(BacktestSystem system) {
        this.system = Objects.requireNonNull(system, "system");
    }

    public BacktestReport run(MarketDataSource data, Strategy strategy) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(strategy, "strategy");

        Instant startedAt = system.clock().instant();
        Histogram latencyHist = new Histogram(2);
        Map<String, Integer> rejectsByCode = new HashMap<>();
        Map<String, Double> lastCloseBySymbol = new HashMap<>();

        int barsProcessed = 0;
        int totalProposals = 0;
        int accepted = 0;
        int rejected = 0;
        String resolvedPortfolioId = strategy.portfolioId();

        for (Bar bar : data) {
            system.clock().setNow(bar.timestamp());
            system.updateCurrentClose(bar.symbol(), bar.close());
            lastCloseBySymbol.put(bar.symbol(), bar.close());

            RiskSnapshot snap = resolvedPortfolioId == null
                    ? null
                    : system.snapshots().getSnapshot(resolvedPortfolioId).orElse(null);

            List<TradeProposal> proposals = strategy.onBar(bar, snap, system.clock());
            for (TradeProposal p : proposals) {
                if (resolvedPortfolioId == null) {
                    resolvedPortfolioId = p.portfolioId();
                }
                totalProposals++;
                if (totalProposals > MAX_PROPOSALS_PER_RUN) {
                    throw new IllegalStateException(
                            "Runaway strategy: > " + MAX_PROPOSALS_PER_RUN + " proposals in a single run");
                }
                long t0 = System.nanoTime();
                GatewayDecision decision = system.gateway().decide(p, Caller.system());
                latencyHist.recordValue(Math.max(1, System.nanoTime() - t0));

                if (decision instanceof GatewayDecision.Accept) {
                    accepted++;
                    system.broker().submit(p);
                } else if (decision instanceof GatewayDecision.Reject r) {
                    rejected++;
                    String code = r.reasons().isEmpty()
                            ? "UNKNOWN"
                            : r.reasons().get(0).code().name();
                    rejectsByCode.merge(code, 1, Integer::sum);
                }
            }
            barsProcessed++;
        }

        Instant endedAt = system.clock().instant();

        Map<String, Long> endingPositions = new HashMap<>();
        double markToMarketValue = 0.0;
        if (resolvedPortfolioId != null) {
            for (Position pos : system.positions().getPositions(resolvedPortfolioId)) {
                if (pos.quantity() == 0L) continue;
                endingPositions.put(pos.symbol(), pos.quantity());
                Double lastClose = lastCloseBySymbol.get(pos.symbol());
                if (lastClose != null) {
                    markToMarketValue += pos.quantity() * lastClose;
                }
            }
        }
        double endingPnl = system.cashFlow() + markToMarketValue;

        LatencyStats latency = LatencyStats.fromHistogram(latencyHist);
        BacktestReport report = new BacktestReport(
                strategy.name(),
                startedAt,
                endedAt,
                barsProcessed,
                totalProposals,
                accepted,
                rejected,
                rejectsByCode,
                endingPositions,
                endingPnl,
                latency);
        log.debug("Backtest complete strategy={} bars={} accepted={} rejected={}",
                strategy.name(), barsProcessed, accepted, rejected);
        return report;
    }
}
