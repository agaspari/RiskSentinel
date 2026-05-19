package com.risksentinel.core.ops;

/**
 * Read-only observation of a value at the moment {@link #value()} is called.
 * The underlying supplier is registered once with the {@link MetricsRegistry}
 * and polled on scrape.
 */
public interface Gauge {
    double value();
}
