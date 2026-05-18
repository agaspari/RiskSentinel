package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.risk.RiskSnapshotCache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The trust boundary. Every {@link TradeProposal} must pass {@link #decide}
 * before any downstream system can act on it.
 *
 * <p>The gateway is synchronous and deterministic. It performs no I/O, no
 * external calls, and never acquires position-book stripe locks — all state
 * comes from a single wait-free read of {@link RiskSnapshotCache} plus
 * {@link GatewayState}.
 *
 * <p>Checks run in a fixed cost-ordered sequence. The orchestrator
 * <em>collects every {@link RejectReason}</em> produced — except for two
 * "existential" rejects ({@code KILL_SWITCH_ENGAGED} and
 * {@code DUPLICATE_PROPOSAL}), which short-circuit the chain because no
 * further check is meaningful when the system is halted or the id has
 * already been decided.
 */
public final class PreTradeGateway {

    private final RiskSnapshotCache snapshotCache;
    private final Map<String, Instrument> instrumentRegistry;
    private final GatewayLimits limits;
    private final GatewayState state;
    private final List<RiskCheck> checks;
    private final Clock clock;

    public PreTradeGateway(
            RiskSnapshotCache snapshotCache,
            Map<String, Instrument> instrumentRegistry,
            GatewayLimits limits,
            GatewayState state,
            Clock clock) {
        this.snapshotCache = Objects.requireNonNull(snapshotCache, "snapshotCache");
        this.instrumentRegistry = Map.copyOf(Objects.requireNonNull(instrumentRegistry, "instrumentRegistry"));
        this.limits = Objects.requireNonNull(limits, "limits");
        this.state = Objects.requireNonNull(state, "state");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.checks = List.of(
                new KillSwitchCheck(),
                new IdempotencyCheck(),
                new FatFingerCheck(),
                new PositionSizeCheck(),
                new NotionalExposureCheck(),
                new ConcentrationCheck());
    }

    /** Convenience for tests that don't supply a clock. */
    public PreTradeGateway(
            RiskSnapshotCache snapshotCache,
            Map<String, Instrument> instrumentRegistry,
            GatewayLimits limits,
            GatewayState state) {
        this(snapshotCache, instrumentRegistry, limits, state, Clock.systemUTC());
    }

    /** Direct access to the kill switch for ops endpoints. */
    public GatewayState state() {
        return state;
    }

    /**
     * Evaluate the proposal against every configured check. Always returns a
     * non-null {@link GatewayDecision}. Never throws.
     */
    public GatewayDecision decide(TradeProposal proposal) {
        Objects.requireNonNull(proposal, "proposal cannot be null");
        Instant now = clock.instant();

        RiskSnapshot snapshot = snapshotCache.getSnapshot(proposal.portfolioId()).orElse(null);
        Instrument instrument = instrumentRegistry.get(proposal.symbol());
        GatewayContext ctx = new GatewayContext(snapshot, instrument, limits, state, now);

        // Stale-snapshot guard. If a snapshot exists but is too old we cannot
        // trust the state it describes — reject immediately.
        if (snapshot != null
                && Duration.between(snapshot.computedAt(), now).compareTo(limits.maxSnapshotAge()) > 0) {
            return new GatewayDecision.Reject(
                    proposal.proposalId(),
                    List.of(new RejectReason(
                            "StaleSnapshotCheck",
                            RejectCode.STALE_SNAPSHOT,
                            "Snapshot is older than configured maxSnapshotAge")),
                    now);
        }

        List<RejectReason> reasons = new ArrayList<>();
        for (RiskCheck check : checks) {
            List<RejectReason> emitted = check.check(proposal, ctx);
            if (emitted.isEmpty()) {
                continue;
            }
            reasons.addAll(emitted);
            // Existential short-circuits: nothing else is meaningful.
            RejectCode firstCode = emitted.get(0).code();
            if (firstCode == RejectCode.KILL_SWITCH_ENGAGED
                    || firstCode == RejectCode.DUPLICATE_PROPOSAL) {
                return new GatewayDecision.Reject(proposal.proposalId(), reasons, now);
            }
        }

        if (!reasons.isEmpty()) {
            return new GatewayDecision.Reject(proposal.proposalId(), reasons, now);
        }
        return new GatewayDecision.Accept(
                proposal.proposalId(),
                snapshot != null ? snapshot.snapshotId() : "no-snapshot",
                now);
    }
}
