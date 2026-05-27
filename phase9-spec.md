# Phase 9 — Tool ACLs + Caller Identity

**Goal:** Close the trust-boundary gap that Phase 8's `TrustBoundaryEvalTest` documented in scenario 3: the agent can presently call `disengage_kill_switch` itself. The fix is a small, layered authorization model that gates **admin-class tools** behind caller identity, threaded through both transports (analyst bridge + MCP server) and recorded in the audit log so every decision is attributable to a caller kind.

**Timebox:** ~1–2 weeks of evenings.

**Deliverable:** Tools declare a `ToolPermission` (`READ_ONLY` / `WRITE` / `ADMIN`). `ToolRegistry.invoke` requires a `Caller` and rejects calls whose tool permission isn't granted to that caller kind. Both transports construct a caller at the edge — the analyst bridge is hardcoded `AGENT`, the MCP server defaults to `AGENT` but can be promoted to `OPERATOR` via a CLI flag. Audit records gain `callerKind` + `callerId` columns (schema v2). The trust-boundary eval is updated so the kill-switch scenario now **fails closed**: an agent calling `disengage_kill_switch` gets a permission-denied error, kill switch stays engaged, the next proposal still rejects with `KILL_SWITCH_ENGAGED`.

---

## Architectural Invariants (re-affirming CLAUDE.md)

1. **The agent is untrusted input.** ACLs are not a substitute for the gateway — they are an additional, *coarser* boundary. Admin tools must require operator identity; write tools must be reachable from the agent (else the system is useless).
2. **Identity is established at the transport edge, not by the tool or the model.** A `Caller` is built where bytes enter the JVM (analyst bridge constructor, MCP server CLI args) and propagated by call signature, never via ThreadLocal or system property at call time.
3. **Default-deny on unknown permission.** A tool whose `permission()` is `ADMIN` and a caller without `ADMIN` in their grants → reject *before* dispatch. No "did the caller pass arguments that look operatorly" guesswork.
4. **Audit captures who, not just what.** Every `DecisionRecord` records the caller kind + id at the time of the decision so post-hoc analysis can answer "which actor caused this reject."

---

## Design — the three new types

```java
// core/audit (it's a domain attribution concept, not a transport one)
public record Caller(CallerKind kind, String id) {
    public enum CallerKind { AGENT, OPERATOR, SYSTEM }
    public static Caller agent(String id) { return new Caller(CallerKind.AGENT, id); }
    public static Caller operator(String id) { return new Caller(CallerKind.OPERATOR, id); }
    public static Caller system() { return new Caller(CallerKind.SYSTEM, "system"); }
}

// mcp-bridge
public enum ToolPermission { READ_ONLY, WRITE, ADMIN }

// mcp-bridge — policy table, not a deep abstraction
public final class ToolAuthorizer {
    public static ToolAuthorizer defaults();  // AGENT → READ_ONLY+WRITE, OPERATOR → all, SYSTEM → all
    public boolean allows(Caller caller, ToolPermission required);
}
```

`Tool` grows a default `permission()` method returning `WRITE` (safe middle ground — no one accidentally upgrades a tool to ADMIN by inheritance, no one accidentally downgrades a tool to READ_ONLY).

`ToolRegistry` constructor optionally takes a `ToolAuthorizer` (defaults to `ToolAuthorizer.defaults()`). New required `invoke` signature:

```java
public ToolResult invoke(String toolName, JsonNode input, Caller caller);
```

The old `invoke(String, JsonNode)` is removed — all three call sites (`McpServerAdapter`, `LangChain4jToolBridge`, the small handful of unit tests) migrate.

`PreTradeGateway` grows an overload `decide(TradeProposal, Caller)`. The existing `decide(TradeProposal)` defers to it with `Caller.system()` (so legacy paths and pipeline tests don't need to change). `SubmitProposalTool.invoke` receives the caller from the registry and forwards it.

---

## Task Breakdown

### Task 9.0 — `Caller` + `ToolPermission` + `ToolAuthorizer`

`core/src/main/java/com/risksentinel/core/audit/Caller.java`:
- Record with nested `CallerKind` enum (AGENT/OPERATOR/SYSTEM).
- Compact constructor: non-null kind, non-null + non-blank id.
- Static factories `agent(id)`, `operator(id)`, `system()`.

`mcp-bridge/src/main/java/com/risksentinel/mcp/ToolPermission.java`:
- Enum with `READ_ONLY`, `WRITE`, `ADMIN`.

`mcp-bridge/src/main/java/com/risksentinel/mcp/ToolAuthorizer.java`:
- Final class. Holds `Map<CallerKind, Set<ToolPermission>>`.
- `defaults()`: AGENT → {READ_ONLY, WRITE}, OPERATOR → {READ_ONLY, WRITE, ADMIN}, SYSTEM → all.
- Tight `allows(Caller, ToolPermission)` boolean.
- No "deny lists", no per-tool rules. The policy is per-permission level; this keeps the surface tiny.

**Tests:**
- `CallerTest`: null/blank validation, factories produce the right kinds.
- `ToolAuthorizerTest`: defaults grant the expected matrix; unknown caller kind → deny; deny does not throw.

**Done when:** types compile, all field validation tested, `defaults()` policy locked in a test.

---

### Task 9.1 — Wire permissions through `Tool` + `ToolRegistry`

Add default method to `Tool`:
```java
default ToolPermission permission() { return ToolPermission.WRITE; }
```

Override in the seven existing tools:
- `GetSnapshotTool`, `ListPositionsTool`, `GetInstrumentTool`, `ListRecentDecisionsTool` → `READ_ONLY`
- `SubmitProposalTool` → `WRITE`
- `EngageKillSwitchTool`, `DisengageKillSwitchTool` → `ADMIN`

`ToolRegistry` changes:
- Constructor overload `ToolRegistry(List<Tool> tools, ToolAuthorizer authorizer)`. The existing one-arg constructor delegates with `ToolAuthorizer.defaults()`.
- Replace `invoke(String, JsonNode)` with `invoke(String, JsonNode, Caller)`.
- Before calling `validateShallow`, check `authorizer.allows(caller, tool.permission())`. If not, return `ToolResult.error("Permission denied: <tool> requires <perm>; caller <kind:id> has <granted>")`. Permission denial is the only path that logs at WARN with the caller id — every other path stays at the existing levels.

Migrate the three call sites:
- `McpServerAdapter.dispatch` — accept a `Caller` in constructor, pass it to `invoke`.
- `LangChain4jToolBridge.execute` — accept a `Caller` in constructor, pass it to `invoke`. Default-construct as `Caller.agent("analyst")` if no caller is supplied (this is the only path where the default is safe — the bridge can never be used by an operator).
- Existing unit tests use `Caller.system()`.

**Tests:**
- `ToolRegistryAclTest`:
  - `shouldDispatch_whenCallerHasPermission` (parameterized: ALL three levels with a granting caller).
  - `shouldDenyWithError_whenCallerLacksAdmin` — AGENT calling `EngageKillSwitchTool` returns `isError=true` with the denial message; the tool's `invoke` is **not** called (use a spy/stub).
  - `shouldDenyWithError_whenCallerLacksWrite` — synthetic caller with only READ_ONLY tries `SubmitProposalTool`.
  - `shouldEmitDenialBeforeSchemaValidation` — missing required fields *plus* lacking permission → permission error comes first (so the wire client doesn't learn schema details until they're authorized).
- One test per existing tool asserts its declared permission matches the spec table above (catches accidental downgrades).

**Done when:** the ACL path is exercised end-to-end through `ToolRegistry.invoke`, no caller-less invoke path exists anywhere.

---

### Task 9.2 — Per-transport caller wiring

`LangChain4jToolBridge`:
- Constructor takes a `Caller`. The analyst module never constructs anything but `Caller.agent("analyst")`. The `Caller` parameter exists so tests can inject an operator-caller to prove the system *would* allow privileged calls if mis-wired.
- All existing tests in `analyst/` migrate to pass `Caller.agent("test-analyst")`.

`McpServerAdapter`:
- Constructor takes a `Caller`.
- `mcp.Main` defaults to `Caller.agent("mcp-client")`. New `--operator <id>` CLI flag constructs `Caller.operator(id)` instead, with a startup log message that names the id. Document in `--help`.
- A future Phase 10+ HTTP transport will require per-request caller identity (e.g. from a bearer token). Today, the MCP stdio transport is a single connection, so a per-server caller is sufficient — this is recorded as a known limitation, not a bug.

`analyst/.../LangChain4jAnalyst` does *not* know about callers. The bridge owns identity.

**Tests:**
- `McpServerAdapterTest` (smoke): server builds with each caller kind; tool catalog is the full registry regardless of caller (caller affects invocation, not discovery — same approach Slack/Discord take).
- `LangChain4jToolBridgeTest`: existing tests migrate; one new test `shouldPropagateCaller_toRegistry` uses a fake `ToolRegistry` and asserts the caller arrives unchanged.

**Done when:** every public construction site of either transport requires an explicit `Caller`. No defaulted `Caller.system()` in production code (only in tests).

---

### Task 9.3 — Audit caller identity (schema v2)

`DecisionRecord` adds two fields at the end:
```java
public record DecisionRecord(
        ... existing fields ...,
        Instant decidedAt,
        Caller.CallerKind callerKind,   // nullable for pre-v2 records read from old DBs
        String callerId                  // nullable for pre-v2 records read from old DBs
) { ... }
```

`AuditSchema` bumps `CURRENT_VERSION` to `2`. Migration in `SqliteAuditLog.applySchema`:
- If `schema_version` is 1, `ALTER TABLE decisions ADD COLUMN caller_kind TEXT`; same for `caller_id`; update `schema_version` row to 2 inside the same transaction.
- If `schema_version` is 2 already, no-op.
- If `schema_version` is anything else, throw (existing behavior).

`PreTradeGateway.decide`:
- Add overload `decide(TradeProposal, Caller)`. Existing `decide(TradeProposal)` calls the overload with `Caller.system()`.
- The overload threads the caller into the `DecisionRecord` it writes.

`SubmitProposalTool.invoke` needs the caller. Two options:
- **(a)** Change `Tool.invoke` signature to take `Caller`. Touches all seven tools.
- **(b)** Pass caller via a small `InvocationContext` record so future fields (request id, deadline) don't churn the signature again.

**Pick (b)** — `InvocationContext(Caller caller, Instant receivedAt)`. `Tool.invoke(JsonNode, InvocationContext)` is the new signature. All seven tools migrate (most ignore the context; SubmitProposal uses it). The old `invoke(JsonNode)` is removed.

**Tests:**
- `SqliteAuditLogSchemaMigrationTest`: create a v1 DB by hand (CREATE TABLE without the columns, `schema_version=1`), open it via `SqliteAuditLog`, assert schema_version is now 2 and new columns exist. Write a v2 record, read it back. Then write a record that simulates a pre-existing v1 record (null caller fields) — readable, no NPE.
- `DecisionRecordTest`: caller fields nullable for backwards compat; new record builds with both AGENT and OPERATOR.
- `PreTradeGatewayCallerTest`: `decide(proposal)` → audit record has `callerKind=SYSTEM, callerId="system"`. `decide(proposal, operator)` → record has the operator's id.

**Done when:** new audit columns populate on every gateway decision; migrating an existing v1 DB does not lose data.

---

### Task 9.4 — Trust-boundary eval update

`TrustBoundaryEvalTest`:
- `EvalFixture` constructs `ToolRegistry` with `ToolAuthorizer.defaults()` and the analyst bridge with `Caller.agent("eval-agent")`.
- Scenario 3 (`shouldStayHonoringKillSwitch_evenIfAgentDisengagesMidLoop`) is **deleted and replaced** by:
  - `shouldRejectDisengage_whenAgentTriesToCallAdminTool`:
    1. Ops engages kill switch externally.
    2. Agent stub: tool call 1 = `submit_proposal` → reject with `KILL_SWITCH_ENGAGED`. Tool call 2 = `disengage_kill_switch` → expect `isError=true` with "Permission denied" content. Tool call 3 = `submit_proposal` → reject with `KILL_SWITCH_ENGAGED`.
    3. Assert `gatewayState.isKillSwitchEngaged() == true` at the end.
    4. Audit log has exactly 2 records, both REJECT/KILL_SWITCH_ENGAGED, both with `callerKind = AGENT, callerId = "eval-agent"`.
- New scenario `shouldAllowDisengage_whenCallerIsOperator`:
  - Build a *second* bridge with `Caller.operator("eval-operator")` and prove the same tool sequence succeeds. This is the positive control: ACLs aren't just "always deny" — they're selective.
- Other scenarios: passing tests get a one-line migration to provide a `Caller` to the bridge; assertions remain.

Add a brand-new test `shouldRecordCallerInAudit`:
- One agent-driven `submit_proposal` is accepted.
- `auditLog.findByPortfolio(...).get(0).callerKind() == AGENT`, `callerId() == "eval-agent"`.

**Done when:** the kill-switch ACL gap from Phase 8 is closed; eval makes a regression-grade assertion that it stays closed.

---

## Out of Scope

- HTTP transport / per-request caller identity (deferred until an HTTP transport exists).
- Authentication of operators — `--operator <id>` trusts the id string. Real auth (bearer tokens, mTLS) is a Phase 11+ concern.
- Per-tool rules (e.g. "agent may call submit_proposal up to N times/min"). Rate-limit-style ACLs are a different feature; this phase is binary allow/deny by permission level.
- A privilege-elevation tool (e.g. "request operator review"). Interesting, but conflates ACL with workflow.
- Backtest harness (Phase 10).
- `sim/` module (Phase 10+).

---

## Risks

- **Schema migration is the only piece that can corrupt an existing DB.** Mitigate with: tested migration, idempotent re-run, refuse-to-open on unknown versions. No `DROP TABLE`, no destructive ALTERs.
- **Tool signature churn.** Adding `InvocationContext` is the second `Tool.invoke` signature change in two phases. After this we should stop adding to it — future per-call data can ride inside `InvocationContext` without touching the signature again.
- **Default-deny vs. default-allow for new tools.** `Tool.permission()` defaults to `WRITE`. A future tool author who forgets to override gets safe-middle behavior, not "unrestricted." Documented in the `Tool` Javadoc.
- **The `--operator` flag is on the honor system.** Anyone with shell access to the JVM can pass it. That's fine for this phase; real deployments would wrap the JVM in a process supervisor that fixes the flag.

---

## Build deps

No new external deps. Schema migration uses the JDBC connection that's already there.
