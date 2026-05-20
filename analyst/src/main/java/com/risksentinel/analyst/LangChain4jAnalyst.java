package com.risksentinel.analyst;

import com.risksentinel.analyst.AnalystResponse.Outcome;
import com.risksentinel.analyst.AnalystResponse.ToolCall;
import com.risksentinel.analyst.tools.LangChain4jToolBridge;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * LangChain4j-backed {@link AnalystAgent} implementation.
 *
 * <p>One {@link #handle(AnalystRequest)} call runs a bounded tool-call loop:
 * the model proposes tool calls, the {@link LangChain4jToolBridge} dispatches
 * them through the Phase 7 {@code ToolRegistry}, and the results feed back in
 * until the model emits a final text response or one of the budgets is hit.
 *
 * <p>Three budgets, all hard caps:
 * <ul>
 *   <li>{@link AnalystRequest#maxToolCalls()} — caller's per-turn cap on tool
 *   invocations (clamped to {@link AnalystConfig#maxTotalToolCalls()}).</li>
 *   <li>{@link AnalystConfig#maxIterations()} — model invocations per turn.</li>
 *   <li>{@link AnalystRequest#maxThinkingTime()} — wall-clock budget.</li>
 * </ul>
 *
 * <p>Crashes from the model or bridge are caught and surfaced as
 * {@link Outcome#ERROR}; a runaway agent always terminates as
 * {@link Outcome#BUDGET_EXHAUSTED}, never as a thrown exception.
 */
public final class LangChain4jAnalyst implements AnalystAgent {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jAnalyst.class);
    private static final String DEFAULT_PROMPT_RESOURCE = "/prompts/analyst-system.md";

    private final ChatModel model;
    private final LangChain4jToolBridge bridge;
    private final AnalystConfig config;
    private final Clock clock;
    private final String systemPrompt;

    public LangChain4jAnalyst(
            ChatModel model,
            LangChain4jToolBridge bridge,
            AnalystConfig config,
            Clock clock) {
        this.model = Objects.requireNonNull(model, "model");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.systemPrompt = (config.systemPromptOverride() != null)
                ? config.systemPromptOverride()
                : loadDefaultPrompt();
    }

    @Override
    public AnalystResponse handle(AnalystRequest request) {
        Objects.requireNonNull(request, "request");

        Instant deadline = clock.instant().plus(request.maxThinkingTime());
        int toolBudget = Math.min(request.maxToolCalls(), config.maxTotalToolCalls());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(buildUserMessage(request)));

        List<ToolSpecification> specs = bridge.specifications();
        List<ToolCall> trail = new ArrayList<>();
        int callsMade = 0;

        for (int iter = 0; iter < config.maxIterations(); iter++) {
            if (clock.instant().isAfter(deadline)) {
                return new AnalystResponse(
                        "Stopped: wall-clock budget exhausted.", trail, Outcome.BUDGET_EXHAUSTED);
            }

            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specs)
                    .build();

            ChatResponse response;
            try {
                response = model.chat(chatRequest);
            } catch (RuntimeException e) {
                log.warn("Model call failed: {}", e.toString());
                return new AnalystResponse(
                        "The analyst could not complete this request due to a model error.",
                        trail, Outcome.ERROR);
            }

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                String text = aiMessage.text() != null ? aiMessage.text() : "";
                return new AnalystResponse(text, trail, Outcome.ANSWERED);
            }

            for (ToolExecutionRequest toolReq : aiMessage.toolExecutionRequests()) {
                if (callsMade >= toolBudget) {
                    return new AnalystResponse(
                            "Stopped: tool-call budget exhausted at " + callsMade + " calls.",
                            trail, Outcome.BUDGET_EXHAUSTED);
                }
                ToolExecutionResultMessage resultMsg;
                try {
                    resultMsg = bridge.execute(toolReq);
                } catch (RuntimeException e) {
                    log.warn("Tool bridge dispatch failed for {}: {}", toolReq.name(), e.toString());
                    return new AnalystResponse(
                            "The analyst could not complete this request due to a tool dispatch error.",
                            trail, Outcome.ERROR);
                }
                messages.add(resultMsg);
                trail.add(new ToolCall(
                        toolReq.name(),
                        toolReq.arguments() != null ? toolReq.arguments() : "",
                        resultMsg.text() != null ? resultMsg.text() : ""));
                callsMade++;
            }
        }

        return new AnalystResponse(
                "Stopped: maximum iterations reached without a final answer.",
                trail, Outcome.BUDGET_EXHAUSTED);
    }

    private static String buildUserMessage(AnalystRequest request) {
        return "Portfolio: " + request.portfolioId() + "\n\n" + request.userMessage();
    }

    private static String loadDefaultPrompt() {
        try (InputStream in = LangChain4jAnalyst.class.getResourceAsStream(DEFAULT_PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Missing classpath resource: " + DEFAULT_PROMPT_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load " + DEFAULT_PROMPT_RESOURCE, e);
        }
    }
}
