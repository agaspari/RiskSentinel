package com.risksentinel.eval.runner;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link java.util.concurrent.ExecutorService} that runs every submitted task
 * synchronously on the calling thread. Used by the backtest to give the broker
 * something that satisfies its constructor signature while preserving
 * determinism (submit → fill → position-update all happen before
 * {@code execute()} returns).
 *
 * <p>Lifecycle methods are no-ops; nothing to shut down. Not suitable for
 * production use.
 */
final class DirectExecutorService extends AbstractExecutorService {

    private volatile boolean shutdown = false;

    @Override
    public void shutdown() {
        this.shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        this.shutdown = true;
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public void execute(Runnable command) {
        command.run();
    }
}
