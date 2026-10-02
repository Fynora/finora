package com.finora.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V248 adds payee rules' amount bounds and the one-answer-per-payee index (recurring-payment question). */
class V248CategoryRulePayeeMigrationIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finora_migration_test")
            .withUsername("finora")
            .withPassword("finora");

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    private Connection connection;

    @BeforeEach
    void freshSchema() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
        migrateTo("247");
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterEach
    void close() throws SQLException {
        connection.close();
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    @Test
    void addsNullableAmountBounds_andAnExistingRuleKeepsNoBounds() throws SQLException {
        UUID user = seedUser();
        insertRuleBeforeV248(user, "DESCRIPTION", "sample shop");

        migrateTo("248");

        assertThat(string("SELECT coalesce(amount_min::text, 'null') || '/' || coalesce(amount_max::text, 'null') "
                + "FROM category_rules WHERE user_id = ?", user)).isEqualTo("null/null");
    }

    @Test
    void onlyOnePayeeRulePerUserAndPayee_ignoringCase() throws SQLException {
        migrateTo("248");
        UUID user = seedUser();
        insertRule(user, "PAYEE", "sample landlord", "8000.00", "12000.00");

        assertThatThrownBy(() -> insertRule(user, "PAYEE", "SAMPLE LANDLORD", "1.00", "2.00"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uq_category_rules_user_payee");
        // Other fields with the same text, and another user's payee rule, are not limited by it.
        assertThatCode(() -> insertRule(user, "DESCRIPTION", "sample landlord", null, null)).doesNotThrowAnyException();
        UUID other = seedUser();
        assertThatCode(() -> insertRule(other, "PAYEE", "sample landlord", null, null)).doesNotThrowAnyException();
    }

    @Test
    void boundsKeepTheSameScaleAsTransactionAmounts() throws SQLException {
        migrateTo("248");
        UUID user = seedUser();
        insertRule(user, "PAYEE", "sample landlord", "7999.995", "12001");

        assertThat(string("SELECT amount_min::text || '/' || amount_max::text FROM category_rules WHERE user_id = ?", user))
                .isEqualTo("8000.00/12001.00");
    }

    private void insertRuleBeforeV248(UUID user, String field, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO category_rules (id, user_id, scope, field, operator, comparison_value, action_type, action_value) "
                        + "VALUES (?, ?, 'USER', ?, 'EQUALS', ?, 'ASSIGN_CATEGORY', 'Rent')")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, field);
            ps.setString(4, value);
            ps.executeUpdate();
        }
    }

    private void insertRule(UUID user, String field, String value, String min, String max) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO category_rules (id, user_id, scope, field, operator, comparison_value, action_type, "
                        + "action_value, amount_min, amount_max) VALUES (?, ?, 'USER', ?, 'EQUALS', ?, 'ASSIGN_CATEGORY', 'Rent', ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, field);
            ps.setString(4, value);
            ps.setBigDecimal(5, min == null ? null : new java.math.BigDecimal(min));
            ps.setBigDecimal(6, max == null ? null : new java.math.BigDecimal(max));
            ps.executeUpdate();
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private UUID seedUser() throws SQLException {
        UUID userId = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, userId + "@example.test");
            ps.setString(3, "hash");
            ps.setString(4, "Test User");
            ps.executeUpdate();
        }
        return userId;
    }

    private String string(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
