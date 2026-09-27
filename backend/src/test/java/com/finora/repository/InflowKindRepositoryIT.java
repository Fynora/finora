package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.InflowKind;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The constraints the inflow-kind service relies on, checked against real Postgres. */
class InflowKindRepositoryIT extends AbstractIntegrationTest {

    @Autowired private InflowKindRepository kinds;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private UUID newUser() {
        User u = new User();
        u.setEmail("inflow-kind-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant");
        u.setFullName("Inflow Kind IT");
        u.setAccountScope(User.SCOPE_USER);
        return users.save(u).getId();
    }

    @Test
    void builtInInsertIsIdempotent() {
        UUID userId = newUser();
        // A @Modifying query needs a transaction; InflowKindService always calls it inside one.
        assertThat((Integer) tx.execute(s -> kinds.insertBuiltInIfMissing(userId, "Income", true, "INCOME"))).isEqualTo(1);
        assertThat((Integer) tx.execute(s -> kinds.insertBuiltInIfMissing(userId, "Income", true, "INCOME"))).isEqualTo(0);
        assertThat(kinds.countByUserIdAndBuiltInIsNotNull(userId)).isEqualTo(1);
    }

    @Test
    void namesAreUniquePerUserIgnoringCase() {
        UUID userId = newUser();
        InflowKind a = new InflowKind();
        a.setUserId(userId); a.setName("Rent from tenant"); a.setCountsAsIncome(true);
        kinds.saveAndFlush(a);
        InflowKind b = new InflowKind();
        b.setUserId(userId); b.setName("RENT FROM TENANT"); b.setCountsAsIncome(false);
        assertThatThrownBy(() -> kinds.saveAndFlush(b)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transactionColumnReferencesInflowKinds() {
        Integer fk = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage k ON k.constraint_name = tc.constraint_name
                WHERE tc.table_name = 'transactions' AND tc.constraint_type = 'FOREIGN KEY'
                  AND k.column_name = 'inflow_kind_id'""", Integer.class);
        assertThat(fk).isEqualTo(1);
    }
}
