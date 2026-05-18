# Phase 2 Commit Notes

Use these notes for your commit message before rolling into Phase 3. 

## Summary
`feat: Complete Phase 2 concurrent risk pipeline`

Implemented the concurrent, high-throughput ingestion pipeline and lock-striped position tracking outlined in the Phase 2 specification. Verified thread safety and fixed latent calculation issues in the risk engine. All 49 unit and property tests are passing.

## Detailed Changes

### Concurrency & Performance
- **`ConcurrentPositionBook`**: Implemented lock-striping using a 64-element array of `Object` locks. Portfolios are hashed to specific stripes (`hashCode & 63`), allowing parallel processing of trades across different portfolios without lock contention.
- **`ConcurrentRiskSnapshotCache`**: Implemented a lock-free snapshot reader using `AtomicReference`. Risk snapshots are securely updated using `updateAndGet` to ensure zero blocking on read paths.
- **`TradeIngestor`**: Added a dedicated background worker (`ExecutorService`) consuming from a `BlockingQueue<Trade>`. This cleanly separates the trade ingestion from the synchronous application of positions.
- **`RiskPipeline`**: Built a new pipeline manager to act as the single entry point. It wires the ingestor, position book, risk engine, and snapshot cache together and exposes `start()` / `stop()` lifecycle methods.

### Bug Fixes
- **`SimpleRiskEngine`**: Fixed a bug where `netExposure` was returning `0.0`. The engine was previously referencing the immutable `Position`'s `marketValue()` (which is zeroed out by `PositionBook`). It now correctly recalculates live exposure using `position.quantity() * instrument.price()`.

### Testing
- **`ConcurrentPositionBookTest`**: Validated lock-striping integrity by unleashing an `ExecutorService` with 10 threads hammering 10,000 trades against a single portfolio. The final `avgCost` and `quantity` resolve exactly as expected without race conditions.
- **`RiskPipelineTest`**: Added a pipeline end-to-end integration test validating the asynchronous blocking queue correctly computes and stores risk snapshots.
- **`jcstress`**: Configured the OpenJDK `jcstress` concurrency harness in `build.gradle.kts` and built `PositionBookStressTest` to mathematically prove the absence of race conditions on state mutations.
