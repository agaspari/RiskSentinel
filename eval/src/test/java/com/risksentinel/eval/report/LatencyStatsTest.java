package com.risksentinel.eval.report;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatencyStatsTest {

    @Test
    void shouldReturnZeros_forEmptyHistogram() {
        Histogram h = new Histogram(2);
        LatencyStats s = LatencyStats.fromHistogram(h);
        assertThat(s.count()).isZero();
        assertThat(s.p50Nanos()).isZero();
        assertThat(s.p99Nanos()).isZero();
        assertThat(s.maxNanos()).isZero();
    }

    @Test
    void shouldComputePercentiles_forKnownInput() {
        Histogram h = new Histogram(2);
        // Record 100 values from 1 to 100.
        for (long v = 1; v <= 100; v++) h.recordValue(v);
        LatencyStats s = LatencyStats.fromHistogram(h);
        assertThat(s.count()).isEqualTo(100L);
        // HdrHistogram with 2 sig digits — percentile values may bucket slightly.
        assertThat(s.p50Nanos()).isBetween(48L, 52L);
        assertThat(s.p95Nanos()).isBetween(92L, 99L);
        assertThat(s.maxNanos()).isEqualTo(100L);
    }

    @Test
    void shouldReject_whenCountNegative() {
        assertThatThrownBy(() -> new LatencyStats(-1, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
