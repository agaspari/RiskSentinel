package com.risksentinel.core.audit;

import java.util.List;
import java.util.Optional;

/**
 * Default audit log that drops everything. Wired by default so the gateway
 * hot path is branch-free when persistence is off.
 */
public final class NoopAuditLog implements AuditLog {

    @Override public void record(DecisionRecord record) {}
    @Override public Optional<DecisionRecord> findByProposalId(String proposalId) { return Optional.empty(); }
    @Override public List<DecisionRecord> findByPortfolio(String portfolioId, int limit) { return List.of(); }
    @Override public long count() { return 0L; }
    @Override public void close() {}
}
