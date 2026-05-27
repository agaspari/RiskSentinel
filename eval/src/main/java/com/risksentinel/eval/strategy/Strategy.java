package com.risksentinel.eval.strategy;

import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.eval.data.Bar;

import java.time.Clock;
import java.util.List;

/**
 * A backtest strategy. Given a bar plus the current risk snapshot for the
 * portfolio the strategy operates against, returns zero or more
 * {@link TradeProposal}s for that bar.
 *
 * <p>Implementations may keep internal mutable state — the
 * {@link com.risksentinel.eval.runner.BacktestRunner} guarantees serial
 * invocation. They must be deterministic given their constructor arguments;
 * any randomness must be seeded.
 *
 * <p>Strategies do <em>not</em> check risk limits, position caps, or kill
 * switches. The gateway is the only enforcement point. A strategy that
 * over-proposes will see its proposals rejected — that is a feature, not
 * a defect.
 */
public interface Strategy {

    /** Stable, human-readable name for the {@link com.risksentinel.eval.report.BacktestReport}. */
    String name();

    /**
     * The portfolio this strategy reads its snapshot for. If {@code null},
     * the runner uses the portfolioId of the first proposal it sees.
     */
    default String portfolioId() {
        return null;
    }

    /**
     * Emit proposals for the given bar. May return an empty list. The
     * returned list must not be modified by the caller; implementations
     * should return immutable lists.
     *
     * @param bar       the current bar (one symbol)
     * @param snapshot  the latest snapshot for the strategy's portfolio, or
     *                  {@code null} if no snapshot has been produced yet
     * @param clock     the runner's clock; use {@code clock.instant()} for
     *                  the {@code proposedAt} field on emitted proposals
     */
    List<TradeProposal> onBar(Bar bar, RiskSnapshot snapshot, Clock clock);
}
