package com.risksentinel.core.gateway;

/**
 * Stable, enumerated reasons a {@link GatewayDecision.Reject} can carry.
 *
 * <p>Codes are part of the gateway's public contract — downstream agents
 * and audit logs depend on their identity, so existing values must not be
 * renumbered or removed without a deliberate migration.
 */
public enum RejectCode {
    KILL_SWITCH_ENGAGED,
    DUPLICATE_PROPOSAL,
    POSITION_SIZE_EXCEEDED,
    GROSS_EXPOSURE_EXCEEDED,
    NET_EXPOSURE_EXCEEDED,
    CONCENTRATION_EXCEEDED,
    SECTOR_CAP_EXCEEDED,
    FAT_FINGER_PRICE_DEVIATION,
    FAT_FINGER_QUANTITY,
    UNKNOWN_SYMBOL,
    STALE_SNAPSHOT
}
