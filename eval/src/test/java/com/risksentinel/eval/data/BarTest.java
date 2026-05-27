package com.risksentinel.eval.data;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BarTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    @Test
    void shouldConstruct_whenAllFieldsValid() {
        Bar b = new Bar("AAPL", T0, 100.0, 102.0, 99.0, 101.0, 1_000L);
        assertThat(b.symbol()).isEqualTo("AAPL");
        assertThat(b.close()).isEqualTo(101.0);
    }

    @Test
    void shouldReject_whenSymbolBlank() {
        assertThatThrownBy(() -> new Bar(" ", T0, 100.0, 102.0, 99.0, 101.0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol");
    }

    @Test
    void shouldReject_whenSymbolNull() {
        assertThatThrownBy(() -> new Bar(null, T0, 100.0, 102.0, 99.0, 101.0, 1L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReject_whenTimestampNull() {
        assertThatThrownBy(() -> new Bar("AAPL", null, 100.0, 102.0, 99.0, 101.0, 1L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReject_whenHighBelowLow() {
        assertThatThrownBy(() -> new Bar("AAPL", T0, 100.0, 99.0, 100.0, 100.0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("high");
    }

    @Test
    void shouldReject_whenHighBelowOpen() {
        assertThatThrownBy(() -> new Bar("AAPL", T0, 105.0, 102.0, 99.0, 101.0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("high");
    }

    @Test
    void shouldReject_whenLowAboveClose() {
        assertThatThrownBy(() -> new Bar("AAPL", T0, 100.0, 102.0, 101.5, 101.0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("low");
    }

    @Test
    void shouldReject_whenVolumeNegative() {
        assertThatThrownBy(() -> new Bar("AAPL", T0, 100.0, 102.0, 99.0, 101.0, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("volume");
    }
}
