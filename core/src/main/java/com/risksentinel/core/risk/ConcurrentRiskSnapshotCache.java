package com.risksentinel.core.risk;

import com.risksentinel.core.domain.RiskSnapshot;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class ConcurrentRiskSnapshotCache implements RiskSnapshotCache {

    private final AtomicReference<Map<String, RiskSnapshot>> cache = new AtomicReference<>(Collections.emptyMap());

    @Override
    public void updateSnapshots(Map<String, RiskSnapshot> newSnapshots) {
        cache.updateAndGet(current -> {
            Map<String, RiskSnapshot> updated = new HashMap<>(current);
            updated.putAll(newSnapshots);
            return Collections.unmodifiableMap(updated);
        });
    }

    @Override
    public Optional<RiskSnapshot> getSnapshot(String portfolioId) {
        return Optional.ofNullable(cache.get().get(portfolioId));
    }

    @Override
    public Map<String, RiskSnapshot> getAllSnapshots() {
        return cache.get();
    }
}
