package com.risksentinel.eval.property;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.eval.data.SyntheticBarGenerator;
import com.risksentinel.eval.report.BacktestReport;
import com.risksentinel.eval.runner.BacktestRunner;
import com.risksentinel.eval.runner.BacktestSystem;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test: for any seeded bar sequence and any seeded noisy strategy,
 * the gateway must never let the position book or risk snapshot fall outside
 * its configured limits — checked after every fill (not just at the end), so
 * a "gateway accepted but oversize fill applied" bug cannot hide.
 */
class GatewayInvariantPropertyTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");
    private static final String PORTFOLIO = "port-1";

    private static final long MAX_POSITION_QTY = 500L;
    private static final double MAX_GROSS_EXPOSURE = 200_000.0;
    private static final double MAX_NET_EXPOSURE = 150_000.0;
    private static final double MAX_HHI = 1.0;
    private static final double MAX_SECTOR_WEIGHT = 1.0;

    private static GatewayLimits limits() {
        return new GatewayLimits(
                MAX_POSITION_QTY,
                MAX_GROSS_EXPOSURE,
                MAX_NET_EXPOSURE,
                MAX_HHI,
                MAX_SECTOR_WEIGHT,
                10.0,
                1000L,
                Duration.ofDays(36500));
    }

    private static Map<String, Instrument> instrumentsFor(List<String> symbols) {
        Map<String, Instrument> m = new HashMap<>();
        for (String s : symbols) {
            m.put(s, new Instrument(s, "tech", "US", 150.0));
        }
        return m;
    }

    private static List<String> symbolsFor(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add("SYM" + i);
        }
        return out;
    }

    @Property(tries = 50)
    void shouldNeverViolateAnyRiskInvariant_underAnyStrategy(
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long seed,
            @ForAll @IntRange(min = 10, max = 60) int numBars,
            @ForAll @IntRange(min = 1, max = 3) int numSymbols) {

        List<String> symbols = symbolsFor(numSymbols);
        Map<String, Instrument> instruments = instrumentsFor(symbols);
        Map<String, Double> initialPrices = new HashMap<>();
        for (String s : symbols) initialPrices.put(s, 150.0);

        SyntheticBarGenerator data = new SyntheticBarGenerator(
                seed,
                symbols,
                T0,
                Duration.ofMinutes(1),
                numBars,
                0.0,
                0.01,
                initialPrices);

        BacktestSystem sys = new BacktestSystem(instruments, limits(), T0);

        // After-fill assertion: snapshot must satisfy every gateway invariant.
        sys.setFillObserver(fillEvent -> assertInvariants(sys, "after fill " + fillEvent.fillId()));

        NoisyStrategy strategy = new NoisyStrategy(PORTFOLIO, seed, 3, MAX_POSITION_QTY * 2);
        BacktestReport report = new BacktestRunner(sys).run(data, strategy);

        // Final-state assertions (snapshot if any was produced).
        assertInvariants(sys, "at end of run");

        // Cross-check: accepted+rejected matches; no unexpected codes from bugs.
        assertThat(report.accepted() + report.rejected()).isEqualTo(report.totalProposals());
    }

    private static void assertInvariants(BacktestSystem sys, String context) {
        // 1. Per-symbol position cap.
        for (Position pos : sys.positions().getPositions(PORTFOLIO)) {
            assertThat(Math.abs(pos.quantity()))
                    .as("per-symbol position cap " + context + " on " + pos.symbol())
                    .isLessThanOrEqualTo(MAX_POSITION_QTY);
        }
        // 2/3/4. Snapshot-derived invariants (gross/net/HHI).
        RiskSnapshot snap = sys.snapshots().getSnapshot(PORTFOLIO).orElse(null);
        if (snap == null) return;
        assertThat(snap.grossExposure())
                .as("gross exposure " + context)
                .isLessThanOrEqualTo(MAX_GROSS_EXPOSURE);
        assertThat(Math.abs(snap.netExposure()))
                .as("net exposure " + context)
                .isLessThanOrEqualTo(MAX_NET_EXPOSURE);
        assertThat(snap.concentrationHHI())
                .as("HHI " + context)
                .isLessThanOrEqualTo(MAX_HHI);
    }
}
