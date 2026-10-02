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

/** V246 seeds "Interest & Cashback" for every existing user, the way V123 seeded its category. */
class V246InterestAndCashbackCategoryMigrationIT {

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
        migrateTo("245");
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
    void everyExistingUserGetsTheCategory_renderedLikeANewRegistrationsCopy() throws SQLException {
        UUID first = seedUser();
        UUID second = seedUser();

        migrateTo("246");

        for (UUID user : new UUID[]{first, second}) {
            assertThat(string("SELECT is_system || '/' || icon || '/' || color FROM categories "
                    + "WHERE user_id = ? AND name = 'Interest & Cashback'", user))
                    .isEqualTo("true/arrow-down-circle/teal");
        }
    }

    @Test
    void aCategoryTheUserAlreadyMadeIsLeftAlone_ratherThanTheMigrationAborting() throws SQLException {
        UUID user = seedUser();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO categories (id, user_id, name, is_system, icon, color) "
                        + "VALUES (?, ?, 'interest & cashback', false, 'tag', 'gray')")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.executeUpdate();
        }

        // Without the NOT EXISTS guard this violates uq_categories_user_name_ci, and a failed
        // migration does not degrade -- the backend does not boot.
        assertThatCode(() -> migrateTo("246")).doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ? "
                + "AND lower(name) = 'interest & cashback'", user)).isEqualTo(1);
        assertThat(string("SELECT name || '/' || is_system FROM categories WHERE user_id = ? "
                + "AND lower(name) = 'interest & cashback'", user)).isEqualTo("interest & cashback/false");
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

    private long count(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
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
