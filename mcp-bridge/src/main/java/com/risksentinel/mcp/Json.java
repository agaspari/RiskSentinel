package com.risksentinel.mcp;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared, pre-configured {@link ObjectMapper} for tools. Java time types
 * (Instant, etc.) are serialized as ISO-8601 strings — that is the default in
 * Jackson 3 (java.time support is built into core; no module registration
 * required and the legacy {@code WRITE_DATES_AS_TIMESTAMPS} feature is gone).
 */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    public static String writeOrError(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize " + value.getClass().getSimpleName(), e);
        }
    }
}
