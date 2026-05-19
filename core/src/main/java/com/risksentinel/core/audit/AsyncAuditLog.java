package com.risksentinel.core.audit;

import com.risksentinel.core.ops.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps any {@link AuditLog} with an async writer. {@link #record(DecisionRecord)}
 * offers to a bounded queue and returns immediately; a single daemon thread
 * drains the queue and calls the delegate.
 *
 * <p>Backpressure is loud: when the queue is full, {@code record} increments
 * the supplied {@link Counter} and emits a WARN log. We do not block the
 * caller — the gateway is on the synchronous path and must not stall on
 * persistence.
 *
 * <p>{@link #close()} signals the writer to drain pending entries, then
 * delegates to the wrapped log.
 */
public final class AsyncAuditLog implements AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AsyncAuditLog.class);
    private static final AtomicInteger INSTANCE_SEQ = new AtomicInteger();
    private static final DecisionRecord SHUTDOWN_SENTINEL =
            sentinel();

    private final AuditLog delegate;
    private final BlockingQueue<DecisionRecord> queue;
    private final Counter droppedCounter;
    private final Thread writer;
    private volatile boolean running = true;

    public AsyncAuditLog(AuditLog delegate, int queueCapacity, Counter droppedCounter) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.droppedCounter = Objects.requireNonNull(droppedCounter, "droppedCounter");

        int seq = INSTANCE_SEQ.incrementAndGet();
        this.writer = new Thread(this::writerLoop, "audit-writer-" + seq);
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public void record(DecisionRecord record) {
        Objects.requireNonNull(record, "record");
        if (!queue.offer(record)) {
            droppedCounter.increment();
            log.warn("Audit queue saturated; dropping decision proposalId={}", record.proposalId());
        }
    }

    @Override
    public Optional<DecisionRecord> findByProposalId(String proposalId) {
        return delegate.findByProposalId(proposalId);
    }

    @Override
    public List<DecisionRecord> findByPortfolio(String portfolioId, int limit) {
        return delegate.findByPortfolio(portfolioId, limit);
    }

    @Override
    public long count() {
        return delegate.count();
    }

    @Override
    public void close() {
        running = false;
        queue.offer(SHUTDOWN_SENTINEL);
        try {
            writer.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        delegate.close();
    }

    private void writerLoop() {
        while (running || !queue.isEmpty()) {
            try {
                DecisionRecord r = queue.poll(200, TimeUnit.MILLISECONDS);
                if (r == null || r == SHUTDOWN_SENTINEL) continue;
                try {
                    delegate.record(r);
                } catch (Throwable t) {
                    log.error("Async audit writer failed to persist proposalId={}: {}",
                            r.proposalId(), t.toString());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static DecisionRecord sentinel() {
        return new DecisionRecord(
                "__shutdown__", "__shutdown__", "__", com.risksentinel.core.domain.Side.BUY,
                1L, 1.0, "__",
                DecisionType.ACCEPT, null, "[]", java.time.Instant.EPOCH);
    }
}
