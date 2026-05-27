package com.risksentinel.eval.property;

import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.eval.data.Bar;
import com.risksentinel.eval.strategy.Strategy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Test-only strategy that emits a deterministic but adversarial mix of
 * proposals: 0–{@code maxProposalsPerBar} per bar, each a random side with a
 * random quantity in {@code [1, maxQuantity]}.
 *
 * <p>Sized so the gateway will accept many but reject the over-large
 * proposals — the point of the property test is to confirm the rejects do
 * their job.
 */
final class NoisyStrategy implements Strategy {

    private final String portfolioId;
    private final long seed;
    private final int maxProposalsPerBar;
    private final long maxQuantity;
    private final Random random;
    private long sequence = 0L;

    NoisyStrategy(String portfolioId, long seed, int maxProposalsPerBar, long maxQuantity) {
        Objects.requireNonNull(portfolioId, "portfolioId");
        if (portfolioId.isBlank()) throw new IllegalArgumentException("portfolioId");
        if (maxProposalsPerBar < 0) throw new IllegalArgumentException("maxProposalsPerBar");
        if (maxQuantity < 1) throw new IllegalArgumentException("maxQuantity");
        this.portfolioId = portfolioId;
        this.seed = seed;
        this.maxProposalsPerBar = maxProposalsPerBar;
        this.maxQuantity = maxQuantity;
        this.random = new Random(seed);
    }

    @Override
    public String name() {
        return "Noisy[" + portfolioId + ":seed=" + seed + "]";
    }

    @Override
    public String portfolioId() {
        return portfolioId;
    }

    @Override
    public List<TradeProposal> onBar(Bar bar, RiskSnapshot snapshot, Clock clock) {
        int n = random.nextInt(maxProposalsPerBar + 1);
        if (n == 0) return List.of();
        String snapshotId = snapshot != null ? snapshot.snapshotId() : "no-snapshot";
        List<TradeProposal> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sequence++;
            long quantity = 1L + (long) (random.nextDouble() * maxQuantity);
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            out.add(new TradeProposal(
                    name() + "-" + bar.timestamp().toEpochMilli() + "-" + bar.symbol() + "-" + sequence,
                    portfolioId,
                    bar.symbol(),
                    side,
                    quantity,
                    bar.close(),
                    bar.close(),
                    "noisy adversarial",
                    0.5,
                    snapshotId,
                    clock.instant()));
        }
        return out;
    }
}
