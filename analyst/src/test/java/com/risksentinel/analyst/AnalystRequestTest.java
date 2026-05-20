package com.risksentinel.analyst;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalystRequestTest {

    @Test
    void shouldConstruct_whenAllFieldsValid() {
        AnalystRequest request = new AnalystRequest(
                "PORT-1", "anything to do today?", Duration.ofSeconds(30), 12);

        assertThat(request.portfolioId()).isEqualTo("PORT-1");
        assertThat(request.userMessage()).isEqualTo("anything to do today?");
        assertThat(request.maxThinkingTime()).isEqualTo(Duration.ofSeconds(30));
        assertThat(request.maxToolCalls()).isEqualTo(12);
    }

    @Test
    void shouldReject_whenPortfolioIdNull() {
        assertThatThrownBy(() -> new AnalystRequest(
                null, "msg", Duration.ofSeconds(30), 12))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("portfolioId");
    }

    @Test
    void shouldReject_whenPortfolioIdBlank() {
        assertThatThrownBy(() -> new AnalystRequest(
                "   ", "msg", Duration.ofSeconds(30), 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("portfolioId");
    }

    @Test
    void shouldReject_whenUserMessageNull() {
        assertThatThrownBy(() -> new AnalystRequest(
                "PORT-1", null, Duration.ofSeconds(30), 12))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userMessage");
    }

    @Test
    void shouldReject_whenUserMessageBlank() {
        assertThatThrownBy(() -> new AnalystRequest(
                "PORT-1", "", Duration.ofSeconds(30), 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userMessage");
    }

    @Test
    void shouldReject_whenMaxThinkingTimeNull() {
        assertThatThrownBy(() -> new AnalystRequest(
                "PORT-1", "msg", null, 12))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("maxThinkingTime");
    }

    @Test
    void shouldReject_whenMaxThinkingTimeZero() {
        assertThatThrownBy(() -> new AnalystRequest(
                "PORT-1", "msg", Duration.ZERO, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxThinkingTime");
    }

    @Test
    void shouldReject_whenMaxThinkingTimeNegative() {
        assertThatThrownBy(() -> new AnalystRequest(
                "PORT-1", "msg", Duration.ofSeconds(-1), 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxThinkingTime");
    }

    @Test
    void shouldReject_whenMaxToolCallsNegative() {
        assertThatThrownBy(() -> new AnalystRequest(
                "PORT-1", "msg", Duration.ofSeconds(30), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxToolCalls");
    }

    @Test
    void shouldAllow_maxToolCallsZero() {
        AnalystRequest request = new AnalystRequest(
                "PORT-1", "msg", Duration.ofSeconds(30), 0);

        assertThat(request.maxToolCalls()).isZero();
    }
}
