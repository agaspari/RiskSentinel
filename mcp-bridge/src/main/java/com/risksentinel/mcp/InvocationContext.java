package com.risksentinel.mcp;

import com.risksentinel.core.audit.Caller;

import java.time.Instant;
import java.util.Objects;

/**
 * Per-call metadata threaded into {@link Tool#invoke(tools.jackson.databind.JsonNode, InvocationContext)}.
 *
 * <p>Today this is the {@link Caller} that invoked the tool plus the wall-clock
 * time the request was received. Designed as a record so future per-call
 * fields (request id, deadline, span context) can be added without churning
 * every tool's signature again.
 */
public record InvocationContext(Caller caller, Instant receivedAt) {

    public InvocationContext {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /** Convenience for tests and internal call sites that don't care about timestamp precision. */
    public static InvocationContext forCaller(Caller caller) {
        return new InvocationContext(caller, Instant.now());
    }

    /** Convenience for tool tests with no real caller. */
    public static InvocationContext forSystem() {
        return forCaller(Caller.system());
    }
}
