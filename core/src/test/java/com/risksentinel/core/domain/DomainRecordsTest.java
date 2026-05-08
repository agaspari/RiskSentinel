package com.risksentinel.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Task 1.2 — Domain record validation.
 *
 * YOUR JOB: Fill in every test body. These encode the contracts
 * that all downstream code depends on. Don't let Claude Code write these.
 */
class DomainRecordsTest {

    @Nested
    @DisplayName("Trade validation")
    class TradeValidation {

        @Test
        void shouldReject_whenPortfolioIdIsNull() {
            assertThatThrownBy(() -> new Trade(1L, null, "AAPL", Side.BUY, 100, 150.0, Instant.now()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldReject_whenSymbolIsNull() {
            assertThatThrownBy(() -> new Trade(1L, "port-1", null, Side.SELL, 200, 250, Instant.now()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldReject_whenQuantityIsZeroOrNegative() {
            assertThatThrownBy(() -> new Trade(1L, "port-1", "AAPL", Side.SELL, -100, 250, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Trade(1L, "port-1", "AAPL", Side.SELL, 0, 250, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);

        }

        @Test
        void shouldReject_whenPriceIsZeroOrNegative() {
            assertThatThrownBy(() -> new Trade(1L, "port-1", "AAPL", Side.SELL, 100, -50, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Trade(1L, "port-1", "AAPL", Side.SELL, 100, 0, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldAccept_whenAllFieldsAreValid() {
            // TODO: construct a valid Trade, assert no exception
            assertThatCode(() -> new Trade(1L, "port-1", "AAPL", Side.BUY, 100, 150, Instant.now()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Instrument validation")
    class InstrumentValidation {

        @Test
        void shouldReject_whenPriceIsNegative() {
            // TODO: you write this
            assertThatThrownBy(() -> new Instrument("AAPL", "Technology", "US", -50))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Instrument("AAPL", "Technology", "US", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldReject_whenSymbolIsNull() {
            assertThatThrownBy(() -> new Instrument(null, "Technology", "US", 100))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("RiskSnapshot immutability")
    class RiskSnapshotImmutability {

        @Test
        void shouldHaveUnmodifiableSectorExposureMap() {
            RiskSnapshot riskSnapshot = new RiskSnapshot("1", "10", .5, new HashMap<>(),
                    new HashMap<>(), .2, .3, 1.1, Instant.now());

            assertThatThrownBy(() -> riskSnapshot.sectorExposure().put("A", 20.4))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void shouldHaveUnmodifiableRegionExposureMap() {
            RiskSnapshot riskSnapshot = new RiskSnapshot("1", "10", .5, new HashMap<>(),
                    new HashMap<>(), .2, .3, 1.1, Instant.now());

            assertThatThrownBy(() -> riskSnapshot.regionExposure().put("A", 10.0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("PortfolioSymbol equality")
    class PortfolioSymbolEquality {

        @Test
        void shouldBeEqual_whenSamePortfolioAndSymbol() {
            PortfolioSymbol p1 = new PortfolioSymbol("port-1", "AAPL");
            PortfolioSymbol p2 = new PortfolioSymbol("port-1", "AAPL");

            assertThat(p1).isEqualTo(p2);
            assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
        }

        @Test
        void shouldNotBeEqual_whenDifferentPortfolio() {
            PortfolioSymbol p1 = new PortfolioSymbol("port-1", "AAPL");
            PortfolioSymbol p2 = new PortfolioSymbol("port-2", "AAPL");

            assertThat(p1).isNotEqualTo(p2);
        }

        @Test
        void shouldWorkAsHashMapKey() {
            Map<PortfolioSymbol, String> map = new HashMap<>();
            PortfolioSymbol key = new PortfolioSymbol("port-1", "AAPL");
            map.put(key, "Found it!");

            PortfolioSymbol differentInstanceSameData = new PortfolioSymbol("port-1", "AAPL");
            assertThat(map).containsKey(differentInstanceSameData);
            assertThat(map.get(differentInstanceSameData)).isEqualTo("Found it!");
        }
    }
}
