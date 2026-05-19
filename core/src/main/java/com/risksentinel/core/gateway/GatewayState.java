package com.risksentinel.core.gateway;

import com.risksentinel.core.ops.BoundedIdempotencyCache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable, thread-safe state owned by the gateway.
 *
 * <p>Holds two pieces of state:
 * <ul>
 *   <li>The <strong>kill switch</strong>, a single {@link AtomicBoolean} ops can flip
 *       to halt every proposal regardless of any other check.</li>
 *   <li>The <strong>idempotency record</strong>, a {@link BoundedIdempotencyCache}
 *       whose {@code recordIfAbsent} is an atomic per-key CAS; concurrent
 *       submissions of the same id see exactly one {@code true}. Entries are
 *       evicted by TTL and a hard size cap (Phase 5).</li>
 * </ul>
 */
public final class GatewayState {

    /** Default retention: 1 hour TTL, 1M entry cap, sweep every minute. */
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);
    private static final int DEFAULT_MAX_SIZE = 1_000_000;
    private static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final AtomicBoolean killSwitch = new AtomicBoolean(false);
    private final BoundedIdempotencyCache seenProposals;

    public GatewayState() {
        this(new BoundedIdempotencyCache(
                DEFAULT_TTL, DEFAULT_MAX_SIZE, DEFAULT_SWEEP_INTERVAL, Clock.systemUTC()));
    }

    public GatewayState(BoundedIdempotencyCache seenProposals) {
        this.seenProposals = Objects.requireNonNull(seenProposals, "seenProposals");
    }

    public boolean isKillSwitchEngaged() {
        return killSwitch.get();
    }

    public void engageKillSwitch() {
        killSwitch.set(true);
    }

    public void disengageKillSwitch() {
        killSwitch.set(false);
    }

    /**
     * Atomically records {@code proposalId} if it has not been seen.
     *
     * @return {@code true} iff this call was the first to record the id,
     *         {@code false} if it was already present
     */
    public boolean recordProposalIfAbsent(String proposalId, Instant at) {
        return seenProposals.recordIfAbsent(proposalId, at);
    }

    /** Number of distinct proposalIds currently retained. */
    public int seenProposalCount() {
        return seenProposals.size();
    }

    /** Releases the cache's background sweeper. */
    public void shutdown() {
        seenProposals.shutdown();
    }
}
