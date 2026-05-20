package com.risksentinel.mcp.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.Trade;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.positions.ConcurrentPositionBook;
import com.risksentinel.core.positions.PositionBook;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskEngine;
import com.risksentinel.core.risk.RiskSnapshotCache;
import com.risksentinel.core.risk.SimpleRiskEngine;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Shared helpers for tool tests: build a small backing system without a full pipeline. */
public final class BridgeFixtures {

    public static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    public static final Instrument GOOGL = new Instrument("GOOGL", "Technology", "US", 100.0);
    public static final Instrument JPM = new Instrument("JPM", "Finance", "US", 200.0);

    public static final Map<String, Instrument> REGISTRY = Map.of(
            "AAPL", AAPL, "GOOGL", GOOGL, "JPM", JPM);

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private BridgeFixtures() {}

    /** Apply some trades and recompute the snapshot, populating both stores. */
    public static SystemUnderTest withFills(String portfolioId, Trade... trades) {
        PositionBook book = new ConcurrentPositionBook();
        RiskSnapshotCache cache = new ConcurrentRiskSnapshotCache();
        RiskEngine engine = new SimpleRiskEngine();
        for (Trade t : trades) {
            book.apply(t);
        }
        Collection<com.risksentinel.core.domain.Position> positions = book.getPositions(portfolioId);
        cache.updateSnapshots(Map.of(portfolioId, engine.compute(portfolioId, positions, REGISTRY)));
        return new SystemUnderTest(book, cache);
    }

    /** Build a Trade in one line. */
    public static Trade trade(long id, String portfolioId, String symbol, Side side, long qty, double price) {
        return new Trade(id, portfolioId, symbol, side, qty, price, Instant.now());
    }

    public static TradeProposal proposal(String symbol, Side side, long qty, double price) {
        return new TradeProposal(
                UUID.randomUUID().toString(),
                "port-1", symbol, side, qty, price, price,
                "rationale", 0.9, "snap-x", Instant.now());
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static JsonNode parseInput(LinkedHashMap<String, Object> fields) {
        try {
            return MAPPER.valueToTree(fields);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record SystemUnderTest(PositionBook positionBook, RiskSnapshotCache snapshotCache) {}
}
