package com.risksentinel.core.ops;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A {@link Clock} whose returned instant can be set imperatively. Intended for
 * backtests and tests that need to advance time through a deterministic
 * sequence — not for production gateways.
 *
 * <p>The current instant is held in a {@code volatile} field so a value
 * written by one thread is visible to readers on other threads. There is no
 * intra-write ordering: a backtest sets {@link #setNow(Instant)} between
 * synchronous phases of work, so reads observe the most recent write by
 * happens-before via the volatile.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant initial) {
        this.now = Objects.requireNonNull(initial, "initial");
    }

    public void setNow(Instant t) {
        this.now = Objects.requireNonNull(t, "t");
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
