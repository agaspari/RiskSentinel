
package com.risksentinel.core.risk;

import com.risksentinel.core.domain.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
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
            Position p = pos("AAPL", 100, 150.0);
            RiskSnapshot snapshot = engine.compute("port-1", List.of(p), INSTRUMENTS);
            assertThat(snapshot.netExposure()).isEqualTo(15000.0);
        }

        @Test
        void shouldComputeNetExposure_longAndShort() {
            // 100 AAPL (long) + -50 GOOGL (short)
            // net = 100*150 + (-50)*100 = 15000 - 5000 = 10000
            Position p1 = pos("AAPL", 100, 150.0);
            Position p2 = pos("GOOGL", -50, 100.0);
            RiskSnapshot snapshot = engine.compute("port-1", List.of(p1, p2), INSTRUMENTS);
            assertThat(snapshot.netExposure()).isEqualTo(10000.0);
        }

        @Test
        void shouldReturnZeroExposure_whenNoPositions() {
            RiskSnapshot snapshot = engine.compute("port-1", List.of(), INSTRUMENTS);
            assertThat(snapshot.netExposure()).isEqualTo(0.0);
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
            Position p1 = pos("AAPL", 100, 150.0);
            Position p2 = pos("GOOGL", 50, 100.0);
            Position p3 = pos("JPM", 100, 200.0);
            
            RiskSnapshot snapshot = engine.compute("port-1", List.of(p1, p2, p3), INSTRUMENTS);
            assertThat(snapshot.sectorExposure()).containsEntry("Technology", 20000.0)
                                                 .containsEntry("Finance", 20000.0);
        }

        @Test
        void shouldGroupRegionExposure_correctly() {
            // All three are "US" → single entry
            Position p1 = pos("AAPL", 100, 150.0);
            Position p2 = pos("GOOGL", 50, 100.0);
            Position p3 = pos("JPM", 100, 200.0);
            
            RiskSnapshot snapshot = engine.compute("port-1", List.of(p1, p2, p3), INSTRUMENTS);
            assertThat(snapshot.regionExposure()).containsEntry("US", 40000.0);
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
            Position p = pos("AAPL", 100, 150.0);
            RiskSnapshot snapshot = engine.compute("port-1", List.of(p), INSTRUMENTS);
            assertThat(snapshot.concentrationHHI()).isEqualTo(1.0);
        }

        @Test
        void shouldBe0Point5_whenTwoEqualPositions() {
            // Two positions, each 50% of gross → HHI = 0.25 + 0.25 = 0.5
            Position p1 = pos("AAPL", 100, 150.0); // value 15000
            Position p2 = pos("GOOGL", 150, 100.0); // value 15000
            RiskSnapshot snapshot = engine.compute("port-1", List.of(p1, p2), INSTRUMENTS);
            assertThat(snapshot.concentrationHHI()).isCloseTo(0.5, within(0.001));
        }

        @Test
        void shouldBelow1_whenDiversified() {
            // Three positions of varying sizes
            // AAPL 15000, GOOGL 5000, JPM 20000. Gross = 40000
            // w1 = 15/40 = 0.375, w2 = 5/40 = 0.125, w3 = 20/40 = 0.5
            // HHI = 0.140625 + 0.015625 + 0.25 = 0.40625
            Position p1 = pos("AAPL", 100, 150.0);
            Position p2 = pos("GOOGL", 50, 100.0);
            Position p3 = pos("JPM", 100, 200.0);
            RiskSnapshot snapshot = engine.compute("port-1", List.of(p1, p2, p3), INSTRUMENTS);
            assertThat(snapshot.concentrationHHI()).isCloseTo(0.40625, within(0.001));
        }
    }

    // ──────────────────────────────────────────────
    // Property tests
    // ──────────────────────────────────────────────

    @Property(tries = 100)
    void hhiShouldAlwaysBeInUnitInterval(
            @ForAll @Size(min = 1, max = 10) List<@DoubleRange(min = 1, max = 100_000) Double> values) {
        List<Position> positions = new java.util.ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String symbol = "SYM" + i;
            // Add instrument dynamically since RiskEngine requires it
            Instrument inst = new Instrument(symbol, "Tech", "US", 1.0);
            Map<String, Instrument> dynamicInstruments = new java.util.HashMap<>(INSTRUMENTS);
            dynamicInstruments.put(symbol, inst);
            
            // Value is quantity * 1.0
            positions.add(new Position("port-1", symbol, Math.round(values.get(i)), 1.0, values.get(i)));
            
            RiskSnapshot snapshot = engine.compute("port-1", positions, dynamicInstruments);
            assertThat(snapshot.concentrationHHI()).isBetween(0.0, 1.0);
        }
    }

    @Property(tries = 100)
    void sectorExposuresShouldSumToGrossExposure(
            @ForAll @Size(min = 1, max = 5) List<@LongRange(min = 1, max = 1000) Long> quantities) {
        List<Position> positions = new java.util.ArrayList<>();
        double grossExposure = 0;
        String[] symbols = {"AAPL", "GOOGL", "JPM"};
        for (int i = 0; i < quantities.size(); i++) {
            String sym = symbols[i % symbols.length];
            long qty = quantities.get(i) * (i % 2 == 0 ? 1 : -1); // mix longs and shorts
            double price = INSTRUMENTS.get(sym).price();
            double value = qty * price;
            grossExposure += Math.abs(value);
            positions.add(new Position("port-1", sym, qty, price, value));
        }
        
        RiskSnapshot snapshot = engine.compute("port-1", positions, INSTRUMENTS);
        
        double sumOfSectorExposures = snapshot.sectorExposure().values().stream()
                .mapToDouble(Math::abs)
                .sum();
                
        assertThat(sumOfSectorExposures).isCloseTo(grossExposure, within(0.001));
    }
}
