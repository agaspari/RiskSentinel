# Phase 10 — Backtest Harness (`eval/` module)

**Goal:** Build the deterministic backtest layer of the three-layer evaluation harness from CLAUDE.md goal #4. Replay bar data through `Strategy → PreTradeGateway → PaperBroker → ConcurrentPositionBook`, deterministically, and produce a `BacktestReport` summarizing what happened. The harness must be the regression-grade evidence that the gateway holds under realistic trade load across many bars and across multiple symbols — not just the per-decision tests already in `core/`.

**Timebox:** ~1–2 weeks of evenings.

**Deliverable:** New `eval/` Gradle module. `Bar` record, `MarketDataSource` (synthetic generator + CSV reader), `Strategy` interface with three shipped implementations (`BuyAndHoldStrategy`, `MeanReversionStrategy`, `FatFingerStrategy`), `BacktestRunner` orchestrating a deterministic bar loop, `BacktestReport` with accept/reject breakdown and latency stats, and a jqwik property test asserting that no risk invariant is violated under random bars × random strategy actions. All backtest infrastructure runs single-threaded on the test thread via `Runnable::run` as the broker executor — async behavior is exercised elsewhere; the backtest needs *reproducible* output.

---

## Architectural Invariants (re-affirming CLAUDE.md)

1. **The gateway is the only enforcement point.** The backtest runner does **not** simulate risk limits, position caps, or kill switches itself — it calls the real `PreTradeGateway.decide` and respects its decision. If a strategy can violate an invariant in the backtest report, that's a gateway bug, not a runner bug.
2. **Determinism is the headline feature.** Same seed + same strategy + same bars ⇒ byte-identical report. No `Thread.sleep`, no async, no wall-clock dependencies, no `ConcurrentHashMap` iteration in the assertion path.
3. **No LLM in the backtest path.** Strategies in this phase are deterministic Java. A future phase may add `AnalystStrategy` that wraps `AnalystAgent`, but it lives outside `eval/` and is not part of the v1 deliverable.
4. **The `eval/` module is a peer of `analyst/`, not a dependency.** `eval/` depends on `:core` only. It does not depend on `:mcp-bridge` or `:analyst` — strategies submit `TradeProposal`s directly to the gateway, no tool dispatch involved.
5. **Caller for backtest decisions is `Caller.system()`.** The strategy is internal infrastructure, not untrusted input. ACL exercise stays in `TrustBoundaryEvalTest`; the backtest is about gateway/risk-engine/position-book correctness over time.

---

## Design — the new types

```java
// eval/.../data
public record Bar(
        String symbol,
        Instant timestamp,      // start of the bar
        double open,
        double high,
        double low,
        double close,
        long volume
) {
    public Bar { /* compact ctor: non-null + non-blank symbol; high >= low; high >= open,close; low <= open,close; volume >= 0 */ }
}

public interface MarketDataSource extends Iterable<Bar> {
    // Bars are returned in non-decreasing timestamp order across all symbols.
    // A source that returns bars out of order is a defect; the runner does not sort.
}

// eval/.../strategy
public interface Strategy {
    String name();
    List<TradeProposal> onBar(Bar bar, RiskSnapshot snapshot, Clock clock);
}

// eval/.../runner
public final class BacktestRunner {
    public BacktestReport run(MarketDataSource data, Strategy strategy);
}

// eval/.../report
public record BacktestReport(
        String strategyName,
        Instant startedAt,
        Instant endedAt,
        int barsProcessed,
        int totalProposals,
        int accepted,
        int rejected,
        Map<String, Integer> rejectsByCode,    // code → count
        Map<String, Long> endingPositionBySymbol,
        double endingMarkToMarketPnl,           // closing price × ending position − cost basis
        LatencyStats gatewayLatency             // p50/p95/p99 over all gateway decisions, HdrHistogram-backed
) { }
```

**Backtest system wiring** lives in a small `BacktestSystem` factory inside `eval/`. It builds the same components the production code uses, with three swaps:
- `ExecutorService = Runnable::run` (direct execution — submit returns after the fill is applied)
- `Clock = MutableClock` (the runner updates it per bar)
- `FillModel = BarPriceFillModel` (fills at the *current bar's close* if the order's limit price would cross — else no fill this bar)

`BarPriceFillModel` is new in this phase but lives in `core/broker/` (not `eval/`), because it's a generally useful fill model and `core/` is the broker's home. It needs a way to read "the current bar's close for symbol X" — solved by a `Supplier<Map<String, Double>>` injected into the model, which the runner updates each bar.

---

## Task Breakdown

### Task 10.0 — Module bootstrap + market data

`settings.gradle.kts` — `include("eval")`.

`eval/build.gradle.kts` — depends on `:core`, JUnit 5, AssertJ, jqwik, HdrHistogram (already transitive via core).

`eval/src/main/java/com/risksentinel/eval/data/`:
- `Bar.java` — record with compact-ctor validation (symbol non-blank; high ≥ low, open, close; low ≤ open, close; volume ≥ 0; timestamp non-null).
- `MarketDataSource.java` — interface extending `Iterable<Bar>`.
- `InMemoryMarketData.java` — wraps an immutable list; constructor sorts by timestamp and throws if any bar fails its compact-ctor validation (already enforced by Bar, but document the ordering contract).
- `SyntheticBarGenerator.java` — geometric Brownian motion generator. Constructor: `(long seed, List<String> symbols, Instant startTime, Duration barInterval, int numBars, double drift, double volatility, Map<String,Double> initialPrices)`. Produces `numBars` per symbol; bars across symbols are interleaved in timestamp order. Uses `java.util.Random` (not `SecureRandom` — we want reproducibility) seeded explicitly.
- `CsvMarketDataSource.java` — reads CSV with header `symbol,timestamp_iso,open,high,low,close,volume`. Streams (does not load whole file). Throws on malformed rows with a line number. ~50 lines. No fancy CSV: no quoted fields, no embedded commas.

**Tests:**
- `BarTest` — compact-ctor validation (each bad-field case is its own `should…_when…` test).
- `InMemoryMarketDataTest` — iteration order; constructor preserves equal-timestamp insertion order (stable sort).
- `SyntheticBarGeneratorTest` — same seed → byte-identical bars; bar OHLC self-consistent; cross-symbol interleaving is timestamp-monotonic.
- `CsvMarketDataSourceTest` — parses a 3-row CSV; rejects a row with `high < low`; rejects malformed numbers with a line number in the message.

**Done when:** `eval/` module compiles and is reachable from `gradle :eval:test`. All four test classes pass.

---

### Task 10.1 — `Strategy` interface and three implementations

`eval/src/main/java/com/risksentinel/eval/strategy/`:

`Strategy.java` — interface as shown above. Documented: "implementations may keep internal mutable state; the runner guarantees serial calls."

`BuyAndHoldStrategy.java`:
- Constructor: `(String portfolioId, String symbol, long quantity)`.
- First `onBar` call: emit one BUY proposal at the bar's close price for the configured quantity. Subsequent calls: empty list.
- Internal state: a boolean `bought`.

`MeanReversionStrategy.java`:
- Constructor: `(String portfolioId, String symbol, int windowSize, double zScoreThreshold, long tradeSize)`.
- Keeps a sliding window of the last `windowSize` closing prices. When the window is full and `|currentClose − mean| / stddev > zScoreThreshold`:
  - Price below mean → BUY `tradeSize` at close.
  - Price above mean → SELL `tradeSize` at close.
- Does not check current position — that's the gateway's job. (If we end up short the position cap, the gateway rejects.)

`FatFingerStrategy.java` — adversarial:
- Constructor: `(String portfolioId, String symbol, long obviouslyTooLargeQuantity)`.
- Every bar: emit one BUY proposal at the bar's close for `obviouslyTooLargeQuantity`.
- This strategy *should* see 100% reject rate with `FAT_FINGER_QUANTITY`. If it doesn't, the gateway is broken.

`ProposalIds.java` (package-private utility) — generates deterministic proposal IDs from `(strategyName, bar.timestamp(), bar.symbol(), sequence)`. So same seed → same proposal IDs → byte-identical audit records. No UUIDs.

**Tests:**
- `BuyAndHoldStrategyTest` — first bar yields one proposal; bars 2..N yield empty.
- `MeanReversionStrategyTest` — feeding a sequence ending in a clear dip yields one BUY at the expected size; feeding a clear spike yields one SELL; flat input yields no proposals.
- `FatFingerStrategyTest` — every bar produces a proposal with the configured (huge) quantity.

**Done when:** strategies are pure (no I/O, no globals) and reproducibly emit the expected proposals.

---

### Task 10.2 — `BacktestRunner` + `MutableClock` + `BarPriceFillModel`

`core/src/main/java/com/risksentinel/core/ops/MutableClock.java`:
- Extends `java.time.Clock`. Field `volatile Instant now`. Methods `setNow(Instant)`, `instant()`, `getZone() → ZoneOffset.UTC`, `withZone(z) → this`.
- Tests: `MutableClockTest` covers reading the latest write across threads (publish via `volatile`), and `withZone` does not break the contract.

`core/src/main/java/com/risksentinel/core/broker/BarPriceFillModel.java`:
- Constructor: `Supplier<Map<String,Double>> currentClosesBySymbol`.
- `simulate(order, instrument, now, fillIdGen)`:
  - Look up the current close for the order's symbol.
  - If absent → `Optional.empty()` (no fill this bar; order stays NEW).
  - If `Side.BUY` and `close ≤ order.limitPrice()` → fill at `close`.
  - If `Side.SELL` and `close ≥ order.limitPrice()` → fill at `close`.
  - Else → `Optional.empty()`.
- Tests: `BarPriceFillModelTest` — BUY fills when close crosses limit, doesn't otherwise; SELL symmetric; missing symbol → empty.

`eval/src/main/java/com/risksentinel/eval/runner/`:

`BacktestSystem.java`:
- Factory that builds and exposes: `PreTradeGateway`, `PaperBroker`, `ConcurrentPositionBook`, `ConcurrentRiskSnapshotCache`, `MutableClock`, `InMemoryAuditLog` (already exists), `currentClosesRef` (an `AtomicReference<Map<String,Double>>` consumed by the `BarPriceFillModel`).
- Constructor takes: `Map<String,Instrument> instruments`, `GatewayLimits limits`. Everything else is internal default.
- Wires the broker with `Runnable::run` as its executor.

`BacktestRunner.java`:
- Constructor: `(BacktestSystem system)`.
- `run(MarketDataSource data, Strategy strategy) → BacktestReport`:
  - Initialize counters, an `HdrHistogram` for gateway latency, `Instant startedAt = clock.instant()`.
  - For each bar in `data`:
    1. Advance `MutableClock` to `bar.timestamp()`.
    2. Update `currentClosesRef` by overlaying `bar.symbol() → bar.close()`.
    3. Get the current snapshot (`snapshots.current(portfolioId)` — strategy supplies portfolioId via its proposals; for snapshot read, the runner uses the strategy's declared `portfolioId()` if the interface exposes one — see below).
    4. Call `strategy.onBar(bar, snapshot, clock)`.
    5. For each proposal: time `gateway.decide(proposal, Caller.system())` with `System.nanoTime()`, record to histogram, increment counters, and on `Accept` call `broker.submit(proposal)`. Because the broker runs on `Runnable::run`, the fill is applied to the position book before `submit` returns.
    6. Bar processed counter++.
  - At end: compute ending positions per symbol (read from `PositionBook`); compute mark-to-market PnL using last seen close per symbol minus weighted-average cost (tracked via fill events — runner subscribes a small cost-basis listener to `FillSink`).
  - Return a `BacktestReport`.
- Hard limit: if `totalProposals > 1_000_000` the runner throws — backtests shouldn't generate that many proposals; runaway strategy is a defect.

**Strategy `portfolioId()` decision:** A backtest is single-portfolio in v1. Add `default String portfolioId() { return null; }` on `Strategy` — if non-null, the runner uses it for the snapshot read; if null, the runner reads `proposal.portfolioId()` from the first proposal it sees and uses that thereafter. This keeps single-portfolio strategies clean while not closing the door on multi-portfolio.

**Tests:**
- `BacktestRunnerTest`:
  - `shouldProduceDeterministicReport_whenRunTwice` — run the same `(seed, bars, strategy)` twice, assert reports are equal field-for-field.
  - `shouldFillBuyAndHold_byEndOfRun` — run BuyAndHold over 10 bars, ending position equals configured quantity, mark-to-market PnL = (lastClose − firstClose) × quantity.
  - `shouldRejectAllFatFingers_byGateway` — run FatFinger over 20 bars, `rejected == 20` and `rejectsByCode["FAT_FINGER_QUANTITY"] == 20`.
  - `shouldApplyFillSynchronously_whenSubmitReturns` — assert position book is updated *before* the runner moves to the next bar. (This is the regression test for "broker actually ran on `Runnable::run`.")
- `MutableClockTest` and `BarPriceFillModelTest` as listed above.

**Done when:** running `BuyAndHoldStrategy` over `SyntheticBarGenerator` produces a hand-checkable PnL; running `FatFingerStrategy` shows 100% rejection.

---

### Task 10.3 — `BacktestReport` + `LatencyStats`

`eval/src/main/java/com/risksentinel/eval/report/`:

`LatencyStats.java` — record `(long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos, long count)`. Static factory `fromHistogram(Histogram h)`.

`BacktestReport.java` — record as shown in the design section. Compact ctor: `accepted + rejected == totalProposals`; `barsProcessed >= 0`; `endedAt >= startedAt`; `rejectsByCode` and `endingPositionBySymbol` defensively copied to `Map.copyOf(...)` to keep the record immutable.

`BacktestReport.toMarkdown()` — pretty-print method that produces a human-readable summary. Not for parsing; just for "what did this run actually do." Tested at the line level.

**Tests:**
- `BacktestReportTest` — compact-ctor validation; `toMarkdown()` includes the strategy name, total bars, accept/reject counts, ending PnL, and the latency percentiles.
- `LatencyStatsTest` — `fromHistogram` returns the expected percentiles for a known input.

**Done when:** the runner from Task 10.2 returns a fully-populated `BacktestReport`.

---

### Task 10.4 — jqwik property test for invariant preservation

`eval/src/test/java/com/risksentinel/eval/property/GatewayInvariantPropertyTest.java`:

Property: given any seeded `SyntheticBarGenerator` and any seeded "noisy strategy" (a strategy that emits random-quantity, random-side proposals per bar — bounded so it sometimes fits gateway limits and sometimes doesn't), running a backtest **never** ends with a position book state that violates any of:

1. `|position(symbol)| ≤ limits.maxPositionPerSymbol` for every symbol.
2. `grossExposure(portfolio) ≤ limits.maxGrossExposure` (after every accepted fill — not just at the end).
3. `dailyTurnover(portfolio) ≤ limits.maxDailyTurnover` (same — after every fill).

Approach:
- jqwik `@Property` with `@ForAll long seed`, `@ForAll @IntRange(min=10, max=200) int numBars`, `@ForAll @IntRange(min=1, max=5) int numSymbols`.
- Tries=50 (configurable in `jqwik.properties` if it's too slow).
- Inside the property: build `BacktestSystem` with tight `GatewayLimits`, build `NoisyStrategy(seed, symbols, maxProposalsPerBar=3, maxQuantity=2× limit.maxPositionPerSymbol)`, run the backtest, then assert all three invariants on the final state. Additionally, a `FillSink` listener mid-run asserts the invariants after every fill — not just at the end (this catches a "gateway accepted but oversize fill applied" bug).
- If the property finds a counterexample, the test logs the seed and the `BacktestReport.toMarkdown()`.

`NoisyStrategy.java` (lives under `eval/src/test/java/` — it's a test fixture, not shipped):
- Deterministic given its seed. Emits 0..maxProposalsPerBar proposals each bar; each is a random side/quantity within configured bounds.

**Tests:**
- `GatewayInvariantPropertyTest.shouldNeverViolateAnyRiskInvariant_underAnyStrategy` — the property.
- `NoisyStrategyTest` — same seed → same proposals (the property test isn't reproducing if the strategy isn't deterministic).

**Done when:** the property runs green at tries=50. If it fails, that's a real gateway/risk bug to fix before merging.

---

## Out of Scope

- Real market-data sources (yfinance / IEX / Alpaca historical). The CSV reader is intentionally minimal — point it at a pre-cleaned file. Real-feed adapters are Phase 11+.
- Paper trading against an external broker. That's Phase 11.
- LLM-in-the-loop backtests. `AnalystStrategy` wrapping `AnalystAgent` is interesting but expensive and non-deterministic; a future, separate phase if it earns its place.
- Optimization / parameter sweeps. The runner takes one strategy and produces one report. Multi-run drivers (e.g. "sweep z-score threshold from 1.0 to 3.0") are a small wrapper on top, not in this phase.
- Slippage, partial fills, fee modeling beyond what `BarPriceFillModel` does. The fill model is intentionally simple — backtest PnL is a sanity check, not a realistic broker simulation.
- Multi-day trading sessions / market-hours gating. Bars are bars; the runner treats them uniformly.

---

## Bug surfaced during implementation (now fixed)

The property test discovered a pre-existing `PositionBook.apply` bug shared
by both `SimplePositionBook` and `ConcurrentPositionBook`: when a BUY trade
crossed a short position back to long, the weighted-average formula yielded
a negative `avgCost`, which `Position`'s compact ctor rejected.

Fix (committed as part of Phase 10): extracted accounting to
`PositionMath.newAvgCost(oldQty, oldAvgCost, trade, newQty)` with four rules:
1. `newQty == 0` → cost is moot, return 0.0.
2. `oldQty == 0` OR sign flipped → basis is the trade price (fresh open / cross).
3. Same direction, grows → weighted average.
4. Same direction, shrinks → basis unchanged.

`NoisyStrategy` is no longer BUY-only; the property exercises both sides
under random seeds and still finds zero invariant violations at 50 tries.
New test cases for the short-side and zero-crossing paths live in
`PositionBookTest$ShortAndCross` and `ConcurrentPositionBookTest$ShortAndCross`.

---

## Risks

- **`BarPriceFillModel` lives in `core/broker/` but is only used by `eval/` for now.** Acceptable: it's the broker's job to know about fill strategies, and a future paper-trading layer is the obvious second user. If no second user materializes by Phase 12, revisit.
- **`MutableClock` introduces mutability into a path that was previously immutable (the gateway holds a `Clock` for the lifetime of the process).** The risk is that production code accidentally takes a `MutableClock` and re-points it mid-trade. Mitigation: `MutableClock` lives in `core/ops/` with a Javadoc that says "intended for backtests and tests only; do not inject into a running production gateway." It's a constructor argument, not a singleton, so this is hard to do by accident.
- **The runner's hard cap of 1M proposals could be surprising.** Documented in the Javadoc; the property test stays well under it.
- **HdrHistogram allocations.** The runner constructs one histogram per `run()` call. Per-decision recording is allocation-free (writes to a long array). No concerns for the backtest scale (millions of records is fine).
- **Property test flakiness if a strategy hits a degenerate seed.** Property tests are deterministic given a seed; if a seed produces a counterexample, that's a real bug, not flake. Failures get logged with the seed for easy reproduction.

---

## Build deps

No new external deps. jqwik is already on the classpath in test scope (per CLAUDE.md tech stack). HdrHistogram is already used by `LatencyRecorder`.
