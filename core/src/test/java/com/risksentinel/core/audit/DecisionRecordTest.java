package com.risksentinel.core.audit;

import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.GatewayDecision;
import com.risksentinel.core.gateway.RejectCode;
import com.risksentinel.core.gateway.RejectReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionRecordTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    private TradeProposal proposal() {
        return new TradeProposal(
                "p-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, 150.0, "rationale",
                0.9, "snap-x", T0);
    }

    @Test
    void shouldRejectAcceptWithRejectCode() {
        assertThatThrownBy(() -> new DecisionRecord(
                "p-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, "snap-x",
                DecisionType.ACCEPT, "FAT_FINGER_QUANTITY", "[]", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectRejectWithoutRejectCode() {
        assertThatThrownBy(() -> new DecisionRecord(
                "p-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, "snap-x",
                DecisionType.REJECT, null, "[]", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptValidAcceptRecord() {
        assertThatCode(() -> new DecisionRecord(
                "p-1", "port-1", "AAPL", Side.BUY,
                100L, 150.0, "snap-x",
                DecisionType.ACCEPT, null, "[]", T0))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBlankProposalId() {
        assertThatThrownBy(() -> new DecisionRecord(
                "  ", "port-1", "AAPL", Side.BUY,
                100L, 150.0, "snap-x",
                DecisionType.ACCEPT, null, "[]", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroQuantity() {
        assertThatThrownBy(() -> new DecisionRecord(
                "p-1", "port-1", "AAPL", Side.BUY,
                0L, 150.0, "snap-x",
                DecisionType.ACCEPT, null, "[]", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldBuildFromAcceptDecision() {
        GatewayDecision decision = new GatewayDecision.Accept("p-1", "snap-x", T0);
        DecisionRecord r = DecisionRecords.fromDecision(decision, proposal());

        assertThat(r.type()).isEqualTo(DecisionType.ACCEPT);
        assertThat(r.firstRejectCode()).isNull();
        assertThat(r.reasonsJson()).isEqualTo("[]");
        assertThat(r.decidedAt()).isEqualTo(T0);
    }

    @Test
    void shouldBuildFromRejectDecision_withFirstCodeAndJsonReasons() {
        List<RejectReason> reasons = List.of(
                new RejectReason("FatFingerCheck", RejectCode.FAT_FINGER_QUANTITY, "qty too high"),
                new RejectReason("PositionSizeCheck", RejectCode.POSITION_SIZE_EXCEEDED, "post-trade overflow"));
        GatewayDecision decision = new GatewayDecision.Reject("p-1", reasons, T0);

        DecisionRecord r = DecisionRecords.fromDecision(decision, proposal());

        assertThat(r.type()).isEqualTo(DecisionType.REJECT);
        assertThat(r.firstRejectCode()).isEqualTo("FAT_FINGER_QUANTITY");
        assertThat(r.reasonsJson()).contains("FAT_FINGER_QUANTITY");
        assertThat(r.reasonsJson()).contains("POSITION_SIZE_EXCEEDED");
        assertThat(r.reasonsJson()).contains("qty too high");
        assertThat(r.reasonsJson()).startsWith("[").endsWith("]");
    }

    @Test
    void shouldEscapeJsonSpecialCharactersInReasonMessage() {
        List<RejectReason> reasons = List.of(
                new RejectReason("Check", RejectCode.UNKNOWN_SYMBOL,
                        "Symbol \"ZZZZ\" has \\ backslash and \n newline"));
        String json = DecisionRecords.encodeReasons(reasons);

        assertThat(json).contains("\\\"ZZZZ\\\"");
        assertThat(json).contains("\\\\ backslash");
        assertThat(json).contains("\\n newline");
    }

    @Test
    void shouldEncodeEmptyReasonsList() {
        assertThat(DecisionRecords.encodeReasons(List.of())).isEqualTo("[]");
    }
}
