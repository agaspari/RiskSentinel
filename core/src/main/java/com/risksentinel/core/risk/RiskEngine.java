package com.risksentinel.core.risk;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;

import java.util.Collection;
import java.util.Map;

public interface RiskEngine {
    RiskSnapshot compute(String portfolioId, Collection<Position> positions, Map<String, Instrument> instruments);
}
