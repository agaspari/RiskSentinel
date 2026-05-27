package com.risksentinel.eval.data;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Deterministic OHLCV generator. Produces {@code numBars} bars per symbol
 * using a simple geometric-Brownian-motion-like price walk, interleaved in
 * timestamp order across symbols.
 *
 * <p>Each call to {@link #iterator()} re-seeds and re-generates, so iterating
 * the same generator twice produces byte-identical bars. {@link Random}
 * (not {@code SecureRandom}) is used on purpose — reproducibility matters,
 * cryptographic strength does not.
 */
public final class SyntheticBarGenerator implements MarketDataSource {

    private final long seed;
    private final List<String> symbols;
    private final Instant startTime;
    private final Duration barInterval;
    private final int numBars;
    private final double drift;
    private final double volatility;
    private final Map<String, Double> initialPrices;

    public SyntheticBarGenerator(
            long seed,
            List<String> symbols,
            Instant startTime,
            Duration barInterval,
            int numBars,
            double drift,
            double volatility,
            Map<String, Double> initialPrices) {
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(barInterval, "barInterval");
        Objects.requireNonNull(initialPrices, "initialPrices");
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("symbols cannot be empty");
        }
        if (numBars <= 0) {
            throw new IllegalArgumentException("numBars must be positive");
        }
        if (barInterval.isNegative() || barInterval.isZero()) {
            throw new IllegalArgumentException("barInterval must be positive");
        }
        if (volatility < 0.0) {
            throw new IllegalArgumentException("volatility cannot be negative");
        }
        for (String sym : symbols) {
            if (!initialPrices.containsKey(sym)) {
                throw new IllegalArgumentException("initialPrices missing symbol: " + sym);
            }
            if (initialPrices.get(sym) <= 0.0) {
                throw new IllegalArgumentException("initial price for " + sym + " must be positive");
            }
        }
        this.seed = seed;
        this.symbols = List.copyOf(symbols);
        this.startTime = startTime;
        this.barInterval = barInterval;
        this.numBars = numBars;
        this.drift = drift;
        this.volatility = volatility;
        this.initialPrices = Map.copyOf(initialPrices);
    }

    @Override
    public Iterator<Bar> iterator() {
        return generate().iterator();
    }

    private List<Bar> generate() {
        Random random = new Random(seed);
        // Last-close per symbol, evolved through the walk.
        Map<String, Double> lastClose = new java.util.HashMap<>(initialPrices);
        List<Bar> out = new ArrayList<>(numBars * symbols.size());
        for (int i = 0; i < numBars; i++) {
            Instant t = startTime.plus(barInterval.multipliedBy(i));
            // Iterate symbols in declared order so cross-symbol interleaving is stable.
            for (String sym : symbols) {
                double prev = lastClose.get(sym);
                double shock = drift + volatility * random.nextGaussian();
                double close = Math.max(0.01, prev * Math.exp(shock));
                double open = prev;
                double high = Math.max(open, close) * (1.0 + Math.abs(random.nextGaussian()) * volatility * 0.25);
                double low = Math.min(open, close) * (1.0 - Math.abs(random.nextGaussian()) * volatility * 0.25);
                // Defensive: tiny floating-point drift can break the OHLC invariant.
                high = Math.max(high, Math.max(open, close));
                low = Math.min(low, Math.min(open, close));
                long volume = 1_000L + (long) (Math.abs(random.nextGaussian()) * 1_000.0);
                out.add(new Bar(sym, t, open, high, low, close, volume));
                lastClose.put(sym, close);
            }
        }
        return out;
    }
}
