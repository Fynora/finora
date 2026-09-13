package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AiAuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cost-governance SUM/COALESCE queries against real Postgres, not a mock -- {@code
 * FynCostGovernanceServiceTest} only ever exercised these against a Mockito stub, which proves the
 * service's threshold logic but nothing about whether the JPQL itself is correct against the real
 * schema. Cost governance existing to fail closed on a real spend that was never actually summed
 * correctly would be a silent no-op, not a safety net.
 */
class AiAuditLogRepositoryIT extends AbstractIntegrationTest {

    @Autowired private AiAuditLogRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ai_audit_log isn't one of AbstractIntegrationTest's own cleaned-up shared queues, and
    // sumCostSince (org-wide, unscoped by user) would otherwise pick up another test method's rows
    // in this same class if they land within the same "since" window -- the exact shared-table
    // leakage class of bug this suite has hit before (see AbstractIntegrationTest's own
    // emptyTheSharedWorkQueues doc). Deleting outright is safe: nothing else in the app reads or
    // writes this table yet, and no phase has shipped that would leave real rows to lose.
    @BeforeEach
    void emptyAiAuditLog() {
        jdbcTemplate.update("DELETE FROM ai_audit_log");
    }

    private AiAuditLog row(UUID userId, BigDecimal cost, Instant createdAt) {
        AiAuditLog log = new AiAuditLog();
        log.setUserId(userId);
        log.setModel("claude-haiku-4-5-20251001");
        log.setPromptVersion("v1");
        log.setTokensIn(100);
        log.setTokensOut(50);
        log.setCost(cost);
        log.setLatencyMs(500);
        AiAuditLog saved = repository.save(log);
        // created_at has a Java-side default of Instant.now() and is not updatable -- overwrite it
        // directly so "since" boundary tests can place rows precisely in the past. JdbcTemplate
        // can't infer a SQL type for a raw Instant (PSQLException at runtime, caught by actually
        // running this) -- java.sql.Timestamp is the type it knows how to bind.
        jdbcTemplate.update("UPDATE ai_audit_log SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    @Test
    void sumIsZeroNotNullWhenNoRowsMatch() {
        BigDecimal sum = repository.sumCostByUserSince(UUID.randomUUID(), Instant.now().minusSeconds(3600));

        assertThat(sum).isNotNull();
        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sumsOnlyTheGivenUsersRows() {
        UUID user = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        Instant now = Instant.now();
        row(user, new BigDecimal("0.01"), now);
        row(user, new BigDecimal("0.02"), now);
        row(otherUser, new BigDecimal("5.00"), now);

        BigDecimal sum = repository.sumCostByUserSince(user, now.minusSeconds(60));

        assertThat(sum).isEqualByComparingTo(new BigDecimal("0.03"));
    }

    @Test
    void excludesRowsOlderThanTheSinceBoundary() {
        UUID user = UUID.randomUUID();
        Instant now = Instant.now();
        row(user, new BigDecimal("0.01"), now.minusSeconds(120)); // outside the 60s window
        row(user, new BigDecimal("0.02"), now); // inside

        BigDecimal sum = repository.sumCostByUserSince(user, now.minusSeconds(60));

        assertThat(sum).isEqualByComparingTo(new BigDecimal("0.02"));
    }

    @Test
    void includesARowExactlyAtTheSinceBoundary() {
        UUID user = UUID.randomUUID();
        Instant boundary = Instant.now().minusSeconds(60);
        row(user, new BigDecimal("0.05"), boundary);

        BigDecimal sum = repository.sumCostByUserSince(user, boundary);

        assertThat(sum).isEqualByComparingTo(new BigDecimal("0.05"));
    }

    @Test
    void orgWideSumIncludesEveryUser() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        Instant now = Instant.now();
        row(userA, new BigDecimal("1.00"), now);
        row(userB, new BigDecimal("2.50"), now);

        BigDecimal sum = repository.sumCostSince(now.minusSeconds(60));

        assertThat(sum).isEqualByComparingTo(new BigDecimal("3.50"));
    }
}
