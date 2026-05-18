package com.risksentinel.core.risk;

import com.risksentinel.core.domain.RiskSnapshot;

import java.util.Map;
import java.util.Optional;

public interface RiskSnapshotCache {
    void updateSnapshots(Map<String, RiskSnapshot> newSnapshots);
    Optional<RiskSnapshot> getSnapshot(String portfolioId);
    Map<String, RiskSnapshot> getAllSnapshots();
}
