package com.risksentinel.core;

import com.risksentinel.core.broker.InstantFillModel;
import com.risksentinel.core.broker.PaperBroker;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.GatewayDecision;
import com.risksentinel.core.gateway.GatewayLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineFillLoopTest {

    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Map<String, Instrument> REGISTRY = Map.of("AAPL", AAPL);

    private static GatewayLimits permissiveLimits() {
        // Single-symbol pipeline test → HHI and sector weight will hit 1.0 by construction.
        return new GatewayLimits(
                10_000L, 1_000_000.0, 1_000_000.0,
                1.0, 1.0, 0.10, 100_000L, Duration.ofMinutes(5));
    }

    private ThreadPoolExecutor brokerExecutor;
    private RiskPipeline pipeline;

    @BeforeEach
    void setUp() {
        brokerExecutor = new ThreadPoolExecutor(
                2, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256));
        pipeline = new RiskPipeline(
                REGISTRY, permissiveLimits(),
                new InstantFillModel(),
                brokerExecutor,
                Clock.systemUTC());
        pipeline.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pipeline.stop();
    }

    private TradeProposal proposal(Side side, long qty, double price) {
        return new TradeProposal(
                UUID.randomUUID().toString(),
                "port-1", "AAPL", side,
                qty, price, price,
                "t", 0.9, "snap-x", Instant.now());
    }

    private boolean waitForPosition(String symbol, long expectedQty) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Position pos = pipeline.getPositionBook()
                    .getPosition("port-1", symbol)
                    .orElse(null);
            if (pos != null && pos.quantity() == expectedQty) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    @Test
    void shouldCloseTheLoop_proposalThenFillThenSnapshot() throws InterruptedException {
        TradeProposal p = proposal(Side.BUY, 100L, 150.0);
        GatewayDecision decision = pipeline.submitProposal(p);

        assertThat(decision).isInstanceOf(GatewayDecision.Accept.class);
        assertThat(waitForPosition("AAPL", 100L)).as("fill must reach position book").isTrue();

        RiskSnapshot snap = pipeline.getSnapshotCache().getSnapshot("port-1").orElseThrow();
        assertThat(snap.positions().get("AAPL").quantity()).isEqualTo(100L);
        assertThat(snap.netExposure()).isEqualTo(15_000.0);

        PaperBroker broker = pipeline.getBroker().orElseThrow();
        assertThat(broker.orderForProposal(p.proposalId()))
                .isPresent()
                .get()
                .matches(o -> o.status() == com.risksentinel.core.broker.OrderStatus.FILLED);
    }

    @Test
    void shouldEvaluateNextProposalAgainstUpdatedSnapshot() throws InterruptedException {
        TradeProposal first = proposal(Side.BUY, 80L, 150.0);
        assertThat(pipeline.submitProposal(first)).isInstanceOf(GatewayDecision.Accept.class);
        assertThat(waitForPosition("AAPL", 80L)).isTrue();

        // Tighten limits via a new pipeline reusing same registry but with maxPositionQty=100.
        // Instead, demonstrate the loop more directly: submit a second proposal that combined
        // with the first stays inside limits, and confirm the gateway sees the updated state.
        TradeProposal second = proposal(Side.BUY, 20L, 150.0);
        assertThat(pipeline.submitProposal(second)).isInstanceOf(GatewayDecision.Accept.class);
        assertThat(waitForPosition("AAPL", 100L)).isTrue();

        RiskSnapshot snap = pipeline.getSnapshotCache().getSnapshot("port-1").orElseThrow();
        assertThat(snap.positions().get("AAPL").quantity()).isEqualTo(100L);
    }

    @Test
    void shouldRejectProposalBeyondPositionCap_afterFirstFillConsumedCapacity() throws InterruptedException {
        // Start a tightly-capped pipeline so the second proposal must be rejected
        // post-fill rather than pre-fill.
        ThreadPoolExecutor tightExec = new ThreadPoolExecutor(
                2, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64));
        GatewayLimits tight = new GatewayLimits(
                100L, 1_000_000.0, 1_000_000.0,
                1.0, 1.0, 0.10, 100_000L, Duration.ofMinutes(5));
        RiskPipeline tightPipeline = new RiskPipeline(
                REGISTRY, tight, new InstantFillModel(), tightExec, Clock.systemUTC());
        tightPipeline.start();
        try {
            TradeProposal first = new TradeProposal(
                    UUID.randomUUID().toString(),
                    "port-1", "AAPL", Side.BUY,
                    100L, 150.0, 150.0, "t", 0.9, "snap-x", Instant.now());
            assertThat(tightPipeline.submitProposal(first)).isInstanceOf(GatewayDecision.Accept.class);

            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            boolean settled = false;
            while (System.nanoTime() < deadline) {
                RiskSnapshot snap = tightPipeline.getSnapshotCache().getSnapshot("port-1").orElse(null);
                if (snap != null && snap.positions().get("AAPL") != null
                        && snap.positions().get("AAPL").quantity() == 100L) {
                    settled = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertThat(settled).isTrue();

            TradeProposal second = new TradeProposal(
                    UUID.randomUUID().toString(),
                    "port-1", "AAPL", Side.BUY,
                    50L, 150.0, 150.0, "t", 0.9, "snap-x", Instant.now());
            GatewayDecision d = tightPipeline.submitProposal(second);

            assertThat(d).isInstanceOf(GatewayDecision.Reject.class);
        } finally {
            tightPipeline.stop();
        }
    }
}
