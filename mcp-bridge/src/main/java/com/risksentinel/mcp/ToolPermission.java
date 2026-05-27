package com.risksentinel.mcp;

/**
 * Permission level a {@link Tool} requires of its caller.
 *
 * <p>Levels are intentionally coarse:
 * <ul>
 *   <li>{@code READ_ONLY} — observation only; cannot mutate state
 *       (snapshots, positions, instruments, audit reads).</li>
 *   <li>{@code WRITE} — proposes trades; subject to the gateway, but does
 *       not alter operator controls.</li>
 *   <li>{@code ADMIN} — operator-only controls (kill switch).</li>
 * </ul>
 *
 * <p>A finer model (per-tool rate limits, per-portfolio scoping) is a
 * deliberate non-goal for this phase. See {@code ToolAuthorizer}.
 */
public enum ToolPermission {
    READ_ONLY,
    WRITE,
    ADMIN
}
