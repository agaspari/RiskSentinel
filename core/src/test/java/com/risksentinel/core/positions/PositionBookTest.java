package com.risksentinel.core.positions;

import com.risksentinel.core.domain.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

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
        book = new SimplePositionBook();
    }

    // ──────────────────────────────────────────────
    // Helpers — build trades concisely
    // ──────────────────────────────────────────────

    private Trade buy(String portfolio, String symbol, long qty, double price) {
        return new Trade(
                System.nanoTime(), portfolio, symbol, Side.BUY, qty, price, Instant.now());
    }

    private Trade sell(String portfolio, String symbol, long qty, double price) {
        return new Trade(
                System.nanoTime(), portfolio, symbol, Side.SELL, qty, price, Instant.now());
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
            book.apply(buy("port-1", "AAPL", 100, 150.0));

            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();
            assertThat(pos.quantity()).isEqualTo(100L);
            assertThat(pos.avgCost()).isEqualTo(150.0);
        }

        @Test
        void shouldAccumulatePosition_whenMultipleBuys() {
            // Buy 100 @ $150, then Buy 50 @ $160
            // → qty=150, avgCost = (100*150 + 50*160) / 150 = 153.33...
            book.apply(buy("port-1", "AAPL", 100, 150.0));
            book.apply(buy("port-1", "AAPL", 50, 160.0));

            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();
            assertThat(pos.quantity()).isEqualTo(150L);
            assertThat(pos.avgCost()).isCloseTo(153.333, within(0.001));
        }

        @Test
        void shouldReducePosition_whenSelling() {
            // Buy 100 @ $150, then Sell 40 @ anything
            // → qty=60, avgCost=150.0 (avgCost does NOT change on sells)
            book.apply(buy("port-1", "AAPL", 100, 150.0));
            book.apply(sell("port-1", "AAPL", 40, 100.0));

            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();

            assertThat(pos.quantity()).isEqualTo(60L);
            assertThat(pos.avgCost()).isEqualTo(150.0);
        }

        @Test
        void shouldGoFlat_whenSellingEntirePosition() {
            // Buy 100, Sell 100
            // → qty=0
            book.apply(buy("port-1", "AAPL", 100, 150.0));
            book.apply(sell("port-1", "AAPL", 100, 100.0));

            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();

            assertThat(pos.quantity()).isEqualTo(0L);
        }

        @Test
        void shouldPreserveAvgCost_afterPartialSell() {
            // Buy 100 @ $150, Sell 30, Buy 50 @ $170
            // After sell: qty=70, avgCost=150.0
            // After second buy: qty=120, avgCost = (70*150 + 50*170) / 120 = 158.33...
            book.apply(buy("port-1", "AAPL", 100, 150.0));
            book.apply(sell("port-1", "AAPL", 30, 150.0));
            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();
            assertThat(pos.quantity()).isEqualTo(70L);
            assertThat(pos.avgCost()).isEqualTo(150.0);

            book.apply(buy("port-1", "AAPL", 50, 170.0));
            assertThat(pos.quantity()).isEqualTo(120L);
            assertThat(pos.avgCost()).isEqualTo(158.33, within(0.001));
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
            book.apply(buy("A", "AAPL", 100, 150.0));
            book.apply(buy("B", "AAPL", 50, 160.0));

            Position posA = book.getPosition("A", "AAPL").orElseThrow();
            Position posB = book.getPosition("B", "AAPL").orElseThrow();

            assertThat(posA.quantity()).isEqualTo(100L);
            assertThat(posA.avgCost()).isEqualTo(150.0);
            assertThat(posB.quantity()).isEqualTo(50L);
            assertThat(posB.avgCost()).isEqualTo(160.0);
        }

        @Test
        void shouldTrackMultipleSymbolsIndependently() {
            // Trade AAPL and GOOGL in the same portfolio
            // Assert positions are independent
            book.apply(buy("A", "AAPL", 100, 150.0));
            book.apply(buy("A", "GOOGL", 50, 100.0));

            Position posAAPL = book.getPosition("A", "AAPL").orElseThrow();
            Position posGOOGL = book.getPosition("A", "GOOGL").orElseThrow();

            assertThat(posAAPL.quantity()).isEqualTo(100L);
            assertThat(posGOOGL.quantity()).isEqualTo(50L);
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
            assertThat(book.getPosition("A", "AAPL")).isEmpty();
        }

        @Test
        void shouldReturnAllPositions_forPortfolio() {
            // Trade AAPL and GOOGL in portfolio "A"
            // getPositions("A") should return both
            book.apply(buy("A", "AAPL", 100, 150.0));
            book.apply(buy("A", "GOOGL", 50, 100.0));

            Collection<Position> positions = book.getPositions("A");
            assertThat(positions).hasSize(2);
            assertThat(positions).extracting(Position::symbol).containsExactlyInAnyOrder("AAPL", "GOOGL");
        }

        @Test
        void shouldReturnAllPortfolioIds() {
            // Trade in "A" and "B"
            // getPortfolioIds() should return both
            book.apply(buy("A", "AAPL", 100, 150.0));
            book.apply(buy("B", "AAPL", 50, 160.0));

            java.util.Set<String> portfolios = book.getPortfolioIds();
            assertThat(portfolios).containsExactlyInAnyOrder("A", "B");
        }
    }

    // ──────────────────────────────────────────────
    // Property-based tests (jqwik)
    // ──────────────────────────────────────────────

    @Property(tries = 200)
    void buyThenSellSameQuantity_shouldResultInFlatPosition(
            @ForAll @LongRange(min = 1, max = 10_000) long qty,
            @ForAll @DoubleRange(min = 0.01, max = 10_000.0) double price) {
        PositionBook freshBook = new SimplePositionBook();
        freshBook.apply(new Trade(System.nanoTime(), "port-1", "AAPL", Side.BUY, qty, price, Instant.now()));
        freshBook.apply(new Trade(System.nanoTime(), "port-1", "AAPL", Side.SELL, qty, price, Instant.now()));
        
        Position pos = freshBook.getPosition("port-1", "AAPL").orElseThrow();
        assertThat(pos.quantity()).isEqualTo(0L);
    }

    @Property(tries = 200)
    void quantityShouldNeverGoNegative_afterValidBuySellSequence(
            @ForAll @LongRange(min = 1, max = 1_000) long buyQty,
            @ForAll @LongRange(min = 1, max = 1_000) long sellQty,
            @ForAll @DoubleRange(min = 0.01, max = 10_000.0) double price) {
        // Only sell up to what we bought
        long actualSell = Math.min(buyQty, sellQty);
        PositionBook freshBook = new SimplePositionBook();
        freshBook.apply(new Trade(System.nanoTime(), "port-1", "AAPL", Side.BUY, buyQty, price, Instant.now()));
        freshBook.apply(new Trade(System.nanoTime(), "port-1", "AAPL", Side.SELL, actualSell, price, Instant.now()));
        
        Position pos = freshBook.getPosition("port-1", "AAPL").orElseThrow();
        assertThat(pos.quantity()).isGreaterThanOrEqualTo(0L);
    }
}
