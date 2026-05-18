# Phase 3 — Pre-Trade Risk Gateway

**Goal:** A synchronous, deterministic gateway that decides ACCEPT or REJECT for every
`TradeProposal` before it can reach the broker. The gateway is the trust boundary: no agent,
no test backdoor, no admin flag bypasses it. Validation never blocks, never makes external
calls, and never acquires position-book stripe locks.

**Timebox:** ~1–1.5 weeks of evenings.

**Deliverable:** `gateway.decide(proposal)` returns a `GatewayDecision` (`Accept` or
`Reject`) in microseconds, deterministically, against a lock-free snapshot read. All
checks, kill switch, and idempotency tests pass. End-to-end pipeline accepts proposals
through the gateway and routes accepted ones to a stub broker sink.

---

## Architectural Invariants (re-affirming CLAUDE.md)

1. **Every proposal passes the gateway.** No special modes, no test backdoors.
2. **Gateway is synchronous and deterministic.** No `Thread.sleep`, no I/O, no LLM call,
   no `CompletableFuture` in the validation path.
3. **Gateway never acquires stripe locks.** All state reads come from `RiskSnapshotCache`
   (`AtomicReference.get()` — wait-free) or from `GatewayState` (`AtomicBoolean` /
   `ConcurrentHashMap`).
4. **Checks are pure functions** of `(proposal, snapshot, limits, state)`. Stateless
   classes. Side-effect free except for `IdempotencyCheck`, which performs a single CAS
   in `GatewayState`.
5. **The gateway decision collects ALL failing reasons,** not just the first. The agent
   needs to know every reason it lost so it can self-correct. The cost is sub-microsecond.

---

## Task Breakdown

### Task 3.0 — Extend `RiskSnapshot` with per-symbol positions

Snapshot needs to carry the per-symbol position data so the gateway can answer size and
fat-finger questions without touching `PositionBook`.

**Spec change:**

```java
public record RiskSnapshot(
    String snapshotId,
    String portfolioId,
    double netExposure,
    double grossExposure,                       // NEW — needed by NotionalExposureCheck
    Map<String, Position> positions,            // NEW — symbol -> Position (unmodifiable)
    Map<String, Double> sectorExposure,
    Map<String, Double> regionExposure,
    double concentrationHHI,
    double parametricVaR95,
    double dailyPnL,
    Instant computedAt
) {
    // Compact constructor adds: positions = Map.copyOf(positions); grossExposure >= 0 check.
}
```

`SimpleRiskEngine` now also populates `positions` (a copy of the input collection keyed
by symbol) and `grossExposure` (already computed internally — just expose it).

**Tests:**
- `shouldExposePositionsMap_afterCompute` — engine output contains every position passed in
- `shouldHaveUnmodifiablePositionsMap` — mutating throws `UnsupportedOperationException`
- `shouldExposeGrossExposure_equalToSumOfAbsValues`

**Done when:** all existing tests still pass, new fields are populated and validated.

---

### Task 3.1 — `GatewayDecision` sealed type + `RejectReason`

The return type from the gateway. Sealed so the compiler exhausts all cases.

**Spec:**

```java
package com.risksentinel.core.gateway;

public sealed interface GatewayDecision permits GatewayDecision.Accept, GatewayDecision.Reject {

    String proposalId();
    Instant decidedAt();

    record Accept(String proposalId, String snapshotId, Instant decidedAt) implements GatewayDecision {}

    record Reject(String proposalId, List<RejectReason> reasons, Instant decidedAt) implements GatewayDecision {
        public Reject {
            Objects.requireNonNull(reasons);
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("Reject must carry at least one reason");
            }
            reasons = List.copyOf(reasons);
        }
    }
}

public record RejectReason(String checkName, RejectCode code, String message) {
    public RejectReason {
        Objects.requireNonNull(checkName);
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
    }
}

public enum RejectCode {
    KILL_SWITCH_ENGAGED,
    DUPLICATE_PROPOSAL,
    POSITION_SIZE_EXCEEDED,
    GROSS_EXPOSURE_EXCEEDED,
    NET_EXPOSURE_EXCEEDED,
    CONCENTRATION_EXCEEDED,
    SECTOR_CAP_EXCEEDED,
    FAT_FINGER_PRICE_DEVIATION,
    FAT_FINGER_QUANTITY,
    UNKNOWN_SYMBOL,
    STALE_SNAPSHOT
}
```

**Tests:**
- `shouldRejectConstruction_whenReasonsEmpty`
- `shouldDeepCopyReasonsList` — caller mutating the input list does not affect the Reject
- Pattern-match exhaustiveness compiles (sealed interface smoke test)

**Done when:** types compile, validation tests pass.

---

### Task 3.2 — `RiskCheck` interface + `GatewayContext` + `GatewayLimits`

The contract every check implements, and the immutable bundle passed to each.

**Spec:**

```java
public interface RiskCheck {
    /** Stable identifier used in RejectReason.checkName(). */
    String name();

    /**
     * Pure function. Returns Optional.empty() if the proposal passes this check,
     * or a RejectReason describing why it failed.
     * Must not block, must not perform I/O, must not throw.
     */
    Optional<RejectReason> check(TradeProposal proposal, GatewayContext ctx);
}

public record GatewayContext(
    RiskSnapshot snapshot,     // may be null if no snapshot yet for portfolio
    Instrument instrument,     // may be null if symbol unknown to registry
    GatewayLimits limits,
    GatewayState state,
    Instant evaluatedAt
) {}

public record GatewayLimits(
    long maxPositionQty,          // per symbol, absolute value
    double maxGrossExposure,      // per portfolio
    double maxNetExposure,        // per portfolio, signed
    double maxHHI,                // [0, 1]
    double maxSectorWeight,       // [0, 1], applied to each sector
    double fatFingerPriceDevPct,  // e.g. 0.10 == reject if proposal price > 10% from market
    long fatFingerMaxQty,         // absolute hard ceiling
    Duration maxSnapshotAge       // reject if snapshot.computedAt is older than this
) {
    // Compact constructor validates all bounds.
}
```

**Tests:**
- `GatewayLimits` rejects negative ceilings, HHI/sector weight outside [0,1], non-positive durations.
- `GatewayContext` accepts null `snapshot` / `instrument` (checks handle these cases explicitly).

**Done when:** types compile, validation tests pass.

---

### Task 3.3 — Six `RiskCheck` implementations

Each is its own package-private class in `core/gateway/checks/`. Stateless. Pure.

#### 3.3.1 — `KillSwitchCheck`
- **Logic:** if `ctx.state().isKillSwitchEngaged()` → reject with `KILL_SWITCH_ENGAGED`.
- **Tests:**
  - `shouldReject_whenKillSwitchEngaged`
  - `shouldPass_whenKillSwitchDisengaged`

#### 3.3.2 — `IdempotencyCheck`
- **Logic:** atomic `ctx.state().recordProposalIfAbsent(proposalId, evaluatedAt)`.
  Returns true if newly recorded, false if already seen.
- **Tests:**
  - `shouldPass_whenProposalIdUnseen`
  - `shouldReject_whenProposalIdReplayed`
  - `shouldBeAtomic_underConcurrentSubmissionsOfSameProposalId` — 32 threads submit the
    same proposalId; exactly one passes, the rest reject with `DUPLICATE_PROPOSAL`.

#### 3.3.3 — `FatFingerCheck`
- **Logic:**
  - If `proposal.quantity() > limits.fatFingerMaxQty()` → reject with `FAT_FINGER_QUANTITY`.
  - If `instrument != null` and
    `|proposal.limitPrice() - instrument.price()| / instrument.price() > limits.fatFingerPriceDevPct()`
    → reject with `FAT_FINGER_PRICE_DEVIATION`.
  - If `instrument == null` → reject with `UNKNOWN_SYMBOL`.
- **Tests:**
  - `shouldReject_whenQtyAboveCeiling`
  - `shouldReject_whenLimitPriceDeviatesAboveThreshold`
  - `shouldPass_whenPriceWithinThreshold`
  - `shouldReject_whenInstrumentUnknown`

#### 3.3.4 — `PositionSizeCheck`
- **Logic:** compute post-trade qty = `currentQty + (BUY ? +qty : -qty)` where `currentQty`
  comes from `snapshot.positions().get(symbol)` (or 0 if absent). If
  `|postTradeQty| > limits.maxPositionQty()` → reject with `POSITION_SIZE_EXCEEDED`.
  Snapshot null → treat current qty as 0.
- **Tests:**
  - `shouldReject_whenPostTradeQtyExceedsLimit_onBuy`
  - `shouldReject_whenPostTradeQtyExceedsLimit_onShortSell`
  - `shouldPass_whenAtExactlyLimit`
  - `shouldHandleNullSnapshot_asZeroCurrentPosition`

#### 3.3.5 — `NotionalExposureCheck`
- **Logic:** compute post-trade gross/net using `currentGross + |delta|` and
  `currentNet + signedDelta` where `delta = qty * instrument.price()`. Reject if either
  exceeds its cap. Use `GROSS_EXPOSURE_EXCEEDED` / `NET_EXPOSURE_EXCEEDED` codes.
  Both can be flagged in the same `RejectReason` chain via separate emissions.
- **Tests:**
  - `shouldReject_whenPostTradeGrossExceedsLimit`
  - `shouldReject_whenPostTradeNetExceedsLimit`
  - `shouldPass_whenWithinBothLimits`
  - `shouldReduceExposure_whenSellingExistingLong` (sell of an existing long reduces gross)

#### 3.3.6 — `ConcentrationCheck`
- **Logic:** compute post-trade sector exposure map and post-trade HHI based on
  `snapshot.sectorExposure()` + delta. Reject if HHI > maxHHI or any sector weight >
  maxSectorWeight.
  *Implementation note:* if snapshot is null, simulate from a single-position book.
- **Tests:**
  - `shouldReject_whenPostTradeHHIExceedsLimit`
  - `shouldReject_whenPostTradeSectorWeightExceedsCap`
  - `shouldPass_whenWellDiversified`
  - **Property test (jqwik):** for any proposal where the resulting HHI is mathematically
    ≤ maxHHI, the check must pass.

**Done when:** every check has its own test class, all passing, no check class exceeds
~80 LOC.

---

### Task 3.4 — `GatewayState`

Holds the kill switch and the idempotency record. Thread-safe, no synchronized blocks
needed — atomics and `ConcurrentHashMap` only.

**Spec:**

```java
public final class GatewayState {

    private final AtomicBoolean killSwitch = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Instant> seenProposals = new ConcurrentHashMap<>();

    public boolean isKillSwitchEngaged() { return killSwitch.get(); }
    public void engageKillSwitch()       { killSwitch.set(true); }
    public void disengageKillSwitch()    { killSwitch.set(false); }

    /** Returns true iff this proposalId was NOT previously seen (atomic). */
    public boolean recordProposalIfAbsent(String proposalId, Instant at) {
        return seenProposals.putIfAbsent(proposalId, at) == null;
    }

    /** For tests / ops introspection. */
    public int seenProposalCount() { return seenProposals.size(); }
}
```

**Note for later:** `seenProposals` grows unbounded. Phase 5 (ops) will add bounded
eviction; for Phase 3 we accept the leak and document it.

**Tests:**
- `shouldDefaultKillSwitchToDisengaged`
- `shouldEngageAndDisengageKillSwitch`
- `shouldReturnTrueOnFirstRecord_falseOnReplay`
- **Concurrency test:** 32 threads racing to record the same proposalId — exactly one
  observes `true`, all others observe `false`. Use `CountDownLatch` + `AtomicInteger`.

**Done when:** atomic-record concurrency test passes deterministically over 100 reruns.

---

### Task 3.5 — `PreTradeGateway`

The orchestrator. Materializes context once, runs checks in order, returns decision.

**Spec:**

```java
public final class PreTradeGateway {

    private final RiskSnapshotCache snapshotCache;
    private final Map<String, Instrument> instrumentRegistry;
    private final GatewayLimits limits;
    private final GatewayState state;
    private final List<RiskCheck> checks;   // ordered, see below
    private final Clock clock;

    public PreTradeGateway(
        RiskSnapshotCache snapshotCache,
        Map<String, Instrument> instrumentRegistry,
        GatewayLimits limits,
        GatewayState state,
        Clock clock
    ) {
        // checks list constructed internally — order is part of the contract
        this.checks = List.of(
            new KillSwitchCheck(),
            new IdempotencyCheck(),    // second so duplicates don't get full evaluation cost
            new FatFingerCheck(),
            new PositionSizeCheck(),
            new NotionalExposureCheck(),
            new ConcentrationCheck()
        );
        // ...
    }

    public GatewayDecision decide(TradeProposal proposal) {
        Instant now = clock.instant();
        RiskSnapshot snapshot = snapshotCache.getSnapshot(proposal.portfolioId()).orElse(null);
        Instrument instrument = instrumentRegistry.get(proposal.symbol());
        GatewayContext ctx = new GatewayContext(snapshot, instrument, limits, state, now);

        // Stale-snapshot guard
        if (snapshot != null && Duration.between(snapshot.computedAt(), now).compareTo(limits.maxSnapshotAge()) > 0) {
            return new GatewayDecision.Reject(
                proposal.proposalId(),
                List.of(new RejectReason("StaleSnapshotCheck", RejectCode.STALE_SNAPSHOT, "Snapshot is older than maxSnapshotAge")),
                now
            );
        }

        List<RejectReason> reasons = new ArrayList<>();
        for (RiskCheck check : checks) {
            check.check(proposal, ctx).ifPresent(reasons::add);
            // Short-circuit ONLY on KILL_SWITCH or DUPLICATE — these are existential rejects.
            if (!reasons.isEmpty()) {
                RejectCode firstCode = reasons.get(0).code();
                if (firstCode == RejectCode.KILL_SWITCH_ENGAGED || firstCode == RejectCode.DUPLICATE_PROPOSAL) {
                    return new GatewayDecision.Reject(proposal.proposalId(), reasons, now);
                }
            }
        }

        if (!reasons.isEmpty()) {
            return new GatewayDecision.Reject(proposal.proposalId(), reasons, now);
        }
        return new GatewayDecision.Accept(
            proposal.proposalId(),
            snapshot != null ? snapshot.snapshotId() : "no-snapshot",
            now
        );
    }
}
```

**Tests:**
- `shouldAccept_whenAllChecksPass`
- `shouldShortCircuit_whenKillSwitchEngaged` — no other check is invoked (use mock checks)
- `shouldShortCircuit_whenDuplicateProposal`
- `shouldCollectAllReasons_whenMultipleNonFatalChecksFail` — a single bad proposal fails
  fat-finger + position-size + exposure; decision carries all three reasons.
- `shouldRejectWithStaleSnapshot_whenSnapshotTooOld`
- `shouldAccept_whenNoSnapshotYet_andOtherChecksPass` — first trade for a portfolio
- **Concurrency test:** 64 threads submitting 1000 distinct proposals each. All
  decisions must be either Accept or Reject (never null, never throw). Idempotency
  reject count must equal duplicate count in the input.
- **Property test (jqwik):** for any randomly generated proposal where every individual
  check would pass, `decide` returns `Accept`.

**Done when:** all tests pass. Decision latency p99 < 50µs measured with HdrHistogram on
a warm JVM (informal — formal latency budgeting is Phase 5).

---

### Task 3.6 — Pipeline wiring

Extend `RiskPipeline` with a proposal entry point. Accepted proposals go to a stub
`BrokerSink` interface (paper-broker arrives in Phase 4 — for now, just a queue).

**Spec:**

```java
public interface BrokerSink {
    void submit(TradeProposal acceptedProposal);
}

public class RiskPipeline {
    // ... existing fields ...
    private final PreTradeGateway gateway;
    private final BrokerSink brokerSink;

    public GatewayDecision submitProposal(TradeProposal proposal) {
        GatewayDecision decision = gateway.decide(proposal);
        if (decision instanceof GatewayDecision.Accept) {
            brokerSink.submit(proposal);
        }
        return decision;
    }
}
```

`processBatch` (the legacy `Thread.sleep` path) stays — but `submitProposal` is the
canonical proposal entry point going forward.

**Tests:**
- `shouldRouteAcceptedProposalToBroker`
- `shouldNotRouteRejectedProposalToBroker`
- **Integration test:** start pipeline → ingest some fills to build positions → submit
  proposals → verify only the ones within all limits reach the broker sink.

**Done when:** integration test passes end-to-end.

---

## Out of Scope for Phase 3

- Real broker integration (Phase 4)
- HdrHistogram latency dashboards (Phase 5)
- Bounded idempotency cache eviction (Phase 5)
- Persistence of decisions to audit log (Phase 6)
- LLM agent producing proposals (Phase 8) — for now proposals come from tests / scripts

---

## How to Use This With Claude Code

For each task:
1. **You write the tests** (or stub them precisely). Tests encode YOUR understanding of
   the invariants — especially for `IdempotencyCheck` and the gateway concurrency tests.
2. Tell Claude: `Implement [class] to pass [test file]. Follow CLAUDE.md.`
3. Review every line. Concurrency code is unreviewable in batch — go one file at a time.
4. Commit per task: `feat(gateway): Task 3.3.4 — PositionSizeCheck`.

For property tests on `ConcentrationCheck`: define the property in English first, then
write or generate the jqwik annotation. Don't let Claude write the property AND the
implementation — that's mutual self-verification, which proves nothing.
