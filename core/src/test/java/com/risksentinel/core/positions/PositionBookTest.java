package com.risksentinel.core.positions;

import com.risksentinel.core.domain.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Task 1.3 — PositionBook tests.
 *
 * These encode the position math that the entire risk system depends on.
 * YOU write every assertion. The weighted average cost calculation and
 * the sell-doesn't-change-avgCost rule are the tricky parts — work them
 * out on paper first.
 *
 * Phase 2 replaces the implementation with a concurrent version.
 * These tests stay identical — that's the payoff of interface-first design.
 */
class PositionBookTest {

    private PositionBook book;

    @BeforeEach
    void setUp() {
        // TODO: instantiate your naïve (non-concurrent) implementation
        // book = new SimplePositionBook();
    }

    // ──────────────────────────────────────────────
    // Helpers — build trades concisely
    // ──────────────────────────────────────────────

    private Trade buy(String portfolio, String symbol, long qty, double price) {
        return new Trade(
            System.nanoTime(), portfolio, symbol, Side.BUY, qty, price, Instant.now()
        );
    }

    private Trade sell(String portfolio, String symbol, long qty, double price) {
        return new Trade(
            System.nanoTime(), portfolio, symbol, Side.SELL, qty, price, Instant.now()
        );
    }

    // ──────────────────────────────────────────────
    // Basic position lifecycle
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Single-symbol position lifecycle")
    class SingleSymbol {

        @Test
        void shouldCreateNewPosition_whenFirstBuyForSymbol() {
            // Buy 100 AAPL @ $150
            // → Position(qty=100, avgCost=150.0)
            // TODO: you write this
        }

        @Test
        void shouldAccumulatePosition_whenMultipleBuys() {
            // Buy 100 @ $150, then Buy 50 @ $160
            // → qty=150, avgCost = (100*150 + 50*160) / 150 = 153.33...
            // TODO: you write this — use assertThat(...).isCloseTo(..., within(...))
        }

        @Test
        void shouldReducePosition_whenSelling() {
            // Buy 100 @ $150, then Sell 40 @ anything
            // → qty=60, avgCost=150.0 (avgCost does NOT change on sells)
            // TODO: you write this
        }

        @Test
        void shouldGoFlat_whenSellingEntirePosition() {
            // Buy 100, Sell 100
            // → qty=0
            // TODO: you write this
        }

        @Test
        void shouldPreserveAvgCost_afterPartialSell() {
            // Buy 100 @ $150, Sell 30, Buy 50 @ $170
            // After sell: qty=70, avgCost=150.0
            // After second buy: qty=120, avgCost = (70*150 + 50*170) / 120 = 158.33...
            // TODO: you write this — this is the nuanced case
        }
    }

    // ──────────────────────────────────────────────
    // Isolation
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Portfolio and symbol isolation")
    class Isolation {

        @Test
        void shouldTrackMultiplePortfoliosIndependently() {
            // Trade AAPL in portfolio "A" and portfolio "B"
            // Assert positions are independent
            // TODO: you write this
        }

        @Test
        void shouldTrackMultipleSymbolsIndependently() {
            // Trade AAPL and GOOGL in the same portfolio
            // Assert positions are independent
            // TODO: you write this
        }
    }

    // ──────────────────────────────────────────────
    // Query methods
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Query behavior")
    class Queries {

        @Test
        void shouldReturnEmpty_whenNoTradesForSymbol() {
            // TODO: you write this
        }

        @Test
        void shouldReturnAllPositions_forPortfolio() {
            // Trade AAPL and GOOGL in portfolio "A"
            // getPositions("A") should return both
            // TODO: you write this
        }

        @Test
        void shouldReturnAllPortfolioIds() {
            // Trade in "A" and "B"
            // getPortfolioIds() should return both
            // TODO: you write this
        }
    }

    // ──────────────────────────────────────────────
    // Property-based tests (jqwik)
    // ──────────────────────────────────────────────

    @Property(tries = 200)
    void buyThenSellSameQuantity_shouldResultInFlatPosition(
            @ForAll @LongRange(min = 1, max = 10_000) long qty,
            @ForAll @DoubleRange(min = 0.01, max = 10_000.0) double price
    ) {
        // TODO: create a fresh PositionBook, apply buy(qty, price), apply sell(qty, price)
        // Assert position quantity == 0
    }

    @Property(tries = 200)
    void quantityShouldNeverGoNegative_afterValidBuySellSequence(
            @ForAll @LongRange(min = 1, max = 1_000) long buyQty,
            @ForAll @LongRange(min = 1, max = 1_000) long sellQty,
            @ForAll @DoubleRange(min = 0.01, max = 10_000.0) double price
    ) {
        // Only sell up to what we bought
        long actualSell = Math.min(buyQty, sellQty);
        // TODO: apply buy(buyQty), apply sell(actualSell)
        // Assert position quantity >= 0
    }
}
