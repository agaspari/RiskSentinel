package com.risksentinel.analyst;

import com.risksentinel.analyst.AnalystResponse.Outcome;
import com.risksentinel.analyst.AnalystResponse.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalystResponseTest {

    @Test
    void shouldConstruct_whenAllFieldsValid() {
        ToolCall call = new ToolCall("get_snapshot", "{}", "{\"id\":\"S1\"}");
        AnalystResponse response = new AnalystResponse(
                "All good.", List.of(call), Outcome.ANSWERED);

        assertThat(response.summary()).isEqualTo("All good.");
        assertThat(response.toolCalls()).containsExactly(call);
        assertThat(response.outcome()).isEqualTo(Outcome.ANSWERED);
    }

    @Test
    void shouldRejectNullSummary() {
        assertThatThrownBy(() -> new AnalystResponse(
                null, List.of(), Outcome.ANSWERED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void shouldRejectNullToolCalls() {
        assertThatThrownBy(() -> new AnalystResponse(
                "summary", null, Outcome.ANSWERED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("toolCalls");
    }

    @Test
    void shouldRejectNullOutcome() {
        assertThatThrownBy(() -> new AnalystResponse(
                "summary", List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    void shouldDefensivelyCopyToolCalls() {
        ToolCall call = new ToolCall("get_snapshot", "{}", "{}");
        List<ToolCall> mutable = new ArrayList<>();
        mutable.add(call);

        AnalystResponse response = new AnalystResponse(
                "summary", mutable, Outcome.ANSWERED);

        mutable.clear();
        assertThat(response.toolCalls()).hasSize(1);
        assertThatThrownBy(() -> response.toolCalls().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldExposeAllOutcomeValues() {
        assertThat(Outcome.values()).containsExactly(
                Outcome.ANSWERED,
                Outcome.REFUSED,
                Outcome.BUDGET_EXHAUSTED,
                Outcome.ERROR);
    }

    @Test
    void toolCallShouldRejectNullName() {
        assertThatThrownBy(() -> new ToolCall(null, "{}", "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void toolCallShouldRejectBlankName() {
        assertThatThrownBy(() -> new ToolCall("  ", "{}", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void toolCallShouldRejectNullInputJson() {
        assertThatThrownBy(() -> new ToolCall("get_snapshot", null, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("inputJson");
    }

    @Test
    void toolCallShouldRejectNullOutputJson() {
        assertThatThrownBy(() -> new ToolCall("get_snapshot", "{}", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outputJson");
    }

    @Test
    void toolCallShouldAllowEmptyJsonStrings() {
        ToolCall call = new ToolCall("get_snapshot", "", "");

        assertThat(call.inputJson()).isEmpty();
        assertThat(call.outputJson()).isEmpty();
    }
}
