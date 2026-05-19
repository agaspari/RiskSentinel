package com.risksentinel.core.broker;

import com.risksentinel.core.domain.Side;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant T0 = Instant.parse("2026-05-18T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);

    private Order validOrder() {
        return new Order(
                "ord-1", "prop-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, OrderStatus.NEW, T0, T0);
    }

    @Test
    void shouldAccept_whenAllFieldsValid() {
        assertThatCode(this::validOrder).doesNotThrowAnyException();
    }

    @Test
    void shouldReject_whenOrderIdNull() {
        assertThatThrownBy(() -> new Order(
                null, "prop-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, OrderStatus.NEW, T0, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReject_whenOrderIdBlank() {
        assertThatThrownBy(() -> new Order(
                "   ", "prop-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, OrderStatus.NEW, T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenProposalIdNull() {
        assertThatThrownBy(() -> new Order(
                "ord-1", null, "port-1", "AAPL", Side.BUY,
                100L, 150.0, OrderStatus.NEW, T0, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReject_whenQuantityIsZeroOrNegative() {
        assertThatThrownBy(() -> new Order(
                "ord-1", "prop-1", "port-1", "AAPL", Side.BUY,
                0L, 150.0, OrderStatus.NEW, T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order(
                "ord-1", "prop-1", "port-1", "AAPL", Side.BUY,
                -10L, 150.0, OrderStatus.NEW, T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenLimitPriceIsZeroOrNegative() {
        assertThatThrownBy(() -> new Order(
                "ord-1", "prop-1", "port-1", "AAPL", Side.BUY,
                100L, 0.0, OrderStatus.NEW, T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order(
                "ord-1", "prop-1", "port-1", "AAPL", Side.BUY,
                100L, -1.0, OrderStatus.NEW, T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReject_whenStatusNull() {
        assertThatThrownBy(() -> new Order(
                "ord-1", "prop-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, null, T0, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReject_whenLastUpdatedBeforeSubmitted() {
        assertThatThrownBy(() -> new Order(
                "ord-1", "prop-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, OrderStatus.NEW, T1, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllow_whenSubmittedEqualsLastUpdated() {
        assertThatCode(() -> new Order(
                "ord-1", "prop-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, OrderStatus.NEW, T0, T0))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldReturnNewOrderWithUpdatedStatus_fromWithStatus() {
        Order original = validOrder();
        Order filled = original.withStatus(OrderStatus.FILLED, T1);

        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(filled.lastUpdatedAt()).isEqualTo(T1);
        assertThat(filled.submittedAt()).isEqualTo(T0);
        assertThat(filled.orderId()).isEqualTo(original.orderId());
        assertThat(original.status()).isEqualTo(OrderStatus.NEW);
    }
}
