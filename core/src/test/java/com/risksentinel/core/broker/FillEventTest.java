package com.risksentinel.core.broker;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FillEventTest {

    private static final Instant T0 = Instant.parse("2026-05-18T12:00:00Z");

    @Test
    void shouldAccept_whenAllFieldsValid() {
        assertThatCode(() -> new FillEvent(1L, "ord-1", "prop-1", 100L, 150.0, T0))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldReject_whenOrderIdNull() {
        assertThatThrownBy(() -> new FillEvent(1L, null, "prop-1", 100L, 150.0, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReject_whenProposalIdNull() {
        assertThatThrownBy(() -> new FillEvent(1L, "ord-1", null, 100L, 150.0, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReject_whenOrderIdBlank() {
        assertThatThrownBy(() -> new FillEvent(1L, "", "prop-1", 100L, 150.0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenFillIdIsZeroOrNegative() {
        assertThatThrownBy(() -> new FillEvent(0L, "ord-1", "prop-1", 100L, 150.0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FillEvent(-1L, "ord-1", "prop-1", 100L, 150.0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenFilledQuantityIsZeroOrNegative() {
        assertThatThrownBy(() -> new FillEvent(1L, "ord-1", "prop-1", 0L, 150.0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FillEvent(1L, "ord-1", "prop-1", -50L, 150.0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenFilledPriceIsZeroOrNegative() {
        assertThatThrownBy(() -> new FillEvent(1L, "ord-1", "prop-1", 100L, 0.0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FillEvent(1L, "ord-1", "prop-1", 100L, -10.0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenFilledAtNull() {
        assertThatThrownBy(() -> new FillEvent(1L, "ord-1", "prop-1", 100L, 150.0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
