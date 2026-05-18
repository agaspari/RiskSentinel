package com.risksentinel.core.positions;

import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.Trade;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.J_Result;

import java.time.Instant;

@JCStressTest
@Outcome(id = "200", expect = Expect.ACCEPTABLE, desc = "Both trades applied cleanly")
@State
public class PositionBookStressTest {

    private final ConcurrentPositionBook book = new ConcurrentPositionBook();

    private Trade trade(long qty) {
        return new Trade(System.nanoTime(), "port-stress", "AAPL", Side.BUY, qty, 150.0, Instant.now());
    }

    @Actor
    public void actor1() {
        book.apply(trade(100));
    }

    @Actor
    public void actor2() {
        book.apply(trade(100));
    }

    @Arbiter
    public void arbiter(J_Result r) {
        Position pos = book.getPosition("port-stress", "AAPL").orElse(null);
        r.r1 = pos != null ? pos.quantity() : 0L;
    }
}
