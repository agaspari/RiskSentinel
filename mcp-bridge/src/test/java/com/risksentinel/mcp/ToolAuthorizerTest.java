package com.risksentinel.mcp;

import com.risksentinel.core.audit.Caller;
import com.risksentinel.core.audit.Caller.CallerKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolAuthorizerTest {

    private final ToolAuthorizer authorizer = ToolAuthorizer.defaults();

    @Test
    void shouldGrantReadAndWrite_butNotAdmin_toAgent() {
        Caller agent = Caller.agent("a");
        assertThat(authorizer.allows(agent, ToolPermission.READ_ONLY)).isTrue();
        assertThat(authorizer.allows(agent, ToolPermission.WRITE)).isTrue();
        assertThat(authorizer.allows(agent, ToolPermission.ADMIN)).isFalse();
    }

    @Test
    void shouldGrantAllPermissions_toOperator() {
        Caller operator = Caller.operator("alice");
        for (ToolPermission perm : ToolPermission.values()) {
            assertThat(authorizer.allows(operator, perm)).isTrue();
        }
    }

    @Test
    void shouldGrantAllPermissions_toSystem() {
        Caller system = Caller.system();
        for (ToolPermission perm : ToolPermission.values()) {
            assertThat(authorizer.allows(system, perm)).isTrue();
        }
    }

    @Test
    void shouldRejectNullCaller() {
        assertThatThrownBy(() -> authorizer.allows(null, ToolPermission.READ_ONLY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPermission() {
        assertThatThrownBy(() -> authorizer.allows(Caller.system(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldExposeGrantsForKind() {
        assertThat(authorizer.grantsFor(CallerKind.AGENT))
                .containsExactlyInAnyOrder(ToolPermission.READ_ONLY, ToolPermission.WRITE);
        assertThat(authorizer.grantsFor(CallerKind.OPERATOR))
                .containsExactlyInAnyOrder(
                        ToolPermission.READ_ONLY, ToolPermission.WRITE, ToolPermission.ADMIN);
    }

    @Test
    void shouldReturnImmutableGrantsSnapshot() {
        var grants = authorizer.grantsFor(CallerKind.AGENT);
        // Snapshot is independent of the authorizer's internal state.
        assertThatThrownBy(() -> grants.add(ToolPermission.ADMIN))
                .isInstanceOfAny(UnsupportedOperationException.class, IllegalStateException.class);
    }
}
