package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Fill model that consults a per-symbol "current close" map. Fills at the
 * current close if the order's limit would cross — i.e. for a BUY, fills
 * iff {@code close <= limitPrice}; for a SELL, iff {@code close >= limitPrice}.
 * Otherwise returns {@link Optional#empty()} so the order remains working.
 *
 * <p>Designed for the backtest harness, which updates the price map per bar
 * before invoking {@link PaperBroker#submit}. Lives in core/broker/ because
 * any future paper-trading layer with snapshot pricing will reuse it.
 */
public final class BarPriceFillModel implements FillModel {

    private final Supplier<Map<String, Double>> currentClosesBySymbol;

    public BarPriceFillModel(Supplier<Map<String, Double>> currentClosesBySymbol) {
        this.currentClosesBySymbol = Objects.requireNonNull(currentClosesBySymbol, "currentClosesBySymbol");
    }

    @Override
    public Optional<FillEvent> simulate(
            Order order,
            Instrument instrument,
            Instant now,
            LongSupplier fillIdGenerator) {
        Map<String, Double> closes = currentClosesBySymbol.get();
        if (closes == null) return Optional.empty();
        Double close = closes.get(order.symbol());
        if (close == null) return Optional.empty();

        boolean crosses = order.side() == Side.BUY
                ? close <= order.limitPrice()
                : close >= order.limitPrice();
        if (!crosses) return Optional.empty();

        return Optional.of(new FillEvent(
                fillIdGenerator.getAsLong(),
                order.orderId(),
                order.proposalId(),
                order.quantity(),
                close,
                now));
    }
}
