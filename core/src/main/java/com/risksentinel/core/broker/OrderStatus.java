package com.risksentinel.core.broker;

/**
 * Lifecycle states for an {@link Order} held inside the broker. Phase 4 only
 * exercises {@link #NEW}, {@link #FILLED}, and {@link #REJECTED}; {@link #PARTIAL}
 * and {@link #CANCELLED} are reserved for later phases.
 */
public enum OrderStatus {
    NEW,
    PARTIAL,
    FILLED,
    REJECTED,
    CANCELLED
}
