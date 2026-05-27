package com.risksentinel.core.audit;

import com.risksentinel.core.domain.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-migration tests for {@link SqliteAuditLog}. Creates a v1 database
 * by hand (without the caller columns) and confirms that opening it with
 * the current build adds the columns, bumps the version, preserves data,
 * and reads pre-migration rows without throwing.
 */
class SqliteAuditLogSchemaMigrationTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    private static final String V1_CREATE_TABLE =
            "CREATE TABLE decisions ("
                    + "proposal_id        TEXT    PRIMARY KEY,"
                    + "portfolio_id       TEXT    NOT NULL,"
                    + "symbol             TEXT    NOT NULL,"
                    + "side               TEXT    NOT NULL,"
                    + "quantity           INTEGER NOT NULL,"
                    + "limit_price        REAL    NOT NULL,"
                    + "snapshot_id        TEXT    NOT NULL,"
                    + "decision_type      TEXT    NOT NULL,"
                    + "first_reject_code  TEXT,"
                    + "reasons_json       TEXT    NOT NULL,"
                    + "decided_at_micros  INTEGER NOT NULL"
                    + ")";

    private static void createV1Database(Path dbFile) throws SQLException {
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute(V1_CREATE_TABLE);
            st.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY)");
            st.execute("INSERT INTO schema_version (version) VALUES (1)");
        }
    }

    private static void insertV1Row(Path dbFile, String proposalId, String portfolio) throws SQLException {
        long micros = T0.getEpochSecond() * 1_000_000L + T0.getNano() / 1_000L;
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO decisions VALUES ("
                    + "'" + proposalId + "', '" + portfolio + "', 'AAPL', 'BUY',"
                    + " 100, 150.0, 'snap-x', 'ACCEPT', NULL, '[]', " + micros + ")");
        }
    }

    @Test
    void shouldMigrateV1Database_bumpVersion_andAddCallerColumns(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("audit.db");
        createV1Database(db);

        try (AuditLog log = new SqliteAuditLog(db)) {
            // Opening migrates in-place.
            assertThat(log.count()).isZero();
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(AuditSchema.CURRENT_VERSION);
            }
            // PRAGMA table_info — confirm both new columns exist.
            List<String> cols = new java.util.ArrayList<>();
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(decisions)")) {
                while (rs.next()) cols.add(rs.getString("name"));
            }
            assertThat(cols).contains("caller_kind", "caller_id");
        }
    }

    @Test
    void shouldPreservePreMigrationRows_andReadThemWithNullCallerFields(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("audit.db");
        createV1Database(db);
        insertV1Row(db, "v1-row", "port-1");

        try (AuditLog log = new SqliteAuditLog(db)) {
            List<DecisionRecord> rows = log.findByPortfolio("port-1", 10);
            assertThat(rows).hasSize(1);
            DecisionRecord r = rows.get(0);
            assertThat(r.proposalId()).isEqualTo("v1-row");
            // Caller fields are null on records written pre-migration.
            assertThat(r.callerKind()).isNull();
            assertThat(r.callerId()).isNull();
        }
    }

    @Test
    void shouldWriteV2Record_withCaller_andReadItBack(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("audit.db");
        createV1Database(db);

        try (AuditLog log = new SqliteAuditLog(db)) {
            DecisionRecord r = new DecisionRecord(
                    "p-new", "port-1", "AAPL", Side.BUY,
                    100L, 150.0, "snap-x",
                    DecisionType.ACCEPT, null, "[]", T0,
                    Caller.CallerKind.OPERATOR, "alice");
            log.record(r);

            DecisionRecord back = log.findByProposalId("p-new").orElseThrow();
            assertThat(back.callerKind()).isEqualTo(Caller.CallerKind.OPERATOR);
            assertThat(back.callerId()).isEqualTo("alice");
        }
    }

    @Test
    void shouldBeIdempotent_whenOpenedTwiceAfterMigration(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("audit.db");
        createV1Database(db);

        try (AuditLog log = new SqliteAuditLog(db)) {
            assertThat(log.count()).isZero();
        }
        try (AuditLog log2 = new SqliteAuditLog(db)) {
            // Second open is a no-op migration: version is already current.
            assertThat(log2.count()).isZero();
        }
    }
}
