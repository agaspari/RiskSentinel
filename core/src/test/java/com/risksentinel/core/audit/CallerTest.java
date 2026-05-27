package com.risksentinel.core.audit;

import com.risksentinel.core.audit.Caller.CallerKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallerTest {

    @Test
    void shouldRejectNullKind() {
        assertThatThrownBy(() -> new Caller(null, "x"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullId() {
        assertThatThrownBy(() -> new Caller(CallerKind.AGENT, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectBlankId() {
        assertThatThrownBy(() -> new Caller(CallerKind.AGENT, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldBuildAgent_viaFactory() {
        Caller c = Caller.agent("analyst-1");
        assertThat(c.kind()).isEqualTo(CallerKind.AGENT);
        assertThat(c.id()).isEqualTo("analyst-1");
    }

    @Test
    void shouldBuildOperator_viaFactory() {
        Caller c = Caller.operator("alice");
        assertThat(c.kind()).isEqualTo(CallerKind.OPERATOR);
        assertThat(c.id()).isEqualTo("alice");
    }

    @Test
    void shouldBuildSystem_viaFactory() {
        Caller c = Caller.system();
        assertThat(c.kind()).isEqualTo(CallerKind.SYSTEM);
        assertThat(c.id()).isEqualTo("system");
    }
}
