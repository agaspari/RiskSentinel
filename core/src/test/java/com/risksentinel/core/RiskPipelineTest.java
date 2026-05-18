package com.risksentinel.core;

import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.RiskSnapshot;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.Trade;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPipelineTest {

    @Test
    void testEndToEndPipeline() throws InterruptedException {
        Instrument aapl = new Instrument("AAPL", "Tech", "US", 150.0);
        RiskPipeline pipeline = new RiskPipeline(Map.of("AAPL", aapl));

        pipeline.start();

        pipeline.submit(new Trade(1, "port-1", "AAPL", Side.BUY, 100, 150.0, Instant.now()));
        pipeline.submit(new Trade(2, "port-2", "AAPL", Side.SELL, 50, 150.0, Instant.now()));

        Thread.sleep(500); // Give it time to process
        pipeline.stop();

        Map<String, RiskSnapshot> snapshots = pipeline.getSnapshotCache().getAllSnapshots();
        assertThat(snapshots).containsKey("port-1");
        assertThat(snapshots).containsKey("port-2");

        assertThat(snapshots.get("port-1").netExposure()).isEqualTo(15000.0);
        assertThat(snapshots.get("port-2").netExposure()).isEqualTo(-7500.0);
    }
}
