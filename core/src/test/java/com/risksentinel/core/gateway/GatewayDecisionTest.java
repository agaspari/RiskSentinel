package com.risksentinel.core.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GatewayDecisionTest {

    private static final Instant T = Instant.parse("2026-05-18T12:00:00Z");

    @Nested
    @DisplayName("Reject construction")
    class RejectConstruction {

        @Test
        void shouldRejectConstruction_whenReasonsEmpty() {
            assertThatThrownBy(() -> new GatewayDecision.Reject("p-1", List.of(), T))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one reason");
        }

        @Test
        void shouldRejectConstruction_whenReasonsNull() {
            assertThatThrownBy(() -> new GatewayDecision.Reject("p-1", null, T))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldDeepCopyReasonsList() {
            List<RejectReason> mutable = new ArrayList<>();
            mutable.add(new RejectReason("c", RejectCode.KILL_SWITCH_ENGAGED, "halted"));

            GatewayDecision.Reject reject = new GatewayDecision.Reject("p-1", mutable, T);
            mutable.clear();

            assertThat(reject.reasons()).hasSize(1);
            assertThatThrownBy(() -> reject.reasons().add(
                    new RejectReason("c2", RejectCode.STALE_SNAPSHOT, "x")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Accept construction")
    class AcceptConstruction {

        @Test
        void shouldAccept_whenAllFieldsProvided() {
            assertThatCode(() -> new GatewayDecision.Accept("p-1", "snap-1", T))
                    .doesNotThrowAnyException();
        }

        @Test
        void shouldRejectConstruction_whenProposalIdNull() {
            assertThatThrownBy(() -> new GatewayDecision.Accept(null, "snap-1", T))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("RejectReason")
    class RejectReasonValidation {

        @Test
        void shouldReject_whenCheckNameBlank() {
            assertThatThrownBy(() -> new RejectReason("", RejectCode.STALE_SNAPSHOT, "m"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldReject_whenCodeNull() {
            assertThatThrownBy(() -> new RejectReason("c", null, "m"))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /** Smoke test: pattern-match exhaustiveness should compile without a default branch. */
    @Test
    void sealedTypeShouldExhaustInSwitch() {
        GatewayDecision decision = new GatewayDecision.Accept("p-1", "snap-1", T);
        String summary = switch (decision) {
            case GatewayDecision.Accept a -> "accept:" + a.snapshotId();
            case GatewayDecision.Reject r -> "reject:" + r.reasons().size();
        };
        assertThat(summary).isEqualTo("accept:snap-1");
    }
}
