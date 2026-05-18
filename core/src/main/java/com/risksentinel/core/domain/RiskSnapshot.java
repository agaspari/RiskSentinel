package com.risksentinel.core.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Point-in-time risk view for a single portfolio. Immutable.
 *
 * <p>Produced by the risk engine after each trade application and cached for
 * lock-free reads by the pre-trade gateway and downstream subscribers.
 * The {@code positions} and exposure maps are defensively copied via
 * {@link Map#copyOf} in the compact constructor so callers cannot mutate
 * shared state.
 */
public record RiskSnapshot(
        String snapshotId,
        String portfolioId,
        double netExposure,
        double grossExposure,
        Map<String, Position> positions,
        Map<String, Double> sectorExposure,
        Map<String, Double> regionExposure,
        double concentrationHHI,
        double parametricVaR95,
        double dailyPnL,
        Instant computedAt
) {
    public RiskSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId cannot be null");
        Objects.requireNonNull(portfolioId, "portfolioId cannot be null");
        Objects.requireNonNull(positions, "positions cannot be null");
        Objects.requireNonNull(sectorExposure, "sectorExposure cannot be null");
        Objects.requireNonNull(regionExposure, "regionExposure cannot be null");
        Objects.requireNonNull(computedAt, "computedAt cannot be null");

        if (snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId cannot be empty");
        }
        if (portfolioId.isBlank()) {
            throw new IllegalArgumentException("portfolioId cannot be empty");
        }
        if (grossExposure < 0.0) {
            throw new IllegalArgumentException("grossExposure cannot be negative");
        }
        if (concentrationHHI < 0.0 || concentrationHHI > 1.0) {
            throw new IllegalArgumentException("concentrationHHI must be in [0.0, 1.0]");
        }

        positions = Map.copyOf(positions);
        sectorExposure = Map.copyOf(sectorExposure);
        regionExposure = Map.copyOf(regionExposure);
    }
}
