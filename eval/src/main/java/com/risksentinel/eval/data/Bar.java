package com.risksentinel.eval.data;

import java.time.Instant;
import java.util.Objects;

/**
 * A single OHLCV bar for one symbol. {@link #timestamp} marks the
 * <em>start</em> of the bar's interval.
 *
 * <p>Bars are domain inputs to a backtest; their internal consistency is
 * enforced here so neither the runner nor the strategy has to re-check.
 */
public record Bar(
        String symbol,
        Instant timestamp,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
    public Bar {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(timestamp, "timestamp");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        if (high < low) {
            throw new IllegalArgumentException(
                    "high (" + high + ") must be >= low (" + low + ")");
        }
        if (high < open || high < close) {
            throw new IllegalArgumentException(
                    "high (" + high + ") must be >= open (" + open + ") and close (" + close + ")");
        }
        if (low > open || low > close) {
            throw new IllegalArgumentException(
                    "low (" + low + ") must be <= open (" + open + ") and close (" + close + ")");
        }
        if (volume < 0) {
            throw new IllegalArgumentException("volume cannot be negative");
        }
    }
}
