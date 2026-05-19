# Phase 6 — Decision Audit Log

**Goal:** Every gateway decision — accept or reject — is durably persisted to an
embedded SQLite database, so forensic investigation, replay, and compliance review
work after the fact without needing to keep the JVM alive.

**Timebox:** ~1 week of evenings.

**Deliverable:** `SqliteAuditLog` persists every `GatewayDecision` produced by
`PreTradeGateway`, on a writer thread that never blocks the validation path. The
schema is migration-aware. Tests cover round-trip, async backpressure, and the
end-to-end gateway → log → query path.

---

## Invariants (re-affirming CLAUDE.md)

1. **The gateway is synchronous and deterministic.** Audit writes are dispatched
   *after* the decision is returned. The writer runs on a dedicated single-thread
   executor — never on a request thread, never on the broker executor.
2. **Backpressure is loud.** A saturated write queue surfaces as a counter
   (`audit_dropped_total`) and a WARN log; we do not silently swallow drops.
3. **Schema migrations are explicit.** A `schema_version` row in the DB records
   the applied version; the writer checks on open and refuses to run against an
   unknown version.
4. **Persistence is best-effort, not transactional.** Phase 6 does not add a
   two-phase commit between gateway decision and audit write. The audit log is
   downstream observability, not part of the trust boundary.

---

## Task Breakdown

### Task 6.0 — `DecisionRecord` + schema DDL

Immutable value type that captures everything needed to reconstruct a decision
after the fact. New package `core/audit/`.

```java
public record DecisionRecord(
    String proposalId,
    String portfolioId,
    String symbol,
    Side side,
    long quantity,
    double limitPrice,
    String snapshotId,
    DecisionType type,          // ACCEPT or REJECT
    String firstRejectCode,     // null for accepts
    String reasonsJson,         // empty array "[]" for accepts; serialized reasons for rejects
    Instant decidedAt
) { ... }

public enum DecisionType { ACCEPT, REJECT }
```

**Schema** (`decisions` table, SQLite):

```sql
CREATE TABLE IF NOT EXISTS decisions (
    proposal_id        TEXT    PRIMARY KEY,
    portfolio_id       TEXT    NOT NULL,
    symbol             TEXT    NOT NULL,
    side               TEXT    NOT NULL,
    quantity           INTEGER NOT NULL,
    limit_price        REAL    NOT NULL,
    snapshot_id        TEXT    NOT NULL,
    decision_type      TEXT    NOT NULL,
    first_reject_code  TEXT,
    reasons_json       TEXT    NOT NULL,
    decided_at_micros  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS decisions_decided_at_idx ON decisions(decided_at_micros);
CREATE INDEX IF NOT EXISTS decisions_portfolio_idx ON decisions(portfolio_id);

CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY
);
```

Tests: validation, `fromDecision(...)` factory round-trip via reasons JSON.

---

### Task 6.1 — `AuditLog` interface + `SqliteAuditLog`

```java
public interface AuditLog extends AutoCloseable {
    void record(DecisionRecord record);
    Optional<DecisionRecord> findByProposalId(String proposalId);
    List<DecisionRecord> findByPortfolio(String portfolioId, int limit);
    long count();
}
```

`SqliteAuditLog`:
- Uses `org.xerial:sqlite-jdbc`.
- Single `java.sql.Connection` guarded by `synchronized` (SQLite serializes writes
  anyway). One prepared statement per query, cached as fields.
- `record(...)` is synchronous. Async dispatch is a separate concern (Task 6.2).
- On open, runs schema DDL inside a transaction, inserts current schema version if
  empty.

Tests:
- DDL applied on open (verify by querying sqlite_master).
- Round-trip: record → findByProposalId → equal.
- `findByPortfolio` returns chronological-DESC (newest first), bounded by limit.
- Insert duplicate proposalId → PrimaryKey violation surfaces as
  `IllegalStateException` (not silently swallowed).
- Schema-version mismatch on open → fails loud.
- Use a temp file via `Files.createTempFile(...)` so tests don't pollute the
  working directory.

---

### Task 6.2 — `AsyncAuditLog`

Wraps any `AuditLog` with a writer thread + bounded queue. The gateway calls
`asyncLog.record(...)`, which enqueues and returns immediately. A single named
daemon thread polls and writes.

```java
public final class AsyncAuditLog implements AuditLog, AutoCloseable {
    public AsyncAuditLog(AuditLog delegate, int queueCapacity, Counter droppedCounter);
    // record(...) offers to the queue. On queue-full, increments droppedCounter and logs WARN.
    // findByProposalId/findByPortfolio/count delegate synchronously.
    // close() drains the queue and shuts down the writer.
}
```

Tests:
- Async write reaches delegate.
- Backpressure: fill the queue, assert that `droppedCounter` increments, no
  blocking.
- `close()` drains pending writes (use a small fixed sample, wait for queue empty).
- Round-trip: 1000 records under multi-threaded `record()` calls — all delivered.

---

### Task 6.3 — Wire into `PreTradeGateway` + integration test

`PreTradeGateway` gains an optional `AuditLog` (default `NoopAuditLog`). After
`decide()` produces a decision, if the log is non-noop, build a
`DecisionRecord.fromDecision(...)` and call `log.record(...)`.

Integration test (`AuditPipelineTest`):
- Construct `SqliteAuditLog` on a temp file, wrap in `AsyncAuditLog`.
- Submit 5 proposals (mix of accept and reject paths).
- Close the audit log (drain queue).
- Open a fresh `SqliteAuditLog` on the same file → assert all 5 records present
  with correct fields.

This is the proof that decisions persist across JVM restart — the whole point
of the phase.

---

## Out of Scope

- Replay tooling (reading the DB and re-feeding via the agent). Phase 8+.
- DuckDB. SQLite is sufficient and simpler. We can swap later behind the same
  `AuditLog` interface.
- Time-partitioned tables / log rotation. The DB file grows unbounded; ops
  truncation is a manual task for now.
- Audit log for fills. Phase 6 is decisions only.

---

## Build deps

```
implementation("org.xerial:sqlite-jdbc:3.46.1.3")
```
