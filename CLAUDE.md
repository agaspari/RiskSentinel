# Risk Sentinel — Project Context

A concurrent Java risk and execution platform with an LLM-powered analyst layer.
The deterministic pre-trade risk gateway is the architectural centerpiece and trust boundary.

## Project Goals

1. Exercise production-grade Java concurrency patterns (lock striping, CAS snapshots, structured concurrency)
2. Build a deterministic pre-trade risk gateway that enforces hard invariants
3. Integrate LLM analyst agent via LangChain4j with MCP tool integrations
4. Measure agent quality with a three-layer evaluation harness (synthetic, backtest, paper trading)

## Architectural Invariants — NEVER VIOLATE

1. **The gateway is the trust boundary.** Every trade passes the gateway. No exceptions, no backdoors, no "trusted mode."
2. **The agent is untrusted input.** It can propose anything. The gateway decides what reaches the broker.
3. **The core's correctness is independent of the agent.** If the agent crashes, hangs, or hallucinates, the position book and risk engine remain consistent.
4. **Gateway validation is synchronous and deterministic.** No external calls, no LLM, no I/O on the validation path.

## Concurrency Rules

- All shared mutable state uses **private final Object** lock instances with synchronized blocks. Never `synchronized(this)`.
- Lock striping by portfolio in PositionBook (64 stripes).
- Snapshot cache uses AtomicReference — readers never block, writers do CAS.
- Records are immutable. Use them everywhere for cross-thread data.
- All executors are explicitly configured (core size, max size, queue type, rejection policy). No Executors.newCachedThreadPool().

## Tech Stack

- Java 21 (LTS), virtual threads where appropriate for I/O-bound paths
- Gradle (Kotlin DSL) for build
- JUnit 5 + Mockito for unit tests
- jqwik for property-based tests
- jcstress for concurrency correctness tests (Phase 2+)
- HdrHistogram for latency measurement
- Micrometer + Prometheus for metrics (Phase 5+)
- LangChain4j for agent orchestration (Phase 8+)
- MCP Java SDK for tool integrations (Phase 7+)
- SQLite or DuckDB for decision audit log
- SLF4J + Logback with structured JSON logging, MDC for portfolio/proposal IDs

## Coding Standards

- All public classes get Javadoc on the class and public methods.
- No wildcard imports.
- Records for all value types. Classes only when mutability is required.
- Sealed interfaces for closed type hierarchies (GatewayDecision, etc.).
- Package-private by default. Public only at module boundaries.
- Tests live in the same package as the code they test (separate source root).
- Test method names: `shouldDoX_whenY()` format.
- Every gateway check is its own class implementing RiskCheck.

## Module Layout

```
risk-sentinel/
├── core/
│   ├── domain/          # Records: Trade, Position, Instrument, RiskSnapshot, TradeProposal
│   ├── ingest/          # Fill ingestion from broker (BlockingQueue consumer)
│   ├── positions/       # PositionBook with lock striping
│   ├── risk/            # RiskEngine, RiskSnapshotCache
│   ├── gateway/         # PreTradeGateway, RiskCheck implementations, GatewayState
│   ├── broker/          # Paper broker adapter (sim, later Alpaca)
│   ├── subscribers/     # Snapshot fan-out
│   └── ops/             # Metrics, logging config, kill switch admin
├── analyst/             # Phase 8+
├── mcp-bridge/          # Phase 7+
├── sim/                 # Trade and market data simulator
└── eval/                # Synthetic tests, backtest harness
```

## Build Phases

Current phase: **Phase 1 — Domain + Single-Threaded Pipeline**

See phase1-spec.md for the active spec and task breakdown.

## What NOT To Do

- No Kubernetes, microservices, or service meshes. Single JVM.
- No fancy frontend. CLI or simple HTML dashboard.
- No real money. Paper only. Ever.
- No agent bypass of the gateway. No special modes, no test backdoors.
- Don't claim latencies you didn't measure. Use HdrHistogram, report percentiles.
- Don't let Claude Code write both the tests AND the implementation for concurrency code.
  Tests encode YOUR understanding. Write them yourself or spec them precisely.
