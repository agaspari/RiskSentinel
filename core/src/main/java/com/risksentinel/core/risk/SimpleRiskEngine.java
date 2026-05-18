package com.risksentinel.core.risk;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Position;
import com.risksentinel.core.domain.RiskSnapshot;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SimpleRiskEngine implements RiskEngine {

    @Override
    public RiskSnapshot compute(String portfolioId, Collection<Position> positions, Map<String, Instrument> instruments) {
        double netExposure = 0.0;
        double grossExposure = 0.0;
        
        Map<String, Double> sectorExposure = new HashMap<>();
        Map<String, Double> regionExposure = new HashMap<>();
        
        for (Position pos : positions) {
            Instrument instrument = instruments.get(pos.symbol());
            double value = instrument != null ? pos.quantity() * instrument.price() : pos.marketValue();
            
            netExposure += value;
            grossExposure += Math.abs(value);
            
            if (instrument != null) {
                sectorExposure.merge(instrument.sector(), Math.abs(value), Double::sum);
                regionExposure.merge(instrument.region(), Math.abs(value), Double::sum);
            }
        }
        
        double hhi = 0.0;
        if (grossExposure > 0.0) {
            for (Position pos : positions) {
                Instrument instrument = instruments.get(pos.symbol());
                double value = instrument != null ? pos.quantity() * instrument.price() : pos.marketValue();
                double weight = Math.abs(value) / grossExposure;
                hhi += weight * weight;
            }
        }
        
        return new RiskSnapshot(
                UUID.randomUUID().toString(),
                portfolioId,
                netExposure,
                sectorExposure,
                regionExposure,
                hhi,
                0.0, // parametricVaR95
                0.0, // dailyPnL
                Instant.now()
        );
    }
}
