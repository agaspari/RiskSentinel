# Phase 4 — Paper Broker + Fill Loop

**Goal:** Close the loop. Accepted proposals from the gateway become simulated fills that
flow back through the ingestion queue, updating the position book and snapshot cache, so
the next proposal is evaluated against the consequences of the last one.

**Timebox:** ~1–1.5 weeks of evenings.

**Deliverable:** A `PaperBroker` implementing `BrokerSink` that accepts proposals, produces
fills on its own executor, and pushes those fills into `RiskPipeline`'s existing trade
queue. A round-trip integration test demonstrates: propose → accept → fill → snapshot
reflects fill → next proposal sees updated state. All fill paths are concurrency-safe.

---

## Architectural Invariants (re-affirming CLAUDE.md)

1. **The broker is downstream of the gateway.** It only ever sees `Accept`ed proposals.
   No retry path re-enters the gateway with a mutated proposal — every retry is a *new*
   proposalId.
2. **No real money. Ever.** This is a simulator. The class is named `PaperBroker`. There
   is no real-broker subclass in this phase. Phase 6+ adds Alpaca behind the same
   interface, also clearly marked as paper-only.
3. **The broker is asynchronous, but the gateway is not.** `BrokerSink.submit` returns
   immediately; the fill arrives later on the broker's executor.
4. **The executor is explicitly configured.** Core size, max size, bounded queue,
   rejection policy. No `Executors.newCachedThreadPool()`.
5. **Order state is in a `ConcurrentHashMap`.** No `synchronized` on order lookup. State
   transitions use atomic CAS where they share writers.
6. **Fills are immutable records.** Same as `Trade`; in fact, a fill *is* a `Trade` once
   it lands in the ingestion queue.
7. **The clock is injectable.** Tests must be able to pin time without `Thread.sleep`.

---

## Vocabulary (so we don't get confused)

- **Proposal** — `TradeProposal`, the agent's *intent* (limit price, quantity, direction).
  Passes through the gateway. Has a `proposalId`.
- **Order** — internal state held by the broker after accepting a proposal. Tracks
  status (`NEW`, `FILLED`, `PARTIAL`, `REJECTED`, `CANCELLED`). Has its own `orderId`,
  distinct from `proposalId`, but always traceable back via `proposalId`.
- **Fill** — a `Trade` produced by the broker and pushed into the ingestion queue. Has
  its own `tradeId`. A fill carries `orderId` so the broker can settle the order state.
- **Settlement** — broker bookkeeping that marks an order `FILLED` (or `PARTIAL`) once
  its corresponding fill has been emitted.

---

## Task Breakdown

### Task 4.0 — `Order` + `OrderStatus` + `FillEvent` types

Internal-but-public types in a new package `core/broker/`.

**Spec:**

```java
package com.risksentinel.core.broker;

public enum OrderStatus {
    NEW,        // accepted by broker, not yet filled
    PARTIAL,    // some quantity filled, rest still working
    FILLED,     // fully filled
    REJECTED,   // broker refused (e.g. market closed, halted symbol)
    CANCELLED   // killed before fill
}

public record Order(
    String orderId,           // broker-generated, monotonic per-broker
    String proposalId,        // links back to the gateway-accepted proposal
    String portfolioId,
    String symbol,
    Side side,
    long quantity,
    double limitPrice,
    OrderStatus status,
    Instant submittedAt,
    Instant lastUpdatedAt
) {
    // Compact constructor: requireNonNull all fields, quantity > 0, limitPrice > 0,
    // submittedAt <= lastUpdatedAt.
}

public record FillEvent(
    long fillId,              // unique, monotonic
    String orderId,
    String proposalId,
    long filledQuantity,
    double filledPrice,
    Instant filledAt
) {
    // Compact constructor: filledQuantity > 0, filledPrice > 0.
}
```

`FillEvent` is the broker's outbound event. To re-enter the ingestion path it is
*translated* into a `Trade` by the broker itself (so the ingestion queue stays a `Trade`
queue and `TradeIngestor` remains unchanged).

**Tests:**
- `OrderRecordsTest` — validation on each field, monotonic timestamps enforced.
- `FillEventTest` — validation: zero or negative qty/price throws.

**Done when:** types compile, validation tests pass.

---

### Task 4.1 — `FillModel` strategy + `InstantFillModel`

The piece that decides *how* a `NEW` order becomes a `FillEvent`. We isolate the policy
so we can swap in slippage/partial-fill behaviors later without rewriting `PaperBroker`.

**Spec:**

```java
package com.risksentinel.core.broker;

/**
 * Pure function: given an order + the current instrument metadata + clock, decide
 * what fill (if any) to emit right now. Returning {@code Optional.empty()} means
 * "no fill yet — leave the order working."
 */
public interface FillModel {
    Optional<FillEvent> simulate(Order order, Instrument instrument, Instant now, LongSupplier fillIdGenerator);
}

/** Fills every order in full, at the order's limit price, immediately. */
public final class InstantFillModel implements FillModel { ... }
```

**Tests:**
- `shouldEmitFullFill_atLimitPrice` (BUY)
- `shouldEmitFullFill_atLimitPrice` (SELL)
- `shouldPreserveOrderId_andProposalId_inFill`
- `shouldUseProvidedFillIdGenerator`
- `shouldReturnEmpty_whenInstrumentUnknown` (defensive — broker should also pre-check)

**Done when:** model is a pure function with no executor, no I/O. ~30 LOC.

---

### Task 4.2 — `PaperBroker` core

The orchestrator. Implements `BrokerSink`. Owns the executor, the order book, the fill
emission path, and the fill-id sequence. Pushes simulated fills back into the
`RiskPipeline` ingestion queue via an injected sink.

**Spec:**

```java
package com.risksentinel.core.broker;

/** Where fills go after the broker emits them. RiskPipeline supplies one. */
public interface FillSink {
    void onFill(FillEvent event);
}

public final class PaperBroker implements BrokerSink {

    private final Map<String, Instrument> instrumentRegistry;
    private final FillModel fillModel;
    private final FillSink fillSink;
    private final ExecutorService executor;          // explicitly configured (see below)
    private final Clock clock;
    private final ConcurrentHashMap<String, Order> ordersByProposalId = new ConcurrentHashMap<>();
    private final AtomicLong fillIdSeq = new AtomicLong();
    private final AtomicLong orderIdSeq = new AtomicLong();

    public PaperBroker(
        Map<String, Instrument> instrumentRegistry,
        FillModel fillModel,
        FillSink fillSink,
        ExecutorService executor,
        Clock clock
    ) { ... }

    /** Synchronous: enqueue the proposal for async simulation. Returns immediately. */
    @Override
    public void submit(TradeProposal acceptedProposal) { ... }

    /** Snapshot of an order by proposalId, if known. */
    public Optional<Order> orderForProposal(String proposalId) { ... }
}
```

Key design choices baked in:

1. **Idempotency at the broker layer.** If the same `proposalId` is submitted twice
   (shouldn't happen — gateway already de-dupes — but defense in depth), use
   `ordersByProposalId.computeIfAbsent` so only one order is created.

2. **Executor configuration** — explicit, per CLAUDE.md:
   ```java
   new ThreadPoolExecutor(
       /*core*/ 2, /*max*/ 4,
       /*keepAlive*/ 30L, TimeUnit.SECONDS,
       new ArrayBoundedQueue<Runnable>(1024),
       Thread.ofPlatform().name("paper-broker-", 0).factory(),
       new ThreadPoolExecutor.AbortPolicy()    // overflow rejects loudly, not silently
   );
   ```
   Provided by a static factory `PaperBroker.defaultExecutor()` for convenience; tests
   can pass `Runnable::run` for synchronous execution.

3. **Fill emission path** (inside the executor task):
   - Look up `Instrument` from registry. Missing → mark order `REJECTED`, stop. Do not
     hallucinate a price.
   - Call `fillModel.simulate(...)`.
   - If `Optional.empty()`, leave order `NEW` (for future models). For Phase 4 with
     `InstantFillModel`, this branch is unreachable but coded defensively.
   - If a `FillEvent` returns, atomically transition `NEW → FILLED` and emit via
     `fillSink.onFill(event)`.

4. **Order state transitions** — guarded by `compute` on the map so transition + emission
   are observed in a consistent order. We do *not* use `synchronized` here; the
   `compute` block on `ConcurrentHashMap` already serializes per-key.

**Tests** (split across two test classes):

`PaperBrokerTest` (single-threaded, executor = `Runnable::run`):
- `shouldEmitFillForAcceptedProposal`
- `shouldEmitFill_withFillIdMonotonicallyIncreasing`
- `shouldMarkOrderRejected_whenInstrumentUnknown`
- `shouldNotDoubleSubmit_forDuplicateProposalId` (idempotency)
- `shouldRecordOrderWithStatusFilled_afterSimulationCompletes`

`PaperBrokerConcurrencyTest` (real executor):
- `shouldProcessAllProposals_underBurst` — 1,000 proposals submitted concurrently; 1,000
  fills delivered to a recording sink; no nulls, no duplicates by fillId.
- `shouldNotLoseFills_whenSinkIsSlow` — sink sleeps 1ms per call; with bounded queue +
  enough wait time, the broker still delivers every fill exactly once.
- `shouldRejectWithBackpressure_whenQueueFull` — submit far more than queue capacity
  with a slow sink; `AbortPolicy` produces a `RejectedExecutionException`. Documented
  behavior; ops dashboards will key off this in Phase 5.

**Done when:** all `PaperBrokerTest` and `PaperBrokerConcurrencyTest` cases pass.

---

### Task 4.3 — `RiskPipeline` wiring + closing the loop

Wire `PaperBroker` into `RiskPipeline` so:
- Accepted proposals go to the broker (`BrokerSink`).
- Broker-emitted fills go back to the existing trade queue (`FillSink` translates
  `FillEvent` → `Trade` and offers it to the queue).
- A new pipeline constructor takes a `FillModel` (default: `InstantFillModel`) and an
  optional broker executor.

**Spec:**

```java
public class RiskPipeline {
    // ... existing fields ...
    private final PaperBroker broker;

    public RiskPipeline(
        Map<String, Instrument> instrumentRegistry,
        GatewayLimits limits,
        FillModel fillModel,
        ExecutorService brokerExecutor,
        Clock clock
    ) {
        // ... existing ingest/gateway wiring ...
        this.broker = new PaperBroker(
            instrumentRegistry,
            fillModel,
            this::onFill,           // FillSink — translates FillEvent into Trade
            brokerExecutor,
            clock);
        this.brokerSink = broker;   // gateway now routes accepts to PaperBroker
    }

    /** Convenience overload preserving the old 3-arg constructor for existing tests. */
    public RiskPipeline(Map<String, Instrument> registry, GatewayLimits limits, BrokerSink legacySink) {
        // ... existing path, no broker ...
    }

    private void onFill(FillEvent event) {
        Trade fill = new Trade(
            event.fillId(),
            broker.orderForProposal(event.proposalId()).map(Order::portfolioId).orElseThrow(),
            broker.orderForProposal(event.proposalId()).map(Order::symbol).orElseThrow(),
            broker.orderForProposal(event.proposalId()).map(Order::side).orElseThrow(),
            event.filledQuantity(),
            event.filledPrice(),
            event.filledAt());
        submit(fill);
    }
}
```

Note: existing tests that construct `RiskPipeline(REGISTRY, limits(), broker)` with a
recording broker still compile and run — that's the legacy 3-arg constructor. The new
4–5-arg constructor is what production wiring uses.

**Tests** (new test class `PipelineFillLoopTest`):
- `shouldCloseTheLoop_proposalThenFillThenSnapshot` — submit accepted proposal,
  await fill delivery, verify position book updated, verify snapshot reflects fill.
- `shouldEvaluateNextProposalAgainstUpdatedSnapshot` — submit two proposals serially;
  after the first fills, the second's gateway evaluation sees the new position.
- `shouldNotRouteRejectedProposalToBroker` (regression — already in `RiskPipelineTest`,
  but worth confirming under the new wiring).

**Done when:** the round-trip integration test passes reliably with a real executor.

---

### Task 4.4 — Observability stubs

Just enough hooks for Phase 5 metrics to land cleanly later. No Micrometer yet.

**Spec:**
- `PaperBroker` exposes counters:
  - `submittedCount()`, `filledCount()`, `rejectedCount()`
  - Backed by `LongAdder`.
- `RiskPipeline` exposes a `getBroker()` accessor so ops/tests can read those counters.

**Tests:**
- `shouldIncrementCounters_underNormalFillFlow`
- `shouldIncrementRejectedCount_whenInstrumentUnknown`

**Done when:** counters move under load. Phase 5 will mirror these into Micrometer.

---

## Out of Scope for Phase 4

- **Partial fills, slippage models, market hours.** Hooks exist via `FillModel`. We
  ship one trivial implementation.
- **Cancel / replace.** No order amendment in Phase 4. A different proposalId is the
  only way to "amend" right now.
- **Persistence of orders or fills.** Decision audit log is Phase 6.
- **Real brokers (Alpaca).** Phase 6+.
- **Micrometer/Prometheus.** Phase 5.

---

## Risks to flag while coding

- **Re-entrancy on the trade queue.** `onFill` calls `submit(Trade)` which calls
  `tradeQueue.offer(...)`. The broker executor must *not* hold any lock that the
  ingestor holds. They are separate executors, separate queues — keep it that way.
- **Lost fills on shutdown.** If the broker executor is shut down with in-flight tasks,
  fills are lost. `RiskPipeline.stop()` must shut the broker down *first*, drain
  pending tasks, *then* stop the ingestor.
- **`getOrThrow` on order lookup inside `onFill`.** It is invariant-protected (the
  order is created in `submit` before the fill task is scheduled), but worth a unit
  test that races shutdown against fill delivery.

---

## How to Use This With Claude Code

For each task:
1. **You write the tests** for any concurrency-bearing path. Don't let Claude write
   both the test and the impl for `PaperBrokerConcurrencyTest`.
2. Tell Claude: `Implement [class] to pass [test file]. Follow CLAUDE.md.`
3. Review every line. Lookup paths, executor configuration, and lock acquisition order
   are the things that go wrong silently. Don't speed past them.
4. Commit per task: `feat(broker): Task 4.2 — PaperBroker core fill loop`.
