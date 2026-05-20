# Phase 7 — MCP Bridge

**Goal:** Expose RiskSentinel's inspection and proposal surface as a set of explicitly-named tools so an external agent (Phase 8) or Claude Desktop can drive the system through a single, audited channel. The trust boundary holds: tool calls that submit proposals still pass through `PreTradeGateway`.

**Timebox:** ~1.5–2 weeks of evenings.

**Deliverable:** A new `mcp-bridge/` Gradle module. A transport-agnostic `Tool` abstraction with a registry, JSON schemas for inputs/outputs, and six core tools wired against the existing `core/` services. The MCP wire protocol itself is wired in the final task; the abstraction lets the tools be unit-tested without a transport.

---

## Architectural Invariants (re-affirming CLAUDE.md)

1. **MCP is just a transport.** A tool that submits a proposal calls `gateway.decide(...)` — it does not bypass it, ever. There is no "admin tool that skips checks."
2. **Tools are explicit, not reflective.** Each tool is a named class with a declared JSON schema. No `@Tool`-annotation magic, no auto-discovery from method signatures. The registry is built by passing a `List<Tool>` to the server.
3. **Tool I/O is JSON.** Inputs are parsed against the schema before the handler runs; outputs are JSON. Helps the agent understand error shapes.
4. **`mcp-bridge` depends on `core`, not the other way around.** No core code imports anything from `mcp-bridge`.
5. **MCP SDK details are hidden behind a transport adapter.** Tools and the registry are SDK-free so we can swap stdio for HTTP/SSE later or stub the transport for tests.

---

## Task Breakdown

### Task 7.0 — `Tool` abstraction + `ToolRegistry` + JSON I/O

`mcp-bridge/src/main/java/com/risksentinel/mcp/`:

```java
public interface Tool {
    String name();                // e.g. "get_snapshot"
    String description();         // human-readable, surfaced to the agent
    Map<String, Object> inputSchema();  // JSON Schema as a Map
    ToolResult invoke(JsonNode input);
}

public record ToolResult(boolean isError, String content) {}
```

- Use Jackson for JSON (transitively available, or pull `com.fasterxml.jackson.core:jackson-databind`).
- `ToolRegistry` stores tools by name, exposes `list()` and `invoke(name, json)`.
- Input validation is best-effort at this layer (presence + type for required fields). Deeper validation lives in the tool's handler — e.g., `SubmitProposalTool` lets the gateway reject malformed proposals via the existing checks.

**Tests:**
- Register two tools, list them, invoke each.
- Invoke unknown tool name → `ToolResult` with `isError=true` and message naming the missing tool.
- Invoke tool with missing required input field → `isError=true` and message naming the field.

**Done when:** registry + abstraction compile in a standalone module, no MCP SDK dep yet, unit tests pass.

---

### Task 7.1 — Read-only tools

Four tools, all delegating to existing `core/` services:

| Name | Inputs | Output | Backed by |
|---|---|---|---|
| `get_snapshot` | `portfolioId` | latest `RiskSnapshot` as JSON | `RiskSnapshotCache.getSnapshot(...)` |
| `list_positions` | `portfolioId` | array of `Position` JSON | `PositionBook.getPositions(...)` |
| `get_instrument` | `symbol` | `Instrument` JSON or `not_found` | injected `Map<String, Instrument>` |
| `list_recent_decisions` | `portfolioId`, `limit` | array of `DecisionRecord` JSON | `AuditLog.findByPortfolio(...)` |

**Tests** (in `mcp-bridge/src/test/...`):
- One test class per tool, exercising the happy path and the not-found / empty-input shapes.
- A `BridgeFixtures` helper builds an in-memory `RiskPipeline` + `SqliteAuditLog` (on a temp file) so the tools have a real backing system to read from.

**Done when:** all four tools return well-shaped JSON, no NPEs on empty state, count-by-snapshot matches what the cache returns.

---

### Task 7.2 — Action tools

| Name | Inputs | Output | Backed by |
|---|---|---|---|
| `submit_proposal` | all `TradeProposal` fields | `GatewayDecision` as JSON | `PreTradeGateway.decide(...)` |
| `engage_kill_switch` | (none) | `{ "engaged": true }` | `GatewayState.engageKillSwitch()` |
| `disengage_kill_switch` | (none) | `{ "engaged": false }` | `GatewayState.disengageKillSwitch()` |

**Critical assertion in tests:** `submit_proposal` results route through the gateway. If the kill switch is engaged, a `submit_proposal` call must return a `Reject` with `KILL_SWITCH_ENGAGED`. There is no path that skips the gateway.

**Tests:**
- `shouldAcceptValidProposal` (happy path)
- `shouldRejectFatFinger` (gateway rejects)
- `shouldRejectAllProposals_whenKillSwitchEngaged` (engage via tool, submit via tool, verify reject code)
- `shouldNotBypassGateway_evenWithAdminFlag` — there is no admin flag; this test exists as a regression to assert that no such field/parameter is silently accepted.

**Done when:** the kill-switch + submit_proposal interaction proves the trust boundary is intact through the MCP layer.

---

### Task 7.3 — MCP SDK wiring

Bring in the MCP Java SDK (`io.modelcontextprotocol.sdk:mcp:<latest>`), build a `McpServer` adapter that:
- Registers every `Tool` from a `ToolRegistry` as an MCP tool with its `inputSchema()`.
- Translates SDK invocations into `Tool.invoke(...)` calls and serializes `ToolResult` back.
- Starts an stdio server on `main()`.

This task includes a `Main` class in `mcp-bridge/`: it wires a default `RiskPipeline`, opens an `AsyncAuditLog(SqliteAuditLog(...))`, constructs the six tools, registers them, and starts the MCP server on stdio.

**Note on SDK version:** the MCP Java SDK is at 0.x. The exact Maven coordinate is verified at task start, not now. If the SDK API surface is unstable, the adapter is the *only* file that needs updating later — tools and tests stay transport-agnostic.

**Tests:**
- Adapter unit tests: stub the SDK's `Tools.List` / `Tools.Call` and assert correct delegation.
- A round-trip test against a real in-process SDK if the SDK ships an in-memory transport.
- `mainCanLaunch` smoke test — runs `Main.main(new String[]{"--check"})` (a flag we add) which prints the registered tool names and exits 0, so we have a runnable entry point that doesn't require an MCP client to verify it boots.

**Done when:** `./gradlew :mcp-bridge:run` (or `:mcp-bridge:run --args="--check"`) prints the tool registry and exits cleanly.

---

## Out of Scope

- HTTP/SSE transport. Stdio only this phase.
- Authentication / per-tool ACLs. Phase 8+.
- Streaming tool outputs.
- Dynamic tool registration at runtime.
- The actual LangChain4j agent — Phase 8.

---

## Risks

- **MCP Java SDK churn.** The SDK is pre-1.0. We keep the surface tiny: only one adapter file knows about the SDK.
- **`get_snapshot` consistency.** The snapshot is from a lock-free `AtomicReference` read; it can be one update behind the position book. Document this in the tool's `description`.
- **Audit log read on `list_recent_decisions`.** Reads go through the synchronous `SqliteAuditLog`. If the agent hammers this, it will serialize on the connection lock. Acceptable for Phase 7; revisit if it becomes a bottleneck.

---

## Build deps

```
implementation(project(":core"))
implementation("com.fasterxml.jackson.core:jackson-databind:<current>")
// Task 7.3 only:
implementation("io.modelcontextprotocol.sdk:mcp:<latest 0.x>")
```
