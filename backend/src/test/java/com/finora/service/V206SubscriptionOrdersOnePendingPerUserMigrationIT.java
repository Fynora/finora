package com.finora.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V206 does a data mutation (superseding duplicate PENDING subscription_orders rows) immediately
 * before creating a UNIQUE index, mirroring V74/V79's exact pattern -- see those migrations' own
 * comments for why: a pre-existing duplicate would fail {@code CREATE UNIQUE INDEX} at startup,
 * which on a Flyway migration means the deployment does not come up. Unlike V74's "in practice
 * this finds nothing", the race V206 closes (BillingCheckoutService.checkout()'s
 * resumableOrderOrGuard()) has been live since V154 shipped subscription billing V1, so this is
 * not something to bet a deploy on without a real test proving the cleanup step actually runs
 * before the index does.
 *
 * <p>Builds the schema V206 assumes it will meet (migrated through V205, rows seeded by hand),
 * then runs V206 forward and checks what actually happens -- same structure as
 * {@code V74ImportJobIdempotencyMigrationIT}, for the same reason: Spring Boot's Flyway
 * autoconfiguration migrates straight to latest with no hook to pause mid-history and seed data,
 * so this drives Flyway directly instead of going through {@code AbstractIntegrationTest}.
 */
class V206SubscriptionOrdersOnePendingPerUserMigrationIT {

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
        migrateTo("205");
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private void migrateToLatestThroughV206() {
        migrateTo("206");
    }

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

    /** V99 already seeds FREE/PLUS/PREMIUM by the time this migrates through V205, so {@code code}
     *  must be unique per call -- a short random suffix keeps it under plans.code's VARCHAR(20)
     *  while avoiding "plans_code_key" collisions across tests. */
    private UUID seedPlan() throws SQLException {
        UUID id = UUID.randomUUID();
        String code = "T" + id.toString().substring(0, 8);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO plans (id, code, name) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, code);
            ps.setString(3, code);
            ps.executeUpdate();
        }
        return id;
    }

    /** Inserts one subscription_orders row at the schema shape V205 leaves behind (pre-V206: no
     *  unique index on user_id/status). */
    private UUID seedOrder(UUID userId, UUID planId, String status, Instant createdAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO subscription_orders
                    (id, user_id, plan_id, billing_cycle, razorpay_subscription_id, status, amount, created_at)
                VALUES (?, ?, ?, 'MONTHLY', ?, ?, ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setObject(3, planId);
            ps.setString(4, "sub_" + id);
            ps.setString(5, status);
            ps.setBigDecimal(6, new BigDecimal("799.00"));
            ps.setTimestamp(7, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
        return id;
    }

    private String statusOf(UUID orderId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status FROM subscription_orders WHERE id = ?")) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private long countUniqueIndexEntries() throws SQLException {
        try (java.sql.Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_subscription_orders_one_pending_per_user'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------------------------------

    @Test
    void twoPendingOrdersSameUser_olderIsAbandonedNewerSurvives() throws SQLException {
        UUID user = seedUser();
        UUID plan = seedPlan();
        Instant now = Instant.now();
        UUID older = seedOrder(user, plan, "PENDING", now.minusSeconds(60));
        UUID newer = seedOrder(user, plan, "PENDING", now);

        assertThatCode(this::migrateToLatestThroughV206).doesNotThrowAnyException();

        assertThat(statusOf(older)).isEqualTo("ABANDONED");
        assertThat(statusOf(newer)).isEqualTo("PENDING");
        assertThat(countUniqueIndexEntries()).isEqualTo(1);
    }

    @Test
    void threeWayCollisionSameUser_onlyNewestSurvivesPending() throws SQLException {
        UUID user = seedUser();
        UUID plan = seedPlan();
        Instant now = Instant.now();
        UUID oldest = seedOrder(user, plan, "PENDING", now.minusSeconds(120));
        UUID middle = seedOrder(user, plan, "PENDING", now.minusSeconds(60));
        UUID newest = seedOrder(user, plan, "PENDING", now);

        assertThatCode(this::migrateToLatestThroughV206).doesNotThrowAnyException();

        assertThat(statusOf(oldest)).isEqualTo("ABANDONED");
        assertThat(statusOf(middle)).isEqualTo("ABANDONED");
        assertThat(statusOf(newest)).isEqualTo("PENDING");
    }

    @Test
    void differentUsersEachWithTheirOwnPendingOrder_neitherTouched() throws SQLException {
        UUID userA = seedUser();
        UUID userB = seedUser();
        UUID plan = seedPlan();
        Instant now = Instant.now();
        UUID orderA = seedOrder(userA, plan, "PENDING", now);
        UUID orderB = seedOrder(userB, plan, "PENDING", now);

        assertThatCode(this::migrateToLatestThroughV206).doesNotThrowAnyException();

        assertThat(statusOf(orderA)).isEqualTo("PENDING");
        assertThat(statusOf(orderB)).isEqualTo("PENDING");
    }

    @Test
    void onePendingOneCompletedSameUser_completedRowUntouchedNoCollision() throws SQLException {
        // A COMPLETED row does not collide with a live PENDING one -- an activated earlier
        // subscription coexisting with a later in-flight upgrade checkout is legitimate and must
        // not be touched by the cleanup or blocked by the resulting index.
        UUID user = seedUser();
        UUID plan = seedPlan();
        Instant now = Instant.now();
        UUID completed = seedOrder(user, plan, "COMPLETED", now.minusSeconds(3600));
        UUID pending = seedOrder(user, plan, "PENDING", now);

        assertThatCode(this::migrateToLatestThroughV206).doesNotThrowAnyException();

        assertThat(statusOf(completed)).isEqualTo("COMPLETED");
        assertThat(statusOf(pending)).isEqualTo("PENDING");
    }

    @Test
    void afterMigration_indexEnforcesUniquenessGoingForward() throws SQLException {
        UUID user = seedUser();
        UUID plan = seedPlan();
        migrateToLatestThroughV206();

        // Two fresh PENDING orders for the same user, inserted directly (bypassing
        // resumableOrderOrGuard entirely), should now be rejected by the database itself -- this
        // is the guarantee V206 exists to provide, not just a migration-time cleanup.
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO subscription_orders
                    (id, user_id, plan_id, billing_cycle, razorpay_subscription_id, status, amount, created_at)
                VALUES (?, ?, ?, 'MONTHLY', ?, 'PENDING', ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setObject(3, plan);
            ps.setString(4, "sub_first");
            ps.setBigDecimal(5, new BigDecimal("799.00"));
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setObject(3, plan);
            ps.setString(4, "sub_second");
            ps.setBigDecimal(5, new BigDecimal("799.00"));
            ps.setTimestamp(6, Timestamp.from(Instant.now()));

            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void noPreexistingDuplicates_migrationSucceedsCleanlyLikeTheEmptySchemaCase() throws SQLException {
        // Baseline: the shape every prior run of this migration has actually exercised (empty
        // schema, no seeded rows). Included here so a regression that breaks the ordinary case
        // shows up alongside the adversarial ones instead of only in a separate suite.
        assertThatCode(this::migrateToLatestThroughV206).doesNotThrowAnyException();
        assertThat(countUniqueIndexEntries()).isEqualTo(1);
    }
}
