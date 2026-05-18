package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskSnapshotCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.risksentinel.core.gateway.CheckTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class PreTradeGatewayTest {

    private RiskSnapshotCache cache;
    private GatewayState state;
    private GatewayLimits limits;
    private PreTradeGateway gateway;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentRiskSnapshotCache();
        state = new GatewayState();
        limits = defaultLimits();
        gateway = new PreTradeGateway(cache, REGISTRY, limits, state, Clock.systemUTC());
    }

    @Test
    void shouldAccept_whenAllChecksPass() {
        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);
        GatewayDecision d = gateway.decide(p);
        assertThat(d).isInstanceOf(GatewayDecision.Accept.class);
    }

    @Test
    void shouldShortCircuit_whenKillSwitchEngaged() {
        state.engageKillSwitch();
        // Also make the proposal "bad" in other ways to prove no later check ran.
        TradeProposal p = proposal("AAPL", Side.BUY, 999_999L, 9_999.0);

        GatewayDecision d = gateway.decide(p);

        assertThat(d).isInstanceOf(GatewayDecision.Reject.class);
        var reject = (GatewayDecision.Reject) d;
        assertThat(reject.reasons()).hasSize(1);
        assertThat(reject.reasons().get(0).code()).isEqualTo(RejectCode.KILL_SWITCH_ENGAGED);
    }

    @Test
    void shouldShortCircuit_whenDuplicateProposal() {
        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);

        GatewayDecision first = gateway.decide(p);
        assertThat(first).isInstanceOf(GatewayDecision.Accept.class);

        GatewayDecision second = gateway.decide(p);
        assertThat(second).isInstanceOf(GatewayDecision.Reject.class);
        var reject = (GatewayDecision.Reject) second;
        assertThat(reject.reasons()).hasSize(1);
        assertThat(reject.reasons().get(0).code()).isEqualTo(RejectCode.DUPLICATE_PROPOSAL);
    }

    @Test
    void shouldCollectAllReasons_whenMultipleNonFatalChecksFail() {
        // Bad qty (fat-finger), bad price (fat-finger), and oversized position.
        TradeProposal p = proposal("AAPL", Side.BUY, 200_000L, 999.0);

        GatewayDecision d = gateway.decide(p);

        assertThat(d).isInstanceOf(GatewayDecision.Reject.class);
        var reject = (GatewayDecision.Reject) d;
        var codes = reject.reasons().stream().map(RejectReason::code).toList();
        assertThat(codes).contains(
                RejectCode.FAT_FINGER_QUANTITY,
                RejectCode.FAT_FINGER_PRICE_DEVIATION,
                RejectCode.POSITION_SIZE_EXCEEDED);
    }

    @Test
    void shouldRejectWithStaleSnapshot_whenSnapshotTooOld() {
        Instant frozen = Instant.parse("2026-05-18T12:00:00Z");
        Clock fixed = Clock.fixed(frozen, ZoneOffset.UTC);
        GatewayLimits tight = new GatewayLimits(
                10_000L, 1_000_000.0, 500_000.0,
                0.7, 0.6, 0.10, 100_000L, Duration.ofSeconds(5));
        PreTradeGateway tightGw = new PreTradeGateway(cache, REGISTRY, tight, state, fixed);

        // Plant a snapshot dated 10 seconds before frozen.
        RiskSnapshot stale = new RiskSnapshot(
                "snap-old", "port-1",
                0.0, 0.0,
                Map.of(),
                Map.of(),
                Map.of(),
                0.0, 0.0, 0.0,
                frozen.minusSeconds(10));
        cache.updateSnapshots(Map.of("port-1", stale));

        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);
        GatewayDecision d = tightGw.decide(p);

        assertThat(d).isInstanceOf(GatewayDecision.Reject.class);
        var reject = (GatewayDecision.Reject) d;
        assertThat(reject.reasons().get(0).code()).isEqualTo(RejectCode.STALE_SNAPSHOT);
    }

    @Test
    void shouldAccept_whenNoSnapshotYet_andOtherChecksPass() {
        TradeProposal p = proposal("AAPL", Side.BUY, 100L, 150.0);
        GatewayDecision d = gateway.decide(p);

        assertThat(d).isInstanceOf(GatewayDecision.Accept.class);
        var accept = (GatewayDecision.Accept) d;
        assertThat(accept.snapshotId()).isEqualTo("no-snapshot");
    }

    /**
     * Concurrency test: 64 threads each push the same 1,000 proposalIds in random order.
     * Across all 64,000 submissions exactly 1,000 must accept and the remaining
     * 63,000 must reject as DUPLICATE_PROPOSAL. No decisions may be null or throw.
     */
    @Test
    void shouldRemainConsistent_underConcurrentSubmission() throws InterruptedException {
        int threads = 64;
        int proposalsPerThread = 1_000;

        // Build a stable pool of proposalIds shared across all threads.
        String[] pool = new String[proposalsPerThread];
        for (int i = 0; i < proposalsPerThread; i++) {
            pool[i] = "shared-" + i;
        }

        ConcurrentHashMap<String, Instrument> registry = new ConcurrentHashMap<>(REGISTRY);
        PreTradeGateway gw = new PreTradeGateway(
                cache,
                registry,
                new GatewayLimits(
                        Long.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                        1.0, 1.0, 1.0, Long.MAX_VALUE, Duration.ofDays(1)),
                state,
                Clock.systemUTC());

        ExecutorService pool2 = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepts = new AtomicInteger();
        AtomicInteger duplicateRejects = new AtomicInteger();
        AtomicInteger otherRejects = new AtomicInteger();
        AtomicInteger nullOrThrew = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                pool2.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < proposalsPerThread; i++) {
                            String pid = pool[i];
                            TradeProposal proposal = new TradeProposal(
                                    pid, "port-1", "AAPL", Side.BUY,
                                    1L, 150.0, 150.0, "t", 0.9,
                                    "snap-x", Instant.now());
                            try {
                                GatewayDecision d = gw.decide(proposal);
                                if (d == null) {
                                    nullOrThrew.incrementAndGet();
                                } else if (d instanceof GatewayDecision.Accept) {
                                    accepts.incrementAndGet();
                                } else if (d instanceof GatewayDecision.Reject r) {
                                    if (r.reasons().get(0).code() == RejectCode.DUPLICATE_PROPOSAL) {
                                        duplicateRejects.incrementAndGet();
                                    } else {
                                        otherRejects.incrementAndGet();
                                    }
                                }
                            } catch (Throwable e) {
                                nullOrThrew.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool2.shutdownNow();
        }

        assertThat(nullOrThrew.get()).as("no null or thrown decisions").isZero();
        assertThat(otherRejects.get()).as("no non-duplicate rejects with permissive limits").isZero();
        assertThat(accepts.get()).as("exactly one accept per distinct proposalId").isEqualTo(proposalsPerThread);
        assertThat(duplicateRejects.get())
                .as("remaining submissions are duplicates")
                .isEqualTo(threads * proposalsPerThread - proposalsPerThread);
    }

    /** Smoke property: a randomly-built passing proposal always accepts under permissive limits. */
    @Test
    void shouldAcceptArbitraryPermittedProposals() {
        PreTradeGateway permissive = new PreTradeGateway(
                new ConcurrentRiskSnapshotCache(),
                REGISTRY,
                new GatewayLimits(
                        Long.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                        1.0, 1.0, 1.0, Long.MAX_VALUE, Duration.ofDays(1)),
                new GatewayState(),
                Clock.systemUTC());

        for (int i = 0; i < 100; i++) {
            TradeProposal p = new TradeProposal(
                    UUID.randomUUID().toString(),
                    "port-" + i,
                    "AAPL",
                    i % 2 == 0 ? Side.BUY : Side.SELL,
                    1 + (long) (Math.random() * 100),
                    150.0,
                    150.0,
                    "t",
                    0.5,
                    "snap-x",
                    Instant.now());
            assertThat(permissive.decide(p)).isInstanceOf(GatewayDecision.Accept.class);
        }
    }
}
