package com.risksentinel.eval.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * In-memory {@link MarketDataSource}. The constructor performs a stable sort
 * by timestamp — bars that share a timestamp keep their input order so
 * cross-symbol determinism is preserved.
 */
public final class InMemoryMarketData implements MarketDataSource {

    private final List<Bar> bars;

    public InMemoryMarketData(List<Bar> bars) {
        Objects.requireNonNull(bars, "bars");
        List<Bar> copy = new ArrayList<>(bars);
        copy.sort(Comparator.comparing(Bar::timestamp));
        this.bars = List.copyOf(copy);
    }

    @Override
    public Iterator<Bar> iterator() {
        return bars.iterator();
    }

    public int size() {
        return bars.size();
    }
}
