package com.risksentinel.core.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MdcScopeTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldRejectOddNumberOfArguments() {
        assertThatThrownBy(() -> MdcScope.of("k1", "v1", "k2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullKey() {
        assertThatThrownBy(() -> MdcScope.of(null, "v"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSetValuesInsideScope_andClearAfter() {
        assertThat(MDC.get("portfolioId")).isNull();

        try (MdcScope ignored = MdcScope.of("portfolioId", "p-1", "proposalId", "x-1")) {
            assertThat(MDC.get("portfolioId")).isEqualTo("p-1");
            assertThat(MDC.get("proposalId")).isEqualTo("x-1");
        }

        assertThat(MDC.get("portfolioId")).isNull();
        assertThat(MDC.get("proposalId")).isNull();
    }

    @Test
    void shouldRestorePreviousValues_onClose() {
        MDC.put("portfolioId", "outer");

        try (MdcScope ignored = MdcScope.of("portfolioId", "inner")) {
            assertThat(MDC.get("portfolioId")).isEqualTo("inner");
        }

        assertThat(MDC.get("portfolioId")).isEqualTo("outer");
    }

    @Test
    void shouldRestoreEvenWhenBodyThrows() {
        MDC.put("portfolioId", "outer");

        try {
            try (MdcScope ignored = MdcScope.of("portfolioId", "inner")) {
                throw new RuntimeException("boom");
            }
        } catch (RuntimeException ignored) {
        }

        assertThat(MDC.get("portfolioId")).isEqualTo("outer");
    }

    @Test
    void shouldClearKey_whenNullValueProvided() {
        MDC.put("portfolioId", "outer");

        try (MdcScope ignored = MdcScope.of("portfolioId", null)) {
            assertThat(MDC.get("portfolioId")).isNull();
        }

        assertThat(MDC.get("portfolioId")).isEqualTo("outer");
    }

    @Test
    void shouldNestCorrectly() {
        try (MdcScope outer = MdcScope.of("portfolioId", "outer")) {
            assertThat(MDC.get("portfolioId")).isEqualTo("outer");
            try (MdcScope inner = MdcScope.of("portfolioId", "inner")) {
                assertThat(MDC.get("portfolioId")).isEqualTo("inner");
            }
            assertThat(MDC.get("portfolioId")).isEqualTo("outer");
        }
        assertThat(MDC.get("portfolioId")).isNull();
    }
}
