# Phase 5 — Observability + Ops

**Goal:** Stop the unbounded growth, start measuring real latency, and make the system
observable from the outside without paging a developer to read heap dumps. After this
phase, "what is the p99 gateway latency right now?" and "how many proposals did we
reject for fat-finger today?" are both answerable from a dashboard, not by re-running
the JVM with println.

**Timebox:** ~1.5–2 weeks of evenings.

**Deliverable:**
- `GatewayState.seenProposals` and `PaperBroker.ordersByProposalId` no longer leak.
- `gateway.decide(...)` and broker submit→fill latencies are tracked with HdrHistogram
  and exposed as percentile snapshots.
- A `MetricsRegistry` façade routes counters, timers, and gauges to Micrometer; a
  Prometheus endpoint exposes them.
- Logback emits structured JSON with `portfolioId`/`proposalId` MDC fields populated
  on the gateway and broker call paths.
- No test in the existing suite regresses. New tests verify eviction, latency capture,
  and MDC propagation.

---

## Architectural Invariants (re-affirming CLAUDE.md)

1. **Metrics are zero-overhead on the hot path.** HdrHistogram is a CAS-based
   `Recorder`, not synchronized. Counters are `LongAdder`. No `log.info` with string
   concatenation on a per-decision path.
2. **"Don't claim latencies you didn't measure."** Every histogram percentile we report
   comes from a `Recorder.getIntervalHistogram(...)` snapshot — never a back-of-envelope.
3. **Eviction is bounded and deterministic.** Caches use TTL + size cap. No
   `WeakReference` games. The eviction policy is testable: a unit test can advance a
   `Clock` and assert what got dropped.
4. **MDC is set at the entry point and cleared in a `finally`.** No bare ThreadLocal
   pollution between unrelated proposals. Structured logging fields are *always*
   the same set (portfolioId, proposalId, snapshotId, decisionCode) so log
   aggregation queries don't break.
5. **Metrics façade hides Micrometer.** No `core/broker/` or `core/gateway/` code
   imports `io.micrometer.*` directly. The façade in `core/ops/` is the only place
   that knows about the registry implementation.

---

## Task Breakdown

### Task 5.0 — `BoundedIdempotencyCache`

Replace the raw `ConcurrentHashMap<String, Instant>` inside `GatewayState` with a
purpose-built cache that evicts entries older than a configured TTL and never holds
more than a configured maximum count.

**Spec:**

```java
package com.risksentinel.core.ops;

public final class BoundedIdempotencyCache {

    /**
     * @param ttl               retention window per entry; entries older than this
     *                          on the wall clock are evicted
     * @param maxSize           hard ceiling; oldest entries are evicted FIFO on overflow
     * @param sweepInterval     how often the background sweep runs
     * @param clock             injectable for tests
     */
    public BoundedIdempotencyCache(Duration ttl, int maxSize, Duration sweepInterval, Clock clock);

    /** True iff this proposalId was NOT previously seen and is now recorded. */
    public boolean recordIfAbsent(String proposalId);

    /** Snapshot size for diagnostics — O(1). */
    public int size();

    /** Force a sweep for tests; production code relies on the scheduled sweep. */
    public int sweepNow();

    public void shutdown();
}
```

Implementation notes:
- Backing storage: `ConcurrentHashMap<String, Instant>`.
- A `ScheduledExecutorService` (single named daemon thread) runs `sweepNow()` every
  `sweepInterval`. Sweep iterates the map and removes entries with
  `now - timestamp > ttl`. Concurrent inserts are fine — `ConcurrentHashMap.remove`
  is atomic per key.
- Size cap is enforced *after* TTL eviction. If still above `maxSize`, drop the
  oldest entries by sort on timestamp. Yes, that is O(n log n) — acceptable because
  `maxSize` is a guardrail, not a steady-state condition.
- Clock injection so tests don't need `Thread.sleep`.

Wire into `GatewayState` as a swap of the existing `ConcurrentHashMap`.
`recordProposalIfAbsent(...)` delegates to `cache.recordIfAbsent(...)`. Existing
`engageKillSwitch`/`isKillSwitchEngaged` unchanged.

**Tests:**
- `shouldReturnTrueOnFirstRecord_falseOnReplay` (regression of existing GatewayState)
- `shouldEvictEntry_whenOlderThanTtl` — advance clock past TTL, sweep, gone
- `shouldEvictOldestEntries_whenSizeExceedsMax`
- `shouldCleanShutdown_withoutLeakingScheduler`
- `shouldRecordConcurrently_underBurst` — 32 threads, distinct IDs, all recorded; size matches
- Property test: for any sequence of recordIfAbsent calls, no ID appears in size()
  before its first record returned `true`.

**Done when:** unbounded leak gone. All 32-thread concurrency tests from Phase 3
still pass against the new cache. Scheduler thread is named `gateway-idempotency-sweep-*`.

---

### Task 5.1 — `BoundedOrderHistory`

Same idea applied to `PaperBroker.ordersByProposalId`. Different policy: orders may be
useful for diagnostics longer than idempotency entries (e.g. "what happened to that
fill from an hour ago?"), so default TTL is longer (~24h) and `maxSize` larger (~100k).
The structure is otherwise identical.

**Spec:**

```java
package com.risksentinel.core.ops;

public final class BoundedOrderHistory {

    public BoundedOrderHistory(Duration ttl, int maxSize, Duration sweepInterval, Clock clock);

    public Order putIfAbsent(String proposalId, Order order);
    public Order compute(String proposalId, BiFunction<String, Order, Order> remapping);
    public Optional<Order> get(String proposalId);

    public int size();
    public int sweepNow();
    public void shutdown();
}
```

Wire into `PaperBroker` by replacing the raw `ConcurrentHashMap<String, Order>`.
Eviction is keyed off `Order.lastUpdatedAt()` so an order that just transitioned
`NEW → FILLED` resets its retention clock — useful, since we care most about recent
activity.

**Tests:**
- `shouldRetainRecentOrders_andEvictOld`
- `shouldRefreshRetention_onCompute` — calling compute updates the lastUpdatedAt;
  the order is *not* eligible for eviction immediately after.
- Concurrency: 1,000 inserts across 8 threads; expected count, no exceptions.

**Done when:** PaperBroker leak gone. Broker concurrency tests from Phase 4 still pass.

---

### Task 5.2 — `LatencyRecorder` + HdrHistogram instrumentation

Instrument `gateway.decide(...)` and the broker submit-to-fill latency. Use
HdrHistogram's `Recorder` (multi-writer-safe) so we can read a snapshot without
freezing the writers.

**Spec:**

```java
package com.risksentinel.core.ops;

/**
 * Multi-writer latency capture with periodic snapshotting. A {@code LatencyRecorder}
 * tracks one logical probe (e.g. "gateway-decide-nanos"). Writers call
 * {@link #recordNanos(long)} on the hot path. Readers call {@link #snapshot()} to
 * obtain an immutable percentile view; readers and writers do not block each other.
 */
public final class LatencyRecorder {

    public LatencyRecorder(String name, long highestTrackableValue, int significantDigits);

    public void recordNanos(long nanos);
    public LatencySnapshot snapshot();   // p50, p95, p99, p99.9, max, count
    public String name();
}

public record LatencySnapshot(
    String probe,
    long count,
    long p50Nanos, long p95Nanos, long p99Nanos, long p999Nanos, long maxNanos
) {}
```

Wire-up:
- `PreTradeGateway` accepts an optional `LatencyRecorder` (no-op by default — kept as a
  `NoopLatencyRecorder` so the hot path has no null check). At decide entry record
  `System.nanoTime()`, in a `finally` record the delta.
- `PaperBroker` accepts two: one for `submit(...)` wall time, one for submit→fill
  end-to-end. The submit-to-fill timer is started when we create the Order and
  stopped right before `fillSink.onFill(...)`.

**Tests:**
- `shouldRecordNanosAndExposeSnapshot`
- `shouldReportAccuratePercentiles_underUniformLoad` — 100k samples of a known
  distribution, p50/p99 within tolerance.
- `shouldBeSafeUnderConcurrentRecording` — 16 writers + 1 reader for 2 seconds,
  no exceptions, snapshot count equals total recorded.
- Integration: after running the 64-thread × 1000-proposal gateway concurrency test
  from Phase 3 against an instrumented gateway, snapshot count is 64,000.

**Done when:** gateway and broker expose latency snapshots. No regression. Hot-path
overhead (verified locally on a warm JVM) is < 100ns per probe.

---

### Task 5.3 — `MetricsRegistry` façade + Micrometer wiring

Hide Micrometer behind a small façade. The façade lives in `core/ops/`; everything
else in `core/` calls the façade only.

**Spec:**

```java
package com.risksentinel.core.ops;

public interface MetricsRegistry {

    Counter counter(String name, Tags tags);
    Timer timer(String name, Tags tags);
    Gauge gauge(String name, Tags tags, Supplier<Number> supplier);

    /** Render a snapshot for diagnostics (not the Prometheus scrape path). */
    String scrapeText();
}
```

Two implementations:
- `NoopMetricsRegistry` — default for tests that don't care.
- `MicrometerMetricsRegistry` — wraps `PrometheusMeterRegistry`. `scrapeText()`
  returns `registry.scrape()`. Tags map to Micrometer `Tags`.

Wire calls:
- `PaperBroker.submit/filled/rejected` → counters with tag `portfolioId`.
- `PreTradeGateway` → counter `gateway.decide` with tags `decision` (accept/reject)
  and `code` (rejection code or `none`).
- `LatencyRecorder` snapshots → registered as gauges so Prometheus scrapes them.

**Tests:**
- `NoopMetricsRegistryTest` — every call is a no-op, no NPEs.
- `MicrometerMetricsRegistryTest` — counter increments visible in `scrapeText()`;
  tag values rendered correctly.
- `BrokerMetricsIntegrationTest` — submit 100 proposals, scrape, expect
  `paper_broker_submitted_total{} 100` and `paper_broker_filled_total{} 100`.

**Done when:** scraping the registry produces lines that match Prometheus exposition
format. No `io.micrometer` import appears outside `core/ops/`.

---

### Task 5.4 — Structured JSON logging + MDC

Replace whatever Logback config exists (default pattern layout) with JSON output that
always includes the MDC keys we care about: `portfolioId`, `proposalId`, `snapshotId`,
`decisionCode`. If a key isn't set, the field is omitted (not "null").

**Spec:**
- Use `logstash-logback-encoder` for JSON output.
- One small `MdcScope` helper in `core/ops/` to set + clear MDC reliably:

  ```java
  try (MdcScope s = MdcScope.of("portfolioId", proposal.portfolioId(),
                                "proposalId",  proposal.proposalId())) {
      // ... gateway work ...
  }
  ```
  `MdcScope` is `AutoCloseable` so we can't forget to clear.
- Apply the scope at two entry points: `PreTradeGateway.decide(...)` and
  `PaperBroker.submit(...)`. The broker's executor task captures the MDC at submit
  time and restores it inside the task using `MDC.setContextMap(...)`.

**Tests:**
- `MdcScopeTest` — values are present inside the scope, gone after, even on exception.
- `LoggingPropagationTest` — submit a proposal, capture log output (via a Logback
  list-appender), assert at least one event has both `portfolioId` and `proposalId`
  set.
- `BrokerMdcPropagationTest` — fill event delivery sees the original MDC even though
  it runs on a broker executor thread.

**Done when:** running the pipeline emits JSON lines that a log aggregator (or `jq`)
can parse, and the broker thread's logs carry the right portfolio/proposal IDs.

---

## Out of Scope for Phase 5

- Decision audit log persistence to SQLite/DuckDB → Phase 6.
- Kill switch admin HTTP endpoint → still in-process in Phase 5; programmatic API
  is fine for now.
- LangChain4j agent and MCP tools → Phase 7+.
- Prometheus scrape endpoint over HTTP — the registry exposes `scrapeText()`, but
  wiring a `com.sun.net.httpserver.HttpServer` or similar is Phase 5.5/6 work.
- jcstress tests for the new caches — desirable but defer to Phase 6 once we have
  more pieces to stress together.

---

## Risks to flag while coding

- **Background sweep starvation under heavy GC.** If the sweep thread is starved,
  the cache can briefly exceed its soft TTL. That's acceptable. The size cap is the
  hard ceiling that prevents unbounded growth even if the sweep never runs.
- **MDC leakage between proposals on the same broker thread.** Without `MdcScope`,
  the next task on the same thread picks up the previous task's MDC. Tests for
  `BrokerMdcPropagationTest` need to assert *isolation*, not just propagation.
- **HdrHistogram `Recorder` reset semantics.** `getIntervalHistogram(...)` returns
  a snapshot and *resets* the recorder. If we want cumulative reads (Prometheus
  prefers cumulative), we either accumulate manually or hold a separate cumulative
  Histogram and merge on each snapshot. Pick one approach and document it.
- **Eviction race vs. concurrent compute.** `BoundedOrderHistory.sweepNow()` must
  not remove an order that a concurrent `compute(...)` is rewriting. Easiest fix:
  the sweep also uses `computeIfPresent(...)` with a TTL check inside the lambda —
  per-key serialization is free that way.

---

## How to Use This With Claude Code

1. **You write the concurrency tests** for `BoundedIdempotencyCache`,
   `BoundedOrderHistory`, and `LatencyRecorder`. These are the new concurrency-bearing
   components in this phase.
2. Tell Claude: `Implement [class] to pass [test file]. Follow CLAUDE.md.`
3. Review every line of the eviction sweep — that's where subtle off-by-one or
   missed-key bugs hide.
4. Commit per task: `feat(ops): Task 5.2 — LatencyRecorder + HdrHistogram wiring`.
