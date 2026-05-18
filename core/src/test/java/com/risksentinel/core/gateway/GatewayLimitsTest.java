package com.risksentinel.core.gateway;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class GatewayLimitsTest {

    private GatewayLimits valid() {
        return new GatewayLimits(
                10_000L,
                1_000_000.0,
                500_000.0,
                0.5,
                0.4,
                0.10,
                100_000L,
                Duration.ofSeconds(5));
    }

    @Test
    void shouldConstruct_whenAllFieldsValid() {
        assertThatCode(this::valid).doesNotThrowAnyException();
    }

    @Test
    void shouldReject_whenMaxPositionQtyNonPositive() {
        assertThatThrownBy(() -> new GatewayLimits(
                0L, 1_000_000.0, 500_000.0, 0.5, 0.4, 0.10, 100_000L, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenMaxGrossExposureNonPositive() {
        assertThatThrownBy(() -> new GatewayLimits(
                10_000L, 0.0, 500_000.0, 0.5, 0.4, 0.10, 100_000L, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenMaxHHIOutsideUnitInterval() {
        assertThatThrownBy(() -> new GatewayLimits(
                10_000L, 1_000_000.0, 500_000.0, 1.5, 0.4, 0.10, 100_000L, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayLimits(
                10_000L, 1_000_000.0, 500_000.0, -0.1, 0.4, 0.10, 100_000L, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenMaxSectorWeightOutsideUnitInterval() {
        assertThatThrownBy(() -> new GatewayLimits(
                10_000L, 1_000_000.0, 500_000.0, 0.5, 1.01, 0.10, 100_000L, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenFatFingerPriceDevNegative() {
        assertThatThrownBy(() -> new GatewayLimits(
                10_000L, 1_000_000.0, 500_000.0, 0.5, 0.4, -0.01, 100_000L, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenMaxSnapshotAgeNonPositive() {
        assertThatThrownBy(() -> new GatewayLimits(
                10_000L, 1_000_000.0, 500_000.0, 0.5, 0.4, 0.10, 100_000L, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayLimits(
                10_000L, 1_000_000.0, 500_000.0, 0.5, 0.4, 0.10, 100_000L, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
