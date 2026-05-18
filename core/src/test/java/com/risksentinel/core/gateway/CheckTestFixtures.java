package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Builders shared across RiskCheck unit tests. Kept terse on purpose. */
final class CheckTestFixtures {

    static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    static final Instrument GOOGL = new Instrument("GOOGL", "Technology", "US", 100.0);
    static final Instrument JPM = new Instrument("JPM", "Finance", "US", 200.0);

    static final Map<String, Instrument> REGISTRY = Map.of(
            "AAPL", AAPL, "GOOGL", GOOGL, "JPM", JPM);

    private CheckTestFixtures() {}

    static GatewayLimits defaultLimits() {
        return new GatewayLimits(
                10_000L,            // maxPositionQty
                1_000_000.0,        // maxGrossExposure
                500_000.0,          // maxNetExposure
                0.7,                // maxHHI
                0.6,                // maxSectorWeight
                0.10,               // fatFingerPriceDevPct (10%)
                100_000L,           // fatFingerMaxQty
                Duration.ofSeconds(30));
    }

    static TradeProposal proposal(String symbol, Side side, long qty, double limitPrice) {
        return new TradeProposal(
                UUID.randomUUID().toString(),
                "port-1",
                symbol,
                side,
                qty,
                limitPrice,
                limitPrice,
                "test",
                0.9,
                "snap-1",
                Instant.parse("2026-05-18T12:00:00Z"));
    }

    static GatewayContext ctx(RiskSnapshot snapshot, Instrument instrument, GatewayLimits limits, GatewayState state) {
        return new GatewayContext(snapshot, instrument, limits, state, Instant.parse("2026-05-18T12:00:00Z"));
    }

    static RiskSnapshot snapshot(String portfolioId, Map<String, Position> positions,
                                 double netExposure, double grossExposure,
                                 Map<String, Double> sectorExposure) {
        return new RiskSnapshot(
                "snap-" + UUID.randomUUID(),
                portfolioId,
                netExposure,
                grossExposure,
                positions,
                sectorExposure,
                new HashMap<>(Map.of("US", grossExposure)),
                0.0,
                0.0,
                0.0,
                Instant.parse("2026-05-18T12:00:00Z"));
    }

    static Position pos(String symbol, long qty, double price) {
        return new Position("port-1", symbol, qty, price, qty * price);
    }
}
