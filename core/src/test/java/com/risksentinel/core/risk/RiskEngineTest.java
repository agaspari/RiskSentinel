
package com.risksentinel.core.risk;

import com.risksentinel.core.domain.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Task 1.4 — RiskEngine tests.
 *
 * The risk math is straightforward but must be exact. Work out the expected
 * values on paper or a calculator before writing the assertions.
 */
class RiskEngineTest {

    private RiskEngine engine;

    // Reference instruments
    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Instrument GOOGL = new Instrument("GOOGL", "Technology", "US", 100.0);
    private static final Instrument JPM = new Instrument("JPM", "Finance", "US", 200.0);

    private static final Map<String, Instrument> INSTRUMENTS = Map.of(
            "AAPL", AAPL,
            "GOOGL", GOOGL,
            "JPM", JPM);

    @BeforeEach
    void setUp() {
        // TODO: instantiate your RiskEngine implementation
        // engine = new SimpleRiskEngine();
    }

    // Helper to build a position
    private Position pos(String symbol, long qty, double avgCost) {
        double marketValue = qty * INSTRUMENTS.get(symbol).price();
        return new Position("test-portfolio", symbol, qty, avgCost, marketValue);
    }

    // ──────────────────────────────────────────────
    // Net exposure
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Net exposure calculation")
    class NetExposure {

        @Test
        void shouldComputeNetExposure_singleLongPosition() {
            // 100 AAPL @ $150 → netExposure = 15,000
            // TODO: you write this
        }

        @Test
        void shouldComputeNetExposure_longAndShort() {
            // 100 AAPL (long) + -50 GOOGL (short)
            // net = 100*150 + (-50)*100 = 15000 - 5000 = 10000
            // TODO: you write this
        }

        @Test
        void shouldReturnZeroExposure_whenNoPositions() {
            // TODO: compute with empty collection
        }
    }

    // ──────────────────────────────────────────────
    // Sector / Region exposure
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Sector and region grouping")
    class SectorRegion {

        @Test
        void shouldGroupSectorExposure_correctly() {
            // AAPL (Tech, $15000) + GOOGL (Tech, $5000) + JPM (Finance, $20000)
            // → Technology: 20000, Finance: 20000
            // TODO: you write this — check the signs and grouping
        }

        @Test
        void shouldGroupRegionExposure_correctly() {
            // All three are "US" → single entry
            // TODO: you write this
        }
    }

    // ──────────────────────────────────────────────
    // Concentration (HHI)
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Concentration HHI")
    class Concentration {

        @Test
        void shouldBeMaximum_whenSinglePosition() {
            // HHI of a single position = 1.0
            // TODO: you write this
        }

        @Test
        void shouldBe0Point5_whenTwoEqualPositions() {
            // Two positions, each 50% of gross → HHI = 0.25 + 0.25 = 0.5
            // TODO: you write this — make sure the positions have equal |market value|
        }

        @Test
        void shouldBelow1_whenDiversified() {
            // Three positions of varying sizes
            // HHI = sum of (|weight_i|)^2 where weight_i = |value_i| / gross
            // TODO: compute by hand, then assert
        }
    }

    // ──────────────────────────────────────────────
    // Property tests
    // ──────────────────────────────────────────────

    @Property(tries = 100)
    void hhiShouldAlwaysBeInUnitInterval(
            @ForAll @Size(min = 1, max = 10) List<@DoubleRange(min = 1, max = 100_000) Double> values) {
        // TODO: build positions with these market values
        // Compute snapshot, assert HHI in [0.0, 1.0]
        // This is a good candidate for Claude Code to help implement,
        // but make sure YOU verify the HHI formula
    }

    @Property(tries = 100)
    void sectorExposuresShouldSumToGrossExposure(
            @ForAll @Size(min = 1, max = 5) List<@LongRange(min = 1, max = 1000) Long> quantities) {
        // TODO: build positions across sectors, compute snapshot
        // Assert: sum of |sector exposures| == gross exposure
        // Careful with sign — sectors sum absolute values
    }
}
