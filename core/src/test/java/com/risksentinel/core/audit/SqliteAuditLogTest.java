package com.risksentinel.core.audit;

import com.risksentinel.core.domain.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteAuditLogTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    private DecisionRecord accept(String pid, String portfolio, Instant at) {
        return new DecisionRecord(
                pid, portfolio, "AAPL", Side.BUY,
                100L, 150.0, "snap-x",
                DecisionType.ACCEPT, null, "[]", at);
    }

    private DecisionRecord reject(String pid, String portfolio, String code, Instant at) {
        return new DecisionRecord(
                pid, portfolio, "AAPL", Side.BUY,
                100L, 150.0, "snap-x",
                DecisionType.REJECT, code,
                "[{\"checkName\":\"X\",\"code\":\"" + code + "\",\"message\":\"m\"}]",
                at);
    }

    @Test
    void shouldApplySchema_onOpen(@TempDir Path dir) throws SQLException {
        Path db = dir.resolve("audit.db");
        try (AuditLog log = new SqliteAuditLog(db)) {
            // schema is applied during construction
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            List<String> tables = new java.util.ArrayList<>();
            while (rs.next()) tables.add(rs.getString(1));
            assertThat(tables).contains("decisions", "schema_version");
        }
    }

    @Test
    void shouldRoundTripAcceptRecord(@TempDir Path dir) {
        Path db = dir.resolve("audit.db");
        try (AuditLog log = new SqliteAuditLog(db)) {
            DecisionRecord original = accept("p-1", "port-1", T0);
            log.record(original);

            Optional<DecisionRecord> loaded = log.findByProposalId("p-1");
            assertThat(loaded).isPresent();
            assertThat(loaded.get()).isEqualTo(original);
        }
    }

    @Test
    void shouldRoundTripRejectRecord(@TempDir Path dir) {
        Path db = dir.resolve("audit.db");
        try (AuditLog log = new SqliteAuditLog(db)) {
            DecisionRecord original = reject("p-2", "port-1", "FAT_FINGER_QUANTITY", T0);
            log.record(original);

            DecisionRecord loaded = log.findByProposalId("p-2").orElseThrow();
            assertThat(loaded.type()).isEqualTo(DecisionType.REJECT);
            assertThat(loaded.firstRejectCode()).isEqualTo("FAT_FINGER_QUANTITY");
            assertThat(loaded.reasonsJson()).contains("FAT_FINGER_QUANTITY");
        }
    }

    @Test
    void shouldPersistAcrossReopen(@TempDir Path dir) {
        Path db = dir.resolve("audit.db");
        try (AuditLog log = new SqliteAuditLog(db)) {
            log.record(accept("p-1", "port-1", T0));
            log.record(reject("p-2", "port-1", "KILL_SWITCH_ENGAGED", T0.plusSeconds(1)));
        }
        try (AuditLog reopened = new SqliteAuditLog(db)) {
            assertThat(reopened.count()).isEqualTo(2L);
            assertThat(reopened.findByProposalId("p-1")).isPresent();
            assertThat(reopened.findByProposalId("p-2")).isPresent();
        }
    }

    @Test
    void shouldReturnEmpty_forUnknownProposalId(@TempDir Path dir) {
        Path db = dir.resolve("audit.db");
        try (AuditLog log = new SqliteAuditLog(db)) {
            assertThat(log.findByProposalId("nope")).isEmpty();
        }
    }

    @Test
    void shouldReturnPortfolioRecordsInDescendingTimeOrder(@TempDir Path dir) {
        Path db = dir.resolve("audit.db");
        try (AuditLog log = new SqliteAuditLog(db)) {
            log.record(accept("p-1", "port-1", T0));
            log.record(accept("p-2", "port-1", T0.plusSeconds(1)));
            log.record(accept("p-3", "port-1", T0.plusSeconds(2)));
            log.record(accept("p-other", "port-2", T0.plusSeconds(3)));

            List<DecisionRecord> portfolio1 = log.findByPortfolio("port-1", 10);
            assertThat(portfolio1).extracting(DecisionRecord::proposalId)
                    .containsExactly("p-3", "p-2", "p-1");

            List<DecisionRecord> bounded = log.findByPortfolio("port-1", 2);
            assertThat(bounded).hasSize(2);
            assertThat(bounded.get(0).proposalId()).isEqualTo("p-3");
        }
    }

    @Test
    void shouldRejectDuplicateProposalId_loud(@TempDir Path dir) {
        Path db = dir.resolve("audit.db");
        try (AuditLog log = new SqliteAuditLog(db)) {
            log.record(accept("p-1", "port-1", T0));
            assertThatThrownBy(() -> log.record(accept("p-1", "port-1", T0.plusSeconds(1))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void shouldFailLoud_onSchemaVersionMismatch(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("audit.db");
        try (AuditLog log = new SqliteAuditLog(db)) {}

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("UPDATE schema_version SET version = 99");
        }

        assertThatThrownBy(() -> new SqliteAuditLog(db))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema version 99");
    }

    @Test
    void shouldPreserveMicrosecondPrecision_onTimestamps(@TempDir Path dir) {
        Path db = dir.resolve("audit.db");
        Instant precise = Instant.ofEpochSecond(1747655252L, 123_456_000); // 123.456 ms after second
        try (AuditLog log = new SqliteAuditLog(db)) {
            log.record(accept("p-1", "port-1", precise));
            DecisionRecord back = log.findByProposalId("p-1").orElseThrow();
            assertThat(back.decidedAt()).isEqualTo(precise);
        }
    }

    @Test
    void shouldCreateDbFile_ifMissing(@TempDir Path dir) {
        Path db = dir.resolve("subdir-not-created").resolve("audit.db");
        // SQLite will fail to open if parent dir doesn't exist; create it first.
        try {
            Files.createDirectories(db.getParent());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try (AuditLog log = new SqliteAuditLog(db)) {
            assertThat(Files.exists(db)).isTrue();
        }
    }
}
