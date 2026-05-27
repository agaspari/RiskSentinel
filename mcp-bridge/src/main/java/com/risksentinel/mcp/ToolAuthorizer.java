package com.risksentinel.mcp;

import com.risksentinel.core.audit.Caller;
import com.risksentinel.core.audit.Caller.CallerKind;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps a {@link Caller}'s kind to the set of {@link ToolPermission}s it may
 * exercise. Consulted by {@link ToolRegistry} immediately before dispatch.
 *
 * <p>The policy is a fixed per-{@link CallerKind} grant table — no per-tool
 * rules, no rate limits, no per-portfolio scoping. Those belong in later
 * phases.
 */
public final class ToolAuthorizer {

    private final Map<CallerKind, Set<ToolPermission>> grants;

    private ToolAuthorizer(Map<CallerKind, Set<ToolPermission>> grants) {
        EnumMap<CallerKind, Set<ToolPermission>> copy = new EnumMap<>(CallerKind.class);
        for (Map.Entry<CallerKind, Set<ToolPermission>> e : grants.entrySet()) {
            copy.put(e.getKey(), EnumSet.copyOf(e.getValue()));
        }
        this.grants = copy;
    }

    /**
     * Default policy:
     * <ul>
     *   <li>{@code AGENT} — {@code READ_ONLY}, {@code WRITE}</li>
     *   <li>{@code OPERATOR} — all three</li>
     *   <li>{@code SYSTEM} — all three (in-process tests, gateway internals)</li>
     * </ul>
     */
    public static ToolAuthorizer defaults() {
        EnumMap<CallerKind, Set<ToolPermission>> grants = new EnumMap<>(CallerKind.class);
        grants.put(CallerKind.AGENT, EnumSet.of(ToolPermission.READ_ONLY, ToolPermission.WRITE));
        grants.put(CallerKind.OPERATOR, EnumSet.allOf(ToolPermission.class));
        grants.put(CallerKind.SYSTEM, EnumSet.allOf(ToolPermission.class));
        return new ToolAuthorizer(grants);
    }

    public boolean allows(Caller caller, ToolPermission required) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(required, "required");
        Set<ToolPermission> granted = grants.get(caller.kind());
        return granted != null && granted.contains(required);
    }

    /** Grants currently held by {@code kind}, as an unmodifiable snapshot. */
    public Set<ToolPermission> grantsFor(CallerKind kind) {
        Objects.requireNonNull(kind, "kind");
        Set<ToolPermission> granted = grants.get(kind);
        return granted == null ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(granted));
    }
}
