package com.risksentinel.eval.strategy;

import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.eval.data.Bar;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Z-score-based mean reversion on a single symbol. Tracks the last
 * {@code windowSize} closing prices for {@link #symbol} and emits:
 * <ul>
 *   <li>BUY {@code tradeSize} when {@code (close - mean) / stddev < -zScoreThreshold}</li>
 *   <li>SELL {@code tradeSize} when {@code (close - mean) / stddev > zScoreThreshold}</li>
 * </ul>
 *
 * <p>Does not consult the snapshot for current position — the gateway will
 * reject if a trade would breach the position cap. This is deliberate: it
 * keeps the strategy ignorant of enforcement, matching how a real LLM-driven
 * strategy would behave.
 */
public final class MeanReversionStrategy implements Strategy {

    private final String portfolioId;
    private final String symbol;
    private final int windowSize;
    private final double zScoreThreshold;
    private final long tradeSize;
    private final Deque<Double> window;
    private long sequence = 0L;

    public MeanReversionStrategy(
            String portfolioId,
            String symbol,
            int windowSize,
            double zScoreThreshold,
            long tradeSize) {
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(symbol, "symbol");
        if (portfolioId.isBlank()) {
            throw new IllegalArgumentException("portfolioId cannot be blank");
        }
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        if (windowSize < 2) {
            throw new IllegalArgumentException("windowSize must be >= 2");
        }
        if (zScoreThreshold <= 0.0) {
            throw new IllegalArgumentException("zScoreThreshold must be positive");
        }
        if (tradeSize <= 0) {
            throw new IllegalArgumentException("tradeSize must be positive");
        }
        this.portfolioId = portfolioId;
        this.symbol = symbol;
        this.windowSize = windowSize;
        this.zScoreThreshold = zScoreThreshold;
        this.tradeSize = tradeSize;
        this.window = new ArrayDeque<>(windowSize);
    }

    @Override
    public String name() {
        return "MeanReversion[" + symbol + ":w=" + windowSize + ":z=" + zScoreThreshold + "]";
    }

    @Override
    public String portfolioId() {
        return portfolioId;
    }

    @Override
    public List<TradeProposal> onBar(Bar bar, RiskSnapshot snapshot, Clock clock) {
        if (!bar.symbol().equals(symbol)) {
            return List.of();
        }
        if (window.size() == windowSize) {
            window.pollFirst();
        }
        window.offerLast(bar.close());
        if (window.size() < windowSize) {
            return List.of();
        }
        double mean = 0.0;
        for (double p : window) mean += p;
        mean /= windowSize;
        double variance = 0.0;
        for (double p : window) variance += (p - mean) * (p - mean);
        variance /= windowSize;
        double stddev = Math.sqrt(variance);
        if (stddev <= 0.0) {
            return List.of();
        }
        double zScore = (bar.close() - mean) / stddev;
        Side side;
        if (zScore < -zScoreThreshold) {
            side = Side.BUY;
        } else if (zScore > zScoreThreshold) {
            side = Side.SELL;
        } else {
            return List.of();
        }
        String snapshotId = snapshot != null ? snapshot.snapshotId() : "no-snapshot";
        sequence++;
        return List.of(new TradeProposal(
                ProposalIds.next(name(), bar, sequence),
                portfolioId,
                symbol,
                side,
                tradeSize,
                bar.close(),
                bar.close(),
                "mean-reversion z=" + String.format(java.util.Locale.ROOT, "%.3f", zScore),
                Math.min(1.0, Math.abs(zScore) / (zScoreThreshold * 2.0)),
                snapshotId,
                clock.instant()));
    }
}
