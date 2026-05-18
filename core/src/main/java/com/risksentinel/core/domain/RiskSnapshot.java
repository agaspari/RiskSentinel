package com.risksentinel.core.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record RiskSnapshot(
        String snapshotId,
        String portfolioId,
        double netExposure,
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
        Objects.requireNonNull(sectorExposure, "sectorExposure cannot be null");
        Objects.requireNonNull(regionExposure, "regionExposure cannot be null");
        Objects.requireNonNull(computedAt, "computedAt cannot be null");

        if (snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId cannot be empty");
        }
        if (portfolioId.isBlank()) {
            throw new IllegalArgumentException("portfolioId cannot be empty");
        }
        if (concentrationHHI < 0.0 || concentrationHHI > 1.0) {
            throw new IllegalArgumentException("concentrationHHI must be in [0.0, 1.0]");
        }

        sectorExposure = Map.copyOf(sectorExposure);
        regionExposure = Map.copyOf(regionExposure);
    }
}
