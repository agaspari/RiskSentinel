package com.risksentinel.core.ops;

import java.util.function.Supplier;

/**
 * Façade over the metrics backend. Hides Micrometer (and any future backend)
 * from {@code core/broker/} and {@code core/gateway/}.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link NoopMetricsRegistry} — default; counters track in-process via
 *       {@link java.util.concurrent.atomic.LongAdder}, timers/gauges are zero-cost
 *       sinks, {@code scrapeText} returns the empty string.</li>
 *   <li>{@code MicrometerMetricsRegistry} — wraps a Prometheus meter registry
 *       and renders Prometheus exposition format via {@link #scrapeText()}.</li>
 * </ul>
 *
 * <p>Repeated calls with the same {@code (name, tags)} pair must return the
 * same logical instrument (caching/de-duplication is the implementation's
 * responsibility).
 */
public interface MetricsRegistry {

    Counter counter(String name, Tags tags);

    Timer timer(String name, Tags tags);

    Gauge gauge(String name, Tags tags, Supplier<Number> supplier);

    /** Renders a snapshot of every registered instrument in Prometheus format. */
    String scrapeText();
}
