package com.risksentinel.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lookup + invocation surface for {@link Tool}s. Constructed once at server
 * startup from a fixed list — no runtime registration, no auto-discovery.
 *
 * <p>Threading: the registry is immutable after construction. The underlying
 * tools are responsible for their own thread-safety.
 */
public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> tools) {
        Objects.requireNonNull(tools, "tools");
        LinkedHashMap<String, Tool> byName = new LinkedHashMap<>(tools.size());
        for (Tool t : tools) {
            Objects.requireNonNull(t, "tool");
            if (byName.put(t.name(), t) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + t.name());
            }
        }
        this.tools = Collections.unmodifiableMap(byName);
    }

    /** Snapshot of all registered tools, in registration order. */
    public List<Tool> list() {
        return new ArrayList<>(tools.values());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * Invoke {@code toolName} with the provided JSON. Performs shallow schema
     * validation (presence + type for top-level required fields) before
     * dispatching. Exceptions thrown by the tool handler are caught and
     * surfaced as {@code ToolResult.error(...)} so the transport layer never
     * sees an unchecked throw.
     */
    public ToolResult invoke(String toolName, JsonNode input) {
        Objects.requireNonNull(toolName, "toolName");
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + toolName);
        }
        if (input == null || input.isNull()) {
            input = MAPPER.createObjectNode();
        }
        String validationError = validateShallow(tool.inputSchema(), input);
        if (validationError != null) {
            return ToolResult.error(validationError);
        }
        try {
            ToolResult result = tool.invoke(input);
            return result != null ? result : ToolResult.error("Tool returned null");
        } catch (RuntimeException e) {
            log.warn("Tool {} threw: {}", toolName, e.toString());
            return ToolResult.error("Tool " + toolName + " failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static String validateShallow(Map<String, Object> schema, JsonNode input) {
        Object required = schema.get("required");
        if (!(required instanceof List<?> reqList)) {
            return null;
        }
        Object properties = schema.get("properties");
        Map<String, Object> propsMap = (properties instanceof Map<?, ?>)
                ? (Map<String, Object>) properties
                : Map.of();

        for (Object o : reqList) {
            if (!(o instanceof String field)) continue;
            JsonNode value = input.get(field);
            if (value == null || value.isNull()) {
                return "Missing required field: " + field;
            }
            Object propDef = propsMap.get(field);
            if (propDef instanceof Map<?, ?> def) {
                Object expectedType = def.get("type");
                if (expectedType instanceof String type) {
                    String mismatch = checkType(field, value, type);
                    if (mismatch != null) {
                        return mismatch;
                    }
                }
            }
        }
        return null;
    }

    private static String checkType(String field, JsonNode value, String expected) {
        boolean matches = switch (expected) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true;
        };
        return matches ? null : "Field " + field + " must be a " + expected;
    }
}
