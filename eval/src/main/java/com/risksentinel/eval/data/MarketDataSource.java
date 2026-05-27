package com.risksentinel.eval.data;

/**
 * Source of bars in non-decreasing timestamp order across all symbols.
 *
 * <p>A source that returns bars out of order is a defect; the backtest runner
 * does not sort. Implementations should validate ordering at construction
 * time when possible.
 */
public interface MarketDataSource extends Iterable<Bar> {
}
