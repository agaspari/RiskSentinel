package com.risksentinel.analyst;

import com.risksentinel.analyst.AnalystResponse.Outcome;
import com.risksentinel.analyst.support.StubChatModel;
import com.risksentinel.analyst.tools.LangChain4jToolBridge;
import com.risksentinel.mcp.Tool;
import com.risksentinel.mcp.ToolRegistry;
import com.risksentinel.mcp.ToolResult;
import com.risksentinel.mcp.ToolSchemas;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jAnalystTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static AnalystRequest defaultRequest() {
        return new AnalystRequest(
                "PORT-1", "anything to do today?", Duration.ofSeconds(30), 12);
    }

    private static AnalystConfig defaultConfig() {
        return new AnalystConfig(12, 6, Duration.ofSeconds(30), "test-system-prompt");
    }

    private static LangChain4jToolBridge bridgeWith(Tool... tools) {
        return new LangChain4jToolBridge(new ToolRegistry(List.of(tools)), JSON);
    }

    private static Tool stubTool(String name, java.util.function.Function<JsonNode, ToolResult> handler) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public Map<String, Object> inputSchema() {
                return ToolSchemas.object(List.of(), Map.of());
            }
            @Override public ToolResult invoke(JsonNode input) { return handler.apply(input); }
        };
    }

    private static ToolExecutionRequest call(String id, String name, String args) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(args).build();
    }

    @Test
    void shouldAnswerWithoutTools() {
        StubChatModel model = StubChatModel.empty()
                .enqueue(AiMessage.from("All quiet."));
        LangChain4jAnalyst agent = new LangChain4jAnalyst(
                model, bridgeWith(), defaultConfig(), Clock.systemUTC());

        AnalystResponse response = agent.handle(defaultRequest());

        assertThat(response.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(response.summary()).isEqualTo("All quiet.");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    void shouldExecuteToolCall_andReturnAnswer() {
        Tool snapshot = stubTool("get_snapshot",
                in -> ToolResult.ok("{\"snapshotId\":\"S1\"}"));

        StubChatModel model = StubChatModel.empty()
                .enqueue(AiMessage.from(List.of(call("c1", "get_snapshot", "{}"))))
                .enqueue(AiMessage.from("Snapshot S1 looks healthy."));

        LangChain4jAnalyst agent = new LangChain4jAnalyst(
                model, bridgeWith(snapshot), defaultConfig(), Clock.systemUTC());

        AnalystResponse response = agent.handle(defaultRequest());

        assertThat(response.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(response.summary()).isEqualTo("Snapshot S1 looks healthy.");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("get_snapshot");
        assertThat(response.toolCalls().get(0).outputJson()).contains("S1");
    }

    @Test
    void shouldStopAtMaxToolCalls() {
        AtomicInteger invocations = new AtomicInteger();
        Tool counter = stubTool("counter",
                in -> {
                    invocations.incrementAndGet();
                    return ToolResult.ok("{\"n\":" + invocations.get() + "}");
                });

        // Model keeps requesting tool calls every turn.
        StubChatModel model = StubChatModel.empty()
                .enqueueRepeating(AiMessage.from(List.of(call("c", "counter", "{}"))));

        AnalystRequest request = new AnalystRequest(
                "PORT-1", "loop", Duration.ofSeconds(30), 3);
        AnalystConfig config = new AnalystConfig(12, 50, Duration.ofSeconds(30), "p");

        LangChain4jAnalyst agent = new LangChain4jAnalyst(
                model, bridgeWith(counter), config, Clock.systemUTC());

        AnalystResponse response = agent.handle(request);

        assertThat(response.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
        assertThat(response.toolCalls()).hasSize(3);
        assertThat(invocations.get()).isEqualTo(3);
    }

    @Test
    void shouldHonorConfigCap_whenRequestAsksForMore() {
        Tool counter = stubTool("counter", in -> ToolResult.ok("{}"));

        StubChatModel model = StubChatModel.empty()
                .enqueueRepeating(AiMessage.from(List.of(call("c", "counter", "{}"))));

        AnalystRequest request = new AnalystRequest(
                "PORT-1", "loop", Duration.ofSeconds(30), 999);
        AnalystConfig config = new AnalystConfig(2, 50, Duration.ofSeconds(30), "p");

        LangChain4jAnalyst agent = new LangChain4jAnalyst(
                model, bridgeWith(counter), config, Clock.systemUTC());

        AnalystResponse response = agent.handle(request);

        assertThat(response.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
        assertThat(response.toolCalls()).hasSize(2);
    }

    @Test
    void shouldStopAtMaxIterations() {
        Tool tool = stubTool("noop", in -> ToolResult.ok("{}"));

        // Each turn the model returns a tool call. With large tool budget and
        // small maxIterations, we should exit on iteration count.
        StubChatModel model = StubChatModel.empty()
                .enqueueRepeating(AiMessage.from(List.of(call("c", "noop", "{}"))));

        AnalystRequest request = new AnalystRequest(
                "PORT-1", "loop", Duration.ofSeconds(30), 1000);
        AnalystConfig config = new AnalystConfig(1000, 3, Duration.ofSeconds(30), "p");

        LangChain4jAnalyst agent = new LangChain4jAnalyst(
                model, bridgeWith(tool), config, Clock.systemUTC());

        AnalystResponse response = agent.handle(request);

        assertThat(response.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
        assertThat(model.callCount()).isEqualTo(3);
    }

    @Test
    void shouldRecordToolCallsInOrder() {
        Tool a = stubTool("alpha", in -> ToolResult.ok("{\"a\":1}"));
        Tool b = stubTool("beta", in -> ToolResult.ok("{\"b\":2}"));
        Tool c = stubTool("gamma", in -> ToolResult.ok("{\"c\":3}"));

        StubChatModel model = StubChatModel.empty()
                .enqueue(AiMessage.from(List.of(
                        call("1", "alpha", "{}"),
                        call("2", "beta", "{}"),
                        call("3", "gamma", "{}"))))
                .enqueue(AiMessage.from("done"));

        LangChain4jAnalyst agent = new LangChain4jAnalyst(
                model, bridgeWith(a, b, c), defaultConfig(), Clock.systemUTC());

        AnalystResponse response = agent.handle(defaultRequest());

        assertThat(response.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(response.toolCalls()).extracting(AnalystResponse.ToolCall::name)
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void shouldSurfaceModelException_asErrorOutcome() {
        StubChatModel model = StubChatModel.empty()
                .enqueueThrow(new RuntimeException("upstream down"));

        LangChain4jAnalyst agent = new LangChain4jAnalyst(
                model, bridgeWith(), defaultConfig(), Clock.systemUTC());

        AnalystResponse response = agent.handle(defaultRequest());

        assertThat(response.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(response.summary()).doesNotContain("upstream down");
        assertThat(response.summary()).containsIgnoringCase("model error");
    }

    @Test
    void shouldStopOnDeadline_whenWallClockExpiresBeforeNextModelCall() {
        // Clock advances 20s on each instant() — first call still within
        // 30s budget, second call is past it.
        Clock fastClock = new Clock() {
            private Instant base = Instant.parse("2026-05-20T00:00:00Z");
            private int step = 0;
            @Override public Instant instant() {
                Instant now = base.plusSeconds(step * 20L);
                step++;
                return now;
            }
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
        };

        Tool tool = stubTool("noop", in -> ToolResult.ok("{}"));
        StubChatModel model = StubChatModel.empty()
                .enqueueRepeating(AiMessage.from(List.of(call("c", "noop", "{}"))));

        AnalystRequest request = new AnalystRequest(
                "PORT-1", "loop", Duration.ofSeconds(30), 100);
        LangChain4jAnalyst agent = new LangChain4jAnalyst(
                model, bridgeWith(tool), defaultConfig(), fastClock);

        AnalystResponse response = agent.handle(request);

        assertThat(response.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
        assertThat(response.summary()).containsIgnoringCase("wall-clock");
    }

    @Test
    void shouldLoadDefaultPrompt_whenNoOverride() {
        // Override is null → loads from /prompts/analyst-system.md classpath resource.
        AnalystConfig config = new AnalystConfig(12, 6, Duration.ofSeconds(30), null);
        StubChatModel model = StubChatModel.empty()
                .enqueue(AiMessage.from("ok"));

        LangChain4jAnalyst agent = new LangChain4jAnalyst(
                model, bridgeWith(), config, Clock.systemUTC());

        AnalystResponse response = agent.handle(defaultRequest());

        assertThat(response.outcome()).isEqualTo(Outcome.ANSWERED);
        // The model's first ChatRequest should contain a SystemMessage with the resource prompt.
        String firstSystem = model.received().get(0).messages().get(0).toString();
        assertThat(firstSystem).contains("RiskSentinel");
    }
}
