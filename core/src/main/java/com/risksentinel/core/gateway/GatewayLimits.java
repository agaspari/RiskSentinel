package com.risksentinel.core.gateway;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration record holding every threshold the gateway enforces.
 *
 * <p>Limits are intentionally loaded once at startup and treated as constants
 * for the life of the gateway instance. Hot-reloading requires constructing
 * a new gateway.
 *
 * @param maxPositionQty          absolute cap on post-trade per-symbol quantity
 * @param maxGrossExposure        absolute cap on post-trade gross exposure (sum of abs notionals)
 * @param maxNetExposure          absolute cap on |post-trade net exposure|
 * @param maxHHI                  cap on post-trade concentration HHI, in [0, 1]
 * @param maxSectorWeight         cap on any single sector's share of gross, in [0, 1]
 * @param fatFingerPriceDevPct    e.g. 0.10 == reject when |limit price - market| / market &gt; 10%
 * @param fatFingerMaxQty         absolute hard ceiling on proposal quantity
 * @param maxSnapshotAge          reject if the cached snapshot is older than this at decide-time
 */
public record GatewayLimits(
        long maxPositionQty,
        double maxGrossExposure,
        double maxNetExposure,
        double maxHHI,
        double maxSectorWeight,
        double fatFingerPriceDevPct,
        long fatFingerMaxQty,
        Duration maxSnapshotAge
) {
    public GatewayLimits {
        if (maxPositionQty <= 0) {
            throw new IllegalArgumentException("maxPositionQty must be positive");
        }
        if (maxGrossExposure <= 0.0) {
            throw new IllegalArgumentException("maxGrossExposure must be positive");
        }
        if (maxNetExposure <= 0.0) {
            throw new IllegalArgumentException("maxNetExposure must be positive");
        }
        if (maxHHI < 0.0 || maxHHI > 1.0) {
            throw new IllegalArgumentException("maxHHI must be in [0.0, 1.0]");
        }
        if (maxSectorWeight < 0.0 || maxSectorWeight > 1.0) {
            throw new IllegalArgumentException("maxSectorWeight must be in [0.0, 1.0]");
        }
        if (fatFingerPriceDevPct < 0.0) {
            throw new IllegalArgumentException("fatFingerPriceDevPct must be non-negative");
        }
        if (fatFingerMaxQty <= 0) {
            throw new IllegalArgumentException("fatFingerMaxQty must be positive");
        }
        Objects.requireNonNull(maxSnapshotAge, "maxSnapshotAge cannot be null");
        if (maxSnapshotAge.isNegative() || maxSnapshotAge.isZero()) {
            throw new IllegalArgumentException("maxSnapshotAge must be positive");
        }
    }
}
