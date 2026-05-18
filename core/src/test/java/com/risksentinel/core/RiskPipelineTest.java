package com.risksentinel.core;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.Trade;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.BrokerSink;
import com.risksentinel.core.gateway.GatewayDecision;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.RejectCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPipelineTest {

    /** Captures every accepted proposal forwarded to the broker. */
    static final class RecordingBroker implements BrokerSink {
        final ConcurrentLinkedQueue<TradeProposal> received = new ConcurrentLinkedQueue<>();

        @Override
        public void submit(TradeProposal acceptedProposal) {
            received.add(acceptedProposal);
        }
    }

    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Map<String, Instrument> REGISTRY = Map.of("AAPL", AAPL);

    private GatewayLimits limits() {
        // Permissive concentration caps — these pipeline tests use single-symbol
        // portfolios, so HHI and sector weight will hit 1.0 by construction.
        return new GatewayLimits(
                10_000L, 1_000_000.0, 500_000.0,
                1.0, 1.0, 0.10, 100_000L, Duration.ofMinutes(5));
    }

    private TradeProposal proposal(String symbol, Side side, long qty, double price) {
        return new TradeProposal(
                UUID.randomUUID().toString(),
                "port-1",
                symbol,
                side,
                qty,
                price,
                price,
                "test",
                0.9,
                "snap-x",
                Instant.now());
    }

    private RecordingBroker broker;
    private RiskPipeline pipeline;

    @BeforeEach
    void setUp() {
        broker = new RecordingBroker();
        pipeline = new RiskPipeline(REGISTRY, limits(), broker);
    }

    @Test
    void shouldRouteAcceptedProposalToBroker() {
        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);

        GatewayDecision decision = pipeline.submitProposal(p);

        assertThat(decision).isInstanceOf(GatewayDecision.Accept.class);
        assertThat(broker.received).containsExactly(p);
    }

    @Test
    void shouldNotRouteRejectedProposalToBroker() {
        TradeProposal p = proposal("AAPL", Side.BUY, 200_000L, 150.0); // qty above ceiling

        GatewayDecision decision = pipeline.submitProposal(p);

        assertThat(decision).isInstanceOf(GatewayDecision.Reject.class);
        assertThat(broker.received).isEmpty();
    }

    @Test
    void shouldRejectAllProposals_whenKillSwitchEngaged() {
        pipeline.getGatewayState().engageKillSwitch();

        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);
        GatewayDecision decision = pipeline.submitProposal(p);

        assertThat(decision).isInstanceOf(GatewayDecision.Reject.class);
        var reject = (GatewayDecision.Reject) decision;
        assertThat(reject.reasons().get(0).code()).isEqualTo(RejectCode.KILL_SWITCH_ENGAGED);
        assertThat(broker.received).isEmpty();
    }

    /**
     * End-to-end: ingest fills, wait for the snapshot to reflect them, submit
     * a proposal against the resulting snapshot, verify accept + broker routing.
     */
    @Test
    void shouldProcessEndToEnd_fillsThenProposal() throws InterruptedException {
        pipeline.start();
        try {
            pipeline.submit(new Trade(1L, "port-1", "AAPL", Side.BUY, 100, 150.0, Instant.now()));
            pipeline.submit(new Trade(2L, "port-1", "AAPL", Side.BUY, 50, 150.0, Instant.now()));

            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) {
                RiskSnapshot snap = pipeline.getSnapshotCache().getSnapshot("port-1").orElse(null);
                if (snap != null
                        && snap.positions().get("AAPL") != null
                        && snap.positions().get("AAPL").quantity() == 150L) {
                    break;
                }
                Thread.sleep(20);
            }

            TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);
            GatewayDecision decision = pipeline.submitProposal(p);

            assertThat(decision).isInstanceOf(GatewayDecision.Accept.class);
            assertThat(broker.received).containsExactly(p);
        } finally {
            pipeline.stop();
        }
    }

    @Test
    void shouldIngestFillsForMultiplePortfolios() throws InterruptedException {
        pipeline.start();
        try {
            pipeline.submit(new Trade(1L, "port-1", "AAPL", Side.BUY, 100, 150.0, Instant.now()));
            pipeline.submit(new Trade(2L, "port-2", "AAPL", Side.SELL, 50, 150.0, Instant.now()));

            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) {
                if (pipeline.getSnapshotCache().getSnapshot("port-1").isPresent()
                        && pipeline.getSnapshotCache().getSnapshot("port-2").isPresent()) {
                    break;
                }
                Thread.sleep(20);
            }

            Map<String, RiskSnapshot> snapshots = pipeline.getSnapshotCache().getAllSnapshots();
            assertThat(snapshots).containsKeys("port-1", "port-2");
            assertThat(snapshots.get("port-1").netExposure()).isEqualTo(15_000.0);
            assertThat(snapshots.get("port-2").netExposure()).isEqualTo(-7_500.0);
        } finally {
            pipeline.stop();
        }
    }
}
