package com.risksentinel.eval.report;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Aggregate outcome of a single backtest run. All fields except
 * {@link #gatewayLatency} are deterministic given the input.
 */
public record BacktestReport(
        String strategyName,
        Instant startedAt,
        Instant endedAt,
        int barsProcessed,
        int totalProposals,
        int accepted,
        int rejected,
        Map<String, Integer> rejectsByCode,
        Map<String, Long> endingPositionBySymbol,
        double endingMarkToMarketPnl,
        LatencyStats gatewayLatency
) {
    public BacktestReport {
        Objects.requireNonNull(strategyName, "strategyName");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(rejectsByCode, "rejectsByCode");
        Objects.requireNonNull(endingPositionBySymbol, "endingPositionBySymbol");
        Objects.requireNonNull(gatewayLatency, "gatewayLatency");
        if (strategyName.isBlank()) {
            throw new IllegalArgumentException("strategyName cannot be blank");
        }
        if (barsProcessed < 0) {
            throw new IllegalArgumentException("barsProcessed cannot be negative");
        }
        if (totalProposals < 0 || accepted < 0 || rejected < 0) {
            throw new IllegalArgumentException("proposal counts cannot be negative");
        }
        if (accepted + rejected != totalProposals) {
            throw new IllegalArgumentException(
                    "accepted (" + accepted + ") + rejected (" + rejected
                            + ") != totalProposals (" + totalProposals + ")");
        }
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt cannot precede startedAt");
        }
        rejectsByCode = Map.copyOf(rejectsByCode);
        endingPositionBySymbol = Map.copyOf(endingPositionBySymbol);
    }

    /** Human-readable summary. Not for parsing; for eyeballing a run. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Backtest: ").append(strategyName).append('\n');
        sb.append("- Window: ").append(startedAt).append(" → ").append(endedAt).append('\n');
        sb.append("- Bars processed: ").append(barsProcessed).append('\n');
        sb.append("- Proposals: total=").append(totalProposals)
                .append(", accepted=").append(accepted)
                .append(", rejected=").append(rejected).append('\n');
        if (!rejectsByCode.isEmpty()) {
            sb.append("- Rejects by code:\n");
            new TreeMap<>(rejectsByCode).forEach((code, count) ->
                    sb.append("    - ").append(code).append(": ").append(count).append('\n'));
        }
        if (!endingPositionBySymbol.isEmpty()) {
            sb.append("- Ending positions:\n");
            new TreeMap<>(endingPositionBySymbol).forEach((sym, qty) ->
                    sb.append("    - ").append(sym).append(": ").append(qty).append('\n'));
        }
        sb.append(String.format(Locale.ROOT, "- Ending mark-to-market PnL: %.2f%n", endingMarkToMarketPnl));
        sb.append("- Gateway latency (nanos): ")
                .append("count=").append(gatewayLatency.count())
                .append(", p50=").append(gatewayLatency.p50Nanos())
                .append(", p95=").append(gatewayLatency.p95Nanos())
                .append(", p99=").append(gatewayLatency.p99Nanos())
                .append(", max=").append(gatewayLatency.maxNanos())
                .append('\n');
        return sb.toString();
    }
}
