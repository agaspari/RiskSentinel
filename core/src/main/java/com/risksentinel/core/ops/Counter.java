package com.risksentinel.core.ops;

/**
 * Monotonically increasing counter. Created by {@link MetricsRegistry}.
 */
public interface Counter {

    void increment();

    void increment(long amount);

    long count();
}
