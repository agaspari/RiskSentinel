package com.risksentinel.mcp;

import java.util.Objects;

/**
 * Outcome of a {@link Tool#invoke(tools.jackson.databind.JsonNode)} call.
 *
 * <p>{@code content} is always a JSON string ready to hand back to the transport
 * layer. {@code isError == true} signals a problem to the caller (the agent or
 * the MCP transport) without throwing — exceptions in tool handlers are caught
 * by {@link ToolRegistry} and surfaced here.
 */
public record ToolResult(boolean isError, String content) {

    public ToolResult {
        Objects.requireNonNull(content, "content");
    }

    public static ToolResult ok(String json) {
        return new ToolResult(false, json);
    }

    public static ToolResult error(String message) {
        return new ToolResult(true, "{\"error\":\"" + escape(message) + "\"}");
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
