package com.risksentinel.core.audit;

/** SQL DDL for the audit-log database. Schema version is incremented on breaking changes. */
public final class AuditSchema {

    public static final int CURRENT_VERSION = 1;

    public static final String CREATE_DECISIONS_TABLE =
            "CREATE TABLE IF NOT EXISTS decisions ("
                    + "  proposal_id        TEXT    PRIMARY KEY,"
                    + "  portfolio_id       TEXT    NOT NULL,"
                    + "  symbol             TEXT    NOT NULL,"
                    + "  side               TEXT    NOT NULL,"
                    + "  quantity           INTEGER NOT NULL,"
                    + "  limit_price        REAL    NOT NULL,"
                    + "  snapshot_id        TEXT    NOT NULL,"
                    + "  decision_type      TEXT    NOT NULL,"
                    + "  first_reject_code  TEXT,"
                    + "  reasons_json       TEXT    NOT NULL,"
                    + "  decided_at_micros  INTEGER NOT NULL"
                    + ")";

    public static final String CREATE_DECIDED_AT_INDEX =
            "CREATE INDEX IF NOT EXISTS decisions_decided_at_idx "
                    + "ON decisions(decided_at_micros)";

    public static final String CREATE_PORTFOLIO_INDEX =
            "CREATE INDEX IF NOT EXISTS decisions_portfolio_idx "
                    + "ON decisions(portfolio_id)";

    public static final String CREATE_SCHEMA_VERSION_TABLE =
            "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY)";

    private AuditSchema() {}
}
