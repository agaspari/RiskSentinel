package com.risksentinel.core.ops;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * AutoCloseable that sets a set of MDC keys on entry and restores the previous
 * values on close. Designed for use with try-with-resources so MDC cannot leak
 * across logical scopes even if the body throws.
 *
 * <pre>{@code
 * try (MdcScope s = MdcScope.of("portfolioId", p.portfolioId(),
 *                                "proposalId",  p.proposalId())) {
 *     // ... gateway work ...
 * }
 * }</pre>
 */
public final class MdcScope implements AutoCloseable {

    private final Map<String, String> previousValues;

    private MdcScope(Map<String, String> previousValues) {
        this.previousValues = previousValues;
    }

    /** Interleaved key/value varargs. Null values clear the key inside the scope. */
    public static MdcScope of(String... keyValuePairs) {
        Objects.requireNonNull(keyValuePairs, "keyValuePairs");
        if ((keyValuePairs.length & 1) == 1) {
            throw new IllegalArgumentException("keyValuePairs must have an even length");
        }

        LinkedHashMap<String, String> previous = new LinkedHashMap<>(keyValuePairs.length);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String key = Objects.requireNonNull(keyValuePairs[i], "MDC key");
            String value = keyValuePairs[i + 1];
            previous.put(key, MDC.get(key));
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
        return new MdcScope(previous);
    }

    @Override
    public void close() {
        for (Map.Entry<String, String> e : previousValues.entrySet()) {
            if (e.getValue() == null) {
                MDC.remove(e.getKey());
            } else {
                MDC.put(e.getKey(), e.getValue());
            }
        }
    }
}
