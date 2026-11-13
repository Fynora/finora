package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One purge of an account at a time (V316). The admin delete, the customer's own delete and the
 * sweep all run AccountPurgeSweepService.purgeOne outside a transaction; before the claim, six
 * simultaneous admin deletes of one account ran the whole purge six times (six
 * ACCOUNT_PURGE_STARTED rows, and the outbound Gmail/Razorpay calls each time).
 */
class AccountPurgeClaimIT extends AbstractIntegrationTest {

    @Autowired private AccountPurgeSweepService purgeService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID customer() {
        User user = new User();
        user.setEmail("purge-claim-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Purge Claim IT");
        return userRepository.save(user).getId();
    }

    private int startedRows(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'ACCOUNT_PURGE_STARTED'",
                Integer.class, userId);
    }

    private String status(UUID userId) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    void simultaneousAdminDeletesRunThePurgeOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 5; round++) {
                UUID userId = customer();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> calls = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        try {
                            purgeService.adminPurge(userId, UUID.randomUUID());
                            return "done";
                        } catch (ApiException e) {
                            return String.valueOf(e.getStatus().value());
                        }
                    }));
                }
                start.countDown();
                List<String> outcomes = new ArrayList<>();
                for (Future<String> call : calls) outcomes.add(call.get(60, TimeUnit.SECONDS));

                assertThat(startedRows(userId)).as("round %d %s", round, outcomes).isEqualTo(1);
                assertThat(status(userId)).isEqualTo(User.STATUS_DELETED);
                // Every other request either found the purge underway (409) or found it finished.
                assertThat(outcomes).as("round %d", round).allMatch(o -> o.equals("done") || o.equals("409"));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDeleteWhileAnotherPurgeHoldsTheAccount_isRefusedAndTouchesNothing() {
        UUID userId = customer();
        jdbc.update("UPDATE users SET purge_claimed_at = now() WHERE id = ?", userId);

        assertThatThrownBy(() -> purgeService.adminPurge(userId, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(startedRows(userId)).isZero();
        // Marked pending, so the sweep finishes it if the holder fails.
        assertThat(status(userId)).isEqualTo(User.STATUS_PENDING_DELETION);
    }

    @Test
    void aClaimOlderThanTheWindowIsTakenOver() {
        UUID userId = customer();
        jdbc.update("UPDATE users SET purge_claimed_at = now() - interval '20 minutes' WHERE id = ?", userId);

        purgeService.adminPurge(userId, UUID.randomUUID());

        assertThat(status(userId)).isEqualTo(User.STATUS_DELETED);
        assertThat(startedRows(userId)).isEqualTo(1);
    }

    @Test
    void deletingAnAccountThatIsAlreadyDeletedIsAQuietNoOp() {
        UUID userId = customer();
        purgeService.adminPurge(userId, UUID.randomUUID());

        purgeService.adminPurge(userId, UUID.randomUUID());

        assertThat(startedRows(userId)).isEqualTo(1);
    }
}
