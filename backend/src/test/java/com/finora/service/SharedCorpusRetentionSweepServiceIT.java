package com.finora.service;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unit test mocks the repository, so it can't show what the derived
 * {@code deleteByCounterpartyKeyAndDirection} actually needs at runtime. This runs the real
 * {@code @Scheduled} entry point the way the scheduler does -- with no transaction open --
 * against a real Postgres. Deliberately not {@code @Transactional}: an ambient test transaction
 * would hide a missing one in the service (see AccountPurgeSweepServiceIT's non-transactional
 * tests for the production bug that pattern hid).
 */
class SharedCorpusRetentionSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private SharedCorpusRetentionSweepService sweep;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void sweep_outsideAnyTransaction_deletesAnExpiredSingleVoterKey_andKeepsAFreshOne() {
        String expiredKey = "retention-it-expired-" + UUID.randomUUID();
        String freshKey = "retention-it-fresh-" + UUID.randomUUID();
        insertObservation(expiredKey, Instant.now().minus(200, ChronoUnit.DAYS));
        insertObservation(freshKey, Instant.now().minus(1, ChronoUnit.DAYS));

        sweep.sweep();

        assertThat(countFor(expiredKey)).isZero();
        assertThat(countFor(freshKey)).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM counterparty_category_observation WHERE counterparty_key = ?", freshKey);
    }

    private void insertObservation(String counterpartyKey, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO counterparty_category_observation (counterparty_key, direction, category, "
                        + "user_id, counterparty_type_at_vote, created_at) VALUES (?, 'EXPENSE', 'Food', ?, 'PERSON', ?)",
                counterpartyKey, UUID.randomUUID(), Timestamp.from(createdAt));
    }

    private int countFor(String counterpartyKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM counterparty_category_observation WHERE counterparty_key = ?",
                Integer.class, counterpartyKey);
        return count == null ? 0 : count;
    }
}
