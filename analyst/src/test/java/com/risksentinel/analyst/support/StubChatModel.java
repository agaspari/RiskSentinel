package com.risksentinel.analyst.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Deterministic {@link ChatModel} that returns scripted responses from a FIFO
 * queue. Tests build one of these to exercise specific shapes — a final text
 * response, a tool call sequence, an exhaustion loop — without needing
 * network or paid tokens.
 */
public final class StubChatModel implements ChatModel {

    private final Deque<Function<ChatRequest, ChatResponse>> script = new ArrayDeque<>();
    private final List<ChatRequest> received = new ArrayList<>();

    public static StubChatModel empty() {
        return new StubChatModel();
    }

    /** Enqueue an {@link AiMessage} to be returned on the next {@code chat(...)} call. */
    public StubChatModel enqueue(AiMessage message) {
        script.add(req -> ChatResponse.builder().aiMessage(message).build());
        return this;
    }

    /** Enqueue a {@link RuntimeException} to be thrown on the next call. */
    public StubChatModel enqueueThrow(RuntimeException toThrow) {
        script.add(req -> { throw toThrow; });
        return this;
    }

    /** Enqueue an unbounded "always reply with this" script, useful for max-iteration tests. */
    public StubChatModel enqueueRepeating(AiMessage message) {
        script.add(new Function<>() {
            @Override public ChatResponse apply(ChatRequest req) {
                // Re-add ourselves so the next chat() finds another response.
                script.addFirst(this);
                return ChatResponse.builder().aiMessage(message).build();
            }
        });
        return this;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        received.add(request);
        Function<ChatRequest, ChatResponse> next = script.poll();
        if (next == null) {
            throw new IllegalStateException(
                    "StubChatModel script exhausted; no response scripted for call #" + received.size());
        }
        return next.apply(request);
    }

    public int callCount() {
        return received.size();
    }

    public List<ChatRequest> received() {
        return List.copyOf(received);
    }
}
