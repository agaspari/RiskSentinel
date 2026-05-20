package com.risksentinel.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helpers for building JSON Schema {@code Map}s without dragging in a
 * schema library. The schemas we produce are intentionally shallow — deeper
 * validation is the tool handler's job, where the gateway's existing checks
 * are the real safety net.
 */
public final class ToolSchemas {

    private ToolSchemas() {}

    public static Map<String, Object> object(
            List<String> required,
            Map<String, Map<String, Object>> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.copyOf(required));
        schema.put("properties", Map.copyOf(properties));
        return schema;
    }

    public static Map<String, Object> field(String type, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("description", description);
        return field;
    }
}
