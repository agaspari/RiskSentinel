# Phase 1 — Domain + Single-Threaded Pipeline

**Goal:** All domain records defined. A naïve (non-concurrent) PositionBook. Simple risk
computation. An end-to-end pipeline that ingests a list of trades, updates positions, and
computes a risk snapshot. No concurrency yet — that's Phase 2.

**Timebox:** ~1 week of evenings.

**Deliverable:** `./gradlew test` passes. You can feed trades in and get a RiskSnapshot out.

---

## Task Breakdown

Each task is a self-contained unit. Complete them in order. Each has:
- **Spec**: the interface/contract to create
- **Tests**: write these FIRST, then implement
- **Implement**: this is where Claude Code earns its keep

---

### Task 1.1 — Project Scaffold

Set up the Gradle project with Java 21, JUnit 5, jqwik, and Mockito.
No business logic yet. Just verify `./gradlew test` runs a dummy test.

```
risk-sentinel/
├── build.gradle.kts
├── settings.gradle.kts
├── CLAUDE.md
├── phase1-spec.md
└── core/
    └── src/
        ├── main/java/com/risksentinel/core/
        └── test/java/com/risksentinel/core/
```

**Done when:** `./gradlew test` passes with one dummy test.

---

### Task 1.2 — Domain Records

Create the immutable domain model. These are the nouns of the system.

**Spec:**

```java
package com.risksentinel.core.domain;

// All fields non-null unless explicitly marked. Use Objects.requireNonNull in compact constructors.

public enum Side { BUY, SELL }

public record Instrument(
    String symbol,      // e.g. "AAPL", non-empty
    String sector,      // e.g. "Technology"
    String region,      // e.g. "US"
    double price         // > 0
) {}

public record Trade(
    long tradeId,        // unique, positive
    String portfolioId,  // non-empty
    String symbol,       // non-empty
    Side side,
    long quantity,       // > 0
    double price,        // > 0
    Instant timestamp
) {}

public record Position(
    String portfolioId,
    String symbol,
    long quantity,       // can be negative (short), zero after closing
    double avgCost,      // >= 0
    double marketValue   // quantity * current price, recomputed externally
) {}

public record RiskSnapshot(
    String snapshotId,
    String portfolioId,
    double netExposure,
    Map<String, Double> sectorExposure,   // unmodifiable
    Map<String, Double> regionExposure,   // unmodifiable
    double concentrationHHI,              // [0.0, 1.0]
    double parametricVaR95,
    double dailyPnL,
    Instant computedAt
) {}

// Used in Phase 4, but define now so downstream types compile.
public record TradeProposal(
    String proposalId,
    String portfolioId,
    String symbol,
    Side side,
    long quantity,
    double limitPrice,
    double expectedPrice,
    String rationale,
    double confidence,     // [0.0, 1.0]
    String snapshotId,
    Instant proposedAt
) {}

// Internal key type for PositionBook
record PortfolioSymbol(String portfolioId, String symbol) {}
```

**Tests to write:**
- Validation: constructing a Trade with null portfolioId throws
- Validation: constructing a Trade with quantity <= 0 throws
- Validation: constructing an Instrument with price <= 0 throws
- RiskSnapshot maps are unmodifiable (mutating them throws UnsupportedOperationException)
- PortfolioSymbol equals/hashCode contract (equal inputs → equal keys)

**Done when:** All records compile and validation tests pass.

---

### Task 1.3 — Naïve PositionBook (Single-Threaded)

A simple, non-concurrent position tracker. Phase 2 replaces this with the lock-striped version.

**Spec:**

```java
package com.risksentinel.core.positions;

public interface PositionBook {

    /**
     * Apply a trade fill to update the position for (portfolioId, symbol).
     *
     * BUY:  increases quantity, updates avgCost as weighted average.
     * SELL: decreases quantity. If quantity goes to zero, position is flat.
     *       avgCost does not change on sells.
     *
     * @throws IllegalArgumentException if trade has invalid fields
     */
    void apply(Trade trade);

    /** Current position, or empty if no trades for this key. */
    Optional<Position> getPosition(String portfolioId, String symbol);

    /** All positions for a portfolio. Never null, may be empty. */
    Collection<Position> getPositions(String portfolioId);

    /** All portfolio IDs that have at least one position. */
    Set<String> getPortfolioIds();
}
```

**Tests to write (YOU write these — they encode the position math):**
- `shouldCreateNewPosition_whenFirstBuyForSymbol`
  - Buy 100 AAPL @ $150 → Position(qty=100, avgCost=150.0)
- `shouldAccumulatePosition_whenMultipleBuys`
  - Buy 100 @ $150, then Buy 50 @ $160 → qty=150, avgCost=153.33
- `shouldReducePosition_whenSelling`
  - Buy 100 @ $150, Sell 40 → qty=60, avgCost=150.0 (unchanged on sell)
- `shouldGoFlat_whenSellingEntirePosition`
  - Buy 100, Sell 100 → qty=0
- `shouldReturnEmpty_whenNoTradesForSymbol`
- `shouldTrackMultiplePortfoliosIndependently`
  - Trades to portfolio "A" don't affect portfolio "B"
- `shouldTrackMultipleSymbolsIndependently`
  - AAPL trades don't affect GOOGL positions in same portfolio
- **Property test (jqwik):** `forAll trades: apply(buy N) then apply(sell N) → position is flat`
- **Property test (jqwik):** `forAll trades: quantity is always >= 0 after any valid sequence of buys and sells`
  (Note: this second property only holds if we disallow naked shorts for now — add a design decision flag)

**Done when:** All position math tests pass. The PositionBook correctly handles buys, sells, multi-portfolio, multi-symbol.

---

### Task 1.4 — Risk Engine (Single-Threaded)

Computes a RiskSnapshot from the current positions and instrument data.

**Spec:**

```java
package com.risksentinel.core.risk;

public interface RiskEngine {

    /**
     * Compute a risk snapshot for the given portfolio using current positions
     * and instrument metadata.
     *
     * Net exposure     = sum(position.quantity * instrument.price) for all positions
     * Sector exposure  = grouped sum by instrument.sector
     * Region exposure  = grouped sum by instrument.region
     * Concentration    = HHI = sum of (weight_i)^2 where weight_i = |position_value_i| / gross_exposure
     * VaR 95           = placeholder: 1.65 * portfolio_std_dev (use supplied volatilities)
     * Daily PnL        = net_exposure_now - net_exposure_at_open (needs an opening snapshot)
     */
    RiskSnapshot compute(String portfolioId,
                         Collection<Position> positions,
                         Map<String, Instrument> instruments);
}
```

**Tests to write:**
- `shouldComputeNetExposure_singlePosition`
  - 100 AAPL @ $150 → netExposure = 15000.0
- `shouldComputeNetExposure_longAndShort`
  - 100 AAPL @ $150, -50 GOOGL @ $100 → netExposure = 15000 - 5000 = 10000
- `shouldComputeSectorExposure_groupedCorrectly`
  - AAPL (Tech, $15000) + GOOGL (Tech, $5000) + JPM (Finance, $10000)
  - → sectorExposure = {Technology: 20000, Finance: 10000}
- `shouldComputeConcentrationHHI`
  - Single position → HHI = 1.0 (maximum concentration)
  - Two equal positions → HHI = 0.5
- `shouldReturnZeroExposure_whenNoPositions`
- **Property test:** `netExposure == sum of all position quantities * prices` (recomputed independently)
- **Property test:** `HHI is always in [0.0, 1.0]`
- **Property test:** `sum of sector exposures == gross exposure`

**Done when:** Risk math is correct and property tests hold.

---

### Task 1.5 — Pipeline Wiring (End-to-End)

Wire PositionBook + RiskEngine into a simple pipeline that processes a list of trades and outputs a snapshot.

**Spec:**

```java
package com.risksentinel.core;

public class RiskPipeline {

    private final PositionBook positionBook;
    private final RiskEngine riskEngine;
    private final Map<String, Instrument> instrumentRegistry;

    /**
     * Ingest a batch of trades, update positions, compute risk.
     * Returns snapshots for all affected portfolios.
     */
    public Map<String, RiskSnapshot> processBatch(List<Trade> trades);
}
```

**Tests to write:**
- `shouldProduceSnapshot_afterSingleTrade`
- `shouldProduceSnapshots_forMultiplePortfolios`
- `shouldReflectAllTrades_inFinalSnapshot`
  - Feed 5 trades for the same portfolio/symbol, verify the snapshot reflects the final state
- **Integration test:** Load a small CSV of trades, process, verify snapshot values against hand-calculated expected results

**Done when:** You can feed trades in and get correct RiskSnapshots out. Phase 1 complete.

---

## How to Use This With Claude Code

For each task:

1. **You create the test file** with the test methods stubbed out (or fully written — better).
2. Tell Claude Code: `Implement [interface] to pass the tests in [test file]. Follow CLAUDE.md conventions.`
3. Review every line of the implementation. Ask yourself: "could I explain this in an interview?"
4. If you can't explain it, rewrite it yourself or ask Claude Code to explain its choices.
5. Commit with a message linking to the task: `feat(domain): Task 1.2 — domain records with validation`

For property tests specifically: describe the property in English, then either write the jqwik
annotation yourself or have Claude Code generate it — but make sure you understand the generator
and the assertion before committing.

## What Phase 2 Changes

Phase 2 replaces the naïve PositionBook with the lock-striped concurrent version, adds
BlockingQueue-based ingestion, and introduces the first concurrency tests. The interface
stays the same — only the implementation changes. That's the payoff of defining the interface
first in Phase 1.
