package com.risksentinel.core.audit;

import com.risksentinel.core.domain.Side;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Synchronous SQLite-backed audit log. Single {@link Connection} guarded by a
 * private monitor — SQLite serializes writes anyway, so explicit per-method
 * synchronization is the simplest correct approach.
 *
 * <p>On {@link #SqliteAuditLog(Path)} construction the database file is opened
 * (created if absent), schema DDL is applied inside a transaction, and the
 * persisted {@code schema_version} is verified against
 * {@link AuditSchema#CURRENT_VERSION}.
 */
public final class SqliteAuditLog implements AuditLog {

    private final Object lock = new Object();
    private final Connection conn;
    private final PreparedStatement insertStmt;
    private final PreparedStatement findByIdStmt;
    private final PreparedStatement findByPortfolioStmt;
    private final PreparedStatement countStmt;

    public SqliteAuditLog(Path dbFile) {
        Objects.requireNonNull(dbFile, "dbFile");
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        Connection openConn = null;
        try {
            openConn = DriverManager.getConnection(url);
            openConn.setAutoCommit(true);
            this.conn = openConn;
            applySchema();
            verifySchemaVersion();
            this.insertStmt = conn.prepareStatement(
                    "INSERT INTO decisions ("
                            + "proposal_id, portfolio_id, symbol, side, quantity, limit_price,"
                            + " snapshot_id, decision_type, first_reject_code, reasons_json, decided_at_micros)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            this.findByIdStmt = conn.prepareStatement(
                    "SELECT proposal_id, portfolio_id, symbol, side, quantity, limit_price,"
                            + " snapshot_id, decision_type, first_reject_code, reasons_json, decided_at_micros"
                            + " FROM decisions WHERE proposal_id = ?");
            this.findByPortfolioStmt = conn.prepareStatement(
                    "SELECT proposal_id, portfolio_id, symbol, side, quantity, limit_price,"
                            + " snapshot_id, decision_type, first_reject_code, reasons_json, decided_at_micros"
                            + " FROM decisions WHERE portfolio_id = ?"
                            + " ORDER BY decided_at_micros DESC LIMIT ?");
            this.countStmt = conn.prepareStatement("SELECT COUNT(*) FROM decisions");
            openConn = null; // ownership transferred to instance; do not close in catch
        } catch (SQLException e) {
            closeQuietly(openConn);
            throw new IllegalStateException("Failed to open SQLite audit log at " + dbFile, e);
        } catch (RuntimeException e) {
            closeQuietly(openConn);
            throw e;
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }

    private void applySchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);
            st.execute(AuditSchema.CREATE_DECISIONS_TABLE);
            st.execute(AuditSchema.CREATE_DECIDED_AT_INDEX);
            st.execute(AuditSchema.CREATE_PORTFOLIO_INDEX);
            st.execute(AuditSchema.CREATE_SCHEMA_VERSION_TABLE);

            try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
                if (!rs.next()) {
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO schema_version (version) VALUES (?)")) {
                        ins.setInt(1, AuditSchema.CURRENT_VERSION);
                        ins.executeUpdate();
                    }
                }
            }
            conn.commit();
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void verifySchemaVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("schema_version row missing");
            }
            int found = rs.getInt(1);
            if (found != AuditSchema.CURRENT_VERSION) {
                throw new IllegalStateException(
                        "Audit DB schema version " + found
                                + " does not match expected " + AuditSchema.CURRENT_VERSION);
            }
        }
    }

    @Override
    public void record(DecisionRecord record) {
        Objects.requireNonNull(record, "record");
        synchronized (lock) {
            try {
                insertStmt.setString(1, record.proposalId());
                insertStmt.setString(2, record.portfolioId());
                insertStmt.setString(3, record.symbol());
                insertStmt.setString(4, record.side().name());
                insertStmt.setLong(5, record.quantity());
                insertStmt.setDouble(6, record.limitPrice());
                insertStmt.setString(7, record.snapshotId());
                insertStmt.setString(8, record.type().name());
                if (record.firstRejectCode() == null) {
                    insertStmt.setNull(9, java.sql.Types.VARCHAR);
                } else {
                    insertStmt.setString(9, record.firstRejectCode());
                }
                insertStmt.setString(10, record.reasonsJson());
                insertStmt.setLong(11, toMicros(record.decidedAt()));
                insertStmt.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "Failed to write decision for proposalId=" + record.proposalId(), e);
            }
        }
    }

    @Override
    public Optional<DecisionRecord> findByProposalId(String proposalId) {
        Objects.requireNonNull(proposalId, "proposalId");
        synchronized (lock) {
            try {
                findByIdStmt.setString(1, proposalId);
                try (ResultSet rs = findByIdStmt.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(mapRow(rs));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query decision " + proposalId, e);
            }
        }
    }

    @Override
    public List<DecisionRecord> findByPortfolio(String portfolioId, int limit) {
        Objects.requireNonNull(portfolioId, "portfolioId");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        synchronized (lock) {
            try {
                findByPortfolioStmt.setString(1, portfolioId);
                findByPortfolioStmt.setInt(2, limit);
                List<DecisionRecord> out = new ArrayList<>();
                try (ResultSet rs = findByPortfolioStmt.executeQuery()) {
                    while (rs.next()) out.add(mapRow(rs));
                }
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query portfolio " + portfolioId, e);
            }
        }
    }

    @Override
    public long count() {
        synchronized (lock) {
            try (ResultSet rs = countStmt.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to count decisions", e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try { insertStmt.close(); } catch (SQLException ignored) {}
            try { findByIdStmt.close(); } catch (SQLException ignored) {}
            try { findByPortfolioStmt.close(); } catch (SQLException ignored) {}
            try { countStmt.close(); } catch (SQLException ignored) {}
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private static DecisionRecord mapRow(ResultSet rs) throws SQLException {
        return new DecisionRecord(
                rs.getString("proposal_id"),
                rs.getString("portfolio_id"),
                rs.getString("symbol"),
                Side.valueOf(rs.getString("side")),
                rs.getLong("quantity"),
                rs.getDouble("limit_price"),
                rs.getString("snapshot_id"),
                DecisionType.valueOf(rs.getString("decision_type")),
                rs.getString("first_reject_code"),
                rs.getString("reasons_json"),
                fromMicros(rs.getLong("decided_at_micros")));
    }

    private static long toMicros(Instant i) {
        return Math.multiplyExact(i.getEpochSecond(), 1_000_000L) + i.getNano() / 1_000L;
    }

    private static Instant fromMicros(long micros) {
        long seconds = micros / 1_000_000L;
        int nanos = (int) ((micros % 1_000_000L) * 1_000L);
        return Instant.ofEpochSecond(seconds, nanos);
    }
}
