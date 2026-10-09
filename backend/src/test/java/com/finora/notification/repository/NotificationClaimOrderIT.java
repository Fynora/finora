package com.finora.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationPriority;
import com.finora.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dispatcher claims its batch by priority first, then by due time.
 *
 * <p>It used to order by {@code next_attempt_at} alone, so priority was stored and ignored: a big
 * LOW batch (an admin push campaign) queued earlier than a user's "import ready" push or a
 * password-changed alert would have been delivered first, 50 rows per pass. The rows are written
 * with the same {@code insertIfAbsent} the real senders use, and the assertions are about the order
 * of this test's own rows only, so rows other tests left in the shared table cannot affect them.
 */
class NotificationClaimOrderIT extends AbstractIntegrationTest {

    @Autowired private NotificationRepository repository;
    @Autowired private UserRepository userRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private UUID user() {
        User user = new User();
        user.setEmail("claim-order-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Claim Order IT User");
        return userRepository.save(user).getId();
    }

    private UUID insert(UUID userId, String key, NotificationPriority priority, Instant dueAt) {
        return repository.insertIfAbsent(userId, key, "CUSTOM_PUSH", "FINANCIAL", "PUSH",
                priority.name(), "t", "m", null, dueAt).orElseThrow();
    }

    @Test
    @Transactional
    void aLargeLowBacklogDoesNotDelayANormalRowQueuedLater() {
        UUID userId = user();
        Instant now = Instant.now();
        String run = UUID.randomUUID().toString();
        List<UUID> low = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            // Due two hours ago: strictly older than the NORMAL row below.
            low.add(insert(userId, "ORDER_LOW_" + run + "_" + i, NotificationPriority.LOW,
                    now.minus(Duration.ofHours(2)).plusSeconds(i)));
        }
        UUID normal = insert(userId, "ORDER_NORMAL_" + run, NotificationPriority.NORMAL,
                now.minus(Duration.ofMinutes(1)));

        List<UUID> claimed = repository.claimDue(now, 50).stream().map(Notification::getId).toList();

        assertThat(claimed).contains(normal);
        int normalAt = claimed.indexOf(normal);
        for (UUID lowId : low) {
            int lowAt = claimed.indexOf(lowId);
            assertThat(lowAt == -1 || lowAt > normalAt)
                    .as("a LOW row (index %d) must not be claimed ahead of the NORMAL row (index %d)",
                            lowAt, normalAt)
                    .isTrue();
        }
    }

    @Test
    @Transactional
    void priorityOrdersCriticalThenHighThenNormalThenLowAndTimeBreaksTiesWithinOne() {
        UUID userId = user();
        Instant now = Instant.now();
        String run = UUID.randomUUID().toString();
        // Inserted in the opposite order from how they must come out, with the oldest due time on
        // the lowest priority, so only the priority term can produce the expected order.
        UUID low = insert(userId, "ORDER_P_LOW_" + run, NotificationPriority.LOW, now.minusSeconds(500));
        UUID normalLater = insert(userId, "ORDER_P_NORMAL2_" + run, NotificationPriority.NORMAL, now.minusSeconds(100));
        UUID normalEarlier = insert(userId, "ORDER_P_NORMAL1_" + run, NotificationPriority.NORMAL, now.minusSeconds(200));
        UUID high = insert(userId, "ORDER_P_HIGH_" + run, NotificationPriority.HIGH, now.minusSeconds(50));
        UUID critical = insert(userId, "ORDER_P_CRIT_" + run, NotificationPriority.CRITICAL, now.minusSeconds(10));

        List<UUID> mine = List.of(critical, high, normalEarlier, normalLater, low);
        List<UUID> claimed = repository.claimDue(now, 5000).stream().map(Notification::getId)
                .filter(mine::contains).toList();

        assertThat(claimed).containsExactly(critical, high, normalEarlier, normalLater, low);
    }

    /**
     * The priority ordering is only cheap because {@code idx_notifications_claim_order} (V261)
     * repeats the query's CASE expression exactly: measured on 100,000 queued rows, 60 ms and a
     * disk-spilling sort per claim without it, 0.065 ms with it. A table this small prefers a
     * sequential scan whatever the indexes say, so scans and explicit sorts are discouraged for the
     * duration of this transaction; the planner then reaches for the index only if the expression in
     * the real query (read from the annotation, not retyped here) matches the index's.
     */
    @Test
    @Transactional
    void theClaimQueryOrdersByAnIndexSoALargeBacklogIsNotSortedOnEveryPass() throws Exception {
        String sql = NotificationRepository.class.getMethod("claimDue", Instant.class, int.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class).value()
                .replace(":now", "now()").replace(":batchSize", "50");
        jdbc.execute("SET LOCAL enable_seqscan = off");
        jdbc.execute("SET LOCAL enable_sort = off");

        List<String> plan = jdbc.queryForList("EXPLAIN " + sql, String.class);

        assertThat(String.join("\n", plan)).contains("idx_notifications_claim_order");
    }

    /**
     * The "oldest pending" gauge drives a critical alert at 15 minutes. A campaign's backlog is hours
     * old by design (about 100 people a minute), so it must not count, or every normal send pages.
     * The campaign row here is a year overdue, older than anything another test could have left, so
     * if it were counted it would be the answer.
     */
    @Test
    @Transactional
    void aCampaignBacklogDoesNotCountTowardsTheOldestPendingNotification() {
        UUID userId = user();
        Instant yearAgo = Instant.now().minus(Duration.ofDays(365));
        insert(userId, "ORDER_OLDEST_CAMPAIGN_" + UUID.randomUUID(), NotificationPriority.LOW, yearAgo);
        // insert() writes type CUSTOM_PUSH; this one is an ordinary notification, a minute old.
        Instant aMinuteAgo = Instant.now().minus(Duration.ofMinutes(1));
        repository.insertIfAbsent(userId, "ORDER_OLDEST_NORMAL_" + UUID.randomUUID(), "PASSWORD_CHANGED",
                "SECURITY", "PUSH", "HIGH", "t", "m", null, aMinuteAgo);

        Instant oldest = repository.findOldestPendingAt().orElseThrow();

        assertThat(oldest).isNotEqualTo(yearAgo);
        assertThat(oldest).isAfter(yearAgo.plus(Duration.ofDays(1)));
    }

    @Test
    @Transactional
    void aRowNotYetDueIsNeverClaimedWhateverItsPriority() {
        UUID userId = user();
        Instant now = Instant.now();
        UUID future = insert(userId, "ORDER_FUTURE_" + UUID.randomUUID(), NotificationPriority.CRITICAL,
                now.plus(Duration.ofHours(1)));

        assertThat(repository.claimDue(now, 5000).stream().map(Notification::getId)).doesNotContain(future);
    }
}
