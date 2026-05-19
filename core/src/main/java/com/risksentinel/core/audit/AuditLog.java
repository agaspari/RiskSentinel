package com.risksentinel.core.audit;

import java.util.List;
import java.util.Optional;

/**
 * Durable record of gateway decisions. Implementations may persist synchronously
 * (e.g. {@code SqliteAuditLog}) or queue writes onto a background writer
 * (e.g. {@code AsyncAuditLog}).
 *
 * <p>{@link #record(DecisionRecord)} is best-effort: it is not part of the
 * gateway's trust boundary. Read methods return whatever has been durably
 * committed at the time of the call.
 */
public interface AuditLog extends AutoCloseable {

    void record(DecisionRecord record);

    Optional<DecisionRecord> findByProposalId(String proposalId);

    /** Most-recent-first, bounded by {@code limit}. */
    List<DecisionRecord> findByPortfolio(String portfolioId, int limit);

    long count();

    @Override
    void close();
}
