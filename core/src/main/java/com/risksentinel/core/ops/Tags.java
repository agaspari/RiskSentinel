package com.risksentinel.core.ops;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Ordered immutable bag of {@code (key, value)} string pairs used to label
 * metrics. Insertion order is preserved so Prometheus-style label rendering
 * is deterministic and test-comparable.
 */
public final class Tags {

    private static final Tags EMPTY = new Tags(Map.of());

    private final Map<String, String> tags;

    private Tags(Map<String, String> tags) {
        this.tags = tags;
    }

    public static Tags empty() {
        return EMPTY;
    }

    /** Interleaved key/value varargs: {@code of("portfolioId","p-1","symbol","AAPL")}. */
    public static Tags of(String... keyValuePairs) {
        Objects.requireNonNull(keyValuePairs, "keyValuePairs");
        if ((keyValuePairs.length & 1) == 1) {
            throw new IllegalArgumentException("keyValuePairs must have an even length");
        }
        if (keyValuePairs.length == 0) {
            return EMPTY;
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>(keyValuePairs.length);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String k = Objects.requireNonNull(keyValuePairs[i], "tag key");
            String v = Objects.requireNonNull(keyValuePairs[i + 1], "tag value");
            map.put(k, v);
        }
        return new Tags(Collections.unmodifiableMap(map));
    }

    public Map<String, String> asMap() {
        return tags;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Tags other && this.tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        return tags.hashCode();
    }

    @Override
    public String toString() {
        return "Tags" + tags;
    }
}
