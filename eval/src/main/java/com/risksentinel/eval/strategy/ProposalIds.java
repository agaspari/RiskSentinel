package com.risksentinel.eval.strategy;

import com.risksentinel.eval.data.Bar;

/**
 * Deterministic proposal-id generator for backtests. Same inputs → same id,
 * so two runs of the same {@code (strategy, bars, seed)} produce identical
 * proposal ids and identical audit records. Format:
 * {@code <strategyName>-<timestamp-epoch-millis>-<symbol>-<sequence>}.
 */
final class ProposalIds {

    private ProposalIds() {}

    static String next(String strategyName, Bar bar, long sequence) {
        return strategyName + "-" + bar.timestamp().toEpochMilli() + "-" + bar.symbol() + "-" + sequence;
    }
}
