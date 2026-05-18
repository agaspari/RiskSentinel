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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class ConcurrentPositionBookTest {

    private PositionBook book;

    @BeforeEach
    void setUp() {
        book = new ConcurrentPositionBook();
    }

    private Trade buy(String portfolio, String symbol, long qty, double price) {
        return new Trade(System.nanoTime(), portfolio, symbol, Side.BUY, qty, price, Instant.now());
    }

    private Trade sell(String portfolio, String symbol, long qty, double price) {
        return new Trade(System.nanoTime(), portfolio, symbol, Side.SELL, qty, price, Instant.now());
    }

    @Test
    @DisplayName("Multiple threads hammering the same portfolio should not corrupt state")
    void concurrentHammering() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        book.apply(buy("port-concurrent", "AAPL", 10, 150.0));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // unleash threads
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Position pos = book.getPosition("port-concurrent", "AAPL").orElseThrow();
        long expectedQty = (long) threadCount * operationsPerThread * 10;
        assertThat(pos.quantity()).isEqualTo(expectedQty);
        assertThat(pos.avgCost()).isEqualTo(150.0);
    }

    @Nested
    @DisplayName("Single-symbol position lifecycle")
    class SingleSymbol {

        @Test
        void shouldCreateNewPosition_whenFirstBuyForSymbol() {
            book.apply(buy("port-1", "AAPL", 100, 150.0));

            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();
            assertThat(pos.quantity()).isEqualTo(100L);
            assertThat(pos.avgCost()).isEqualTo(150.0);
        }

        @Test
        void shouldAccumulatePosition_whenMultipleBuys() {
            book.apply(buy("port-1", "AAPL", 100, 150.0));
            book.apply(buy("port-1", "AAPL", 50, 160.0));

            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();
            assertThat(pos.quantity()).isEqualTo(150L);
            assertThat(pos.avgCost()).isCloseTo(153.333, within(0.001));
        }

        @Test
        void shouldReducePosition_whenSelling() {
            book.apply(buy("port-1", "AAPL", 100, 150.0));
            book.apply(sell("port-1", "AAPL", 40, 100.0));

            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();

            assertThat(pos.quantity()).isEqualTo(60L);
            assertThat(pos.avgCost()).isEqualTo(150.0);
        }

        @Test
        void shouldGoFlat_whenSellingEntirePosition() {
            book.apply(buy("port-1", "AAPL", 100, 150.0));
            book.apply(sell("port-1", "AAPL", 100, 100.0));

            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();

            assertThat(pos.quantity()).isEqualTo(0L);
        }

        @Test
        void shouldPreserveAvgCost_afterPartialSell() {
            book.apply(buy("port-1", "AAPL", 100, 150.0));
            book.apply(sell("port-1", "AAPL", 30, 150.0));
            Position pos = book.getPosition("port-1", "AAPL").orElseThrow();
            assertThat(pos.quantity()).isEqualTo(70L);
            assertThat(pos.avgCost()).isEqualTo(150.0);

            book.apply(buy("port-1", "AAPL", 50, 170.0));
            pos = book.getPosition("port-1", "AAPL").orElseThrow();
            assertThat(pos.quantity()).isEqualTo(120L);
            assertThat(pos.avgCost()).isCloseTo(158.333, within(0.001));
        }
    }

    @Nested
    @DisplayName("Portfolio and symbol isolation")
    class Isolation {

        @Test
        void shouldTrackMultiplePortfoliosIndependently() {
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
            book.apply(buy("A", "AAPL", 100, 150.0));
            book.apply(buy("A", "GOOGL", 50, 100.0));

            Position posAAPL = book.getPosition("A", "AAPL").orElseThrow();
            Position posGOOGL = book.getPosition("A", "GOOGL").orElseThrow();

            assertThat(posAAPL.quantity()).isEqualTo(100L);
            assertThat(posGOOGL.quantity()).isEqualTo(50L);
        }
    }

    @Nested
    @DisplayName("Query behavior")
    class Queries {

        @Test
        void shouldReturnEmpty_whenNoTradesForSymbol() {
            assertThat(book.getPosition("A", "AAPL")).isEmpty();
        }

        @Test
        void shouldReturnAllPositions_forPortfolio() {
            book.apply(buy("A", "AAPL", 100, 150.0));
            book.apply(buy("A", "GOOGL", 50, 100.0));

            Collection<Position> positions = book.getPositions("A");
            assertThat(positions).hasSize(2);
            assertThat(positions).extracting(Position::symbol).containsExactlyInAnyOrder("AAPL", "GOOGL");
        }

        @Test
        void shouldReturnAllPortfolioIds() {
            book.apply(buy("A", "AAPL", 100, 150.0));
            book.apply(buy("B", "AAPL", 50, 160.0));

            java.util.Set<String> portfolios = book.getPortfolioIds();
            assertThat(portfolios).containsExactlyInAnyOrder("A", "B");
        }
    }

    @Property(tries = 200)
    void buyThenSellSameQuantity_shouldResultInFlatPosition(
            @ForAll @LongRange(min = 1, max = 10_000) long qty,
            @ForAll @DoubleRange(min = 0.01, max = 10_000.0) double price) {
        PositionBook freshBook = new ConcurrentPositionBook();
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
        long actualSell = Math.min(buyQty, sellQty);
        PositionBook freshBook = new ConcurrentPositionBook();
        freshBook.apply(new Trade(System.nanoTime(), "port-1", "AAPL", Side.BUY, buyQty, price, Instant.now()));
        freshBook.apply(new Trade(System.nanoTime(), "port-1", "AAPL", Side.SELL, actualSell, price, Instant.now()));
        
        Position pos = freshBook.getPosition("port-1", "AAPL").orElseThrow();
        assertThat(pos.quantity()).isGreaterThanOrEqualTo(0L);
    }
}
