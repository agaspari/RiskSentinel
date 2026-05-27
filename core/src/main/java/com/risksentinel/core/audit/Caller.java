package com.risksentinel.core.audit;

import java.util.Objects;

/**
 * Identity of the actor responsible for a gateway decision or tool invocation.
 *
 * <p>Lives in {@code core/audit} because it is a domain-attribution concept,
 * not a transport one: the audit log persists it, the gateway records it,
 * and transports merely construct it at the edge where bytes enter the JVM.
 *
 * <p>{@code id} is whatever string makes sense for the caller kind — the
 * analyst bridge passes a constant (e.g. {@code "analyst"}), the MCP server
 * passes a connection id, the system path uses {@code "system"}.
 */
public record Caller(CallerKind kind, String id) {

    public enum CallerKind { AGENT, OPERATOR, SYSTEM }

    public Caller {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
    }

    public static Caller agent(String id) {
        return new Caller(CallerKind.AGENT, id);
    }

    public static Caller operator(String id) {
        return new Caller(CallerKind.OPERATOR, id);
    }

    public static Caller system() {
        return new Caller(CallerKind.SYSTEM, "system");
    }
}
