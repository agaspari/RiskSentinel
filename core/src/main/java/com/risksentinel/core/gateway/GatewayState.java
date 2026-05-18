package com.risksentinel.core.gateway;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable, thread-safe state owned by the gateway.
 *
 * <p>Holds two pieces of state:
 * <ul>
 *   <li>The <strong>kill switch</strong>, a single {@link AtomicBoolean} ops can flip
 *       to halt every proposal regardless of any other check.</li>
 *   <li>The <strong>idempotency record</strong>, a {@link ConcurrentHashMap} of every
 *       {@code proposalId} seen so far. {@link #recordProposalIfAbsent} is an atomic
 *       CAS — concurrent submissions of the same id see exactly one {@code true}.</li>
 * </ul>
 *
 * <p><strong>Phase 3 caveat:</strong> {@code seenProposals} grows unbounded.
 * Phase 5 (ops) will add bounded eviction; for now we accept the leak as the
 * cost of strict idempotency.
 */
public final class GatewayState {

    private final AtomicBoolean killSwitch = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Instant> seenProposals = new ConcurrentHashMap<>();

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
        Objects.requireNonNull(proposalId, "proposalId cannot be null");
        Objects.requireNonNull(at, "at cannot be null");
        return seenProposals.putIfAbsent(proposalId, at) == null;
    }

    /** Number of distinct proposalIds recorded. Useful for tests and ops. */
    public int seenProposalCount() {
        return seenProposals.size();
    }
}
