package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** AI insights foundation (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §5.4, §6):
 *  the single-flight insert, the atomic Free allowance, stale take-over and the FYN_INSIGHTS grant. */
class AiInsightRepositoriesIT extends AbstractIntegrationTest {

    @Autowired private AiInsightTextRepository texts;
    @Autowired private AiInsightUsageRepository usage;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private UUID newUser() {
        User u = new User();
        u.setEmail("ai-insight-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Sample User");
        return users.save(u).getId();
    }

    private int pending(UUID id, UUID userId, String hash) {
        return texts.insertPending(id, userId, "STORY", "MONTH", "2026-10", hash,
                "ai-story-v1", "claude-haiku-4-5-20251001", "policy-v1", "FREE");
    }

    private void age(UUID id, String status, long secondsAgo) {
        jdbc.update("UPDATE ai_insight_texts SET status = ?, updated_at = now() - make_interval(secs => ?) WHERE id = ?",
                status, secondsAgo, id);
    }

    @Test
    void insertPendingWinsOnceForTheSameKey() {
        UUID user = newUser();
        String hash = "a".repeat(64);
        assertThat(pending(UUID.randomUUID(), user, hash)).isEqualTo(1);
        assertThat(pending(UUID.randomUUID(), user, hash)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_insight_texts WHERE user_id = ?", Long.class, user))
                .isEqualTo(1L);
        assertThat(texts.findByKey(user, "STORY", "MONTH", "2026-10", hash, "ai-story-v1",
                "claude-haiku-4-5-20251001", "policy-v1")).get()
                .satisfies(t -> assertThat(t.getStatus()).isEqualTo("PENDING"));
    }

    @Test
    void parallelWhyReservationsNeverPassTheLimit() throws Exception {
        UUID user = newUser();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) calls.add(() -> usage.reserveWhy(user, "2026-10", 5));
            int sum = 0;
            for (Future<Integer> f : pool.invokeAll(calls)) sum += f.get();
            assertThat(sum).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(usage.whyUsed(user, "2026-10")).isEqualTo(5);
        // Another month starts from nothing.
        assertThat(usage.whyUsed(user, "2026-11")).isZero();
    }

    @Test
    void releaseNeverGoesBelowZero() {
        UUID user = newUser();
        assertThat(usage.reserveWhy(user, "2026-10", 5)).isEqualTo(1);
        usage.releaseWhy(user, "2026-10");
        usage.releaseWhy(user, "2026-10");
        assertThat(usage.whyUsed(user, "2026-10")).isZero();
        assertThat(usage.reserveStory(user, "2026-10", 4)).isEqualTo(1);
        usage.releaseStory(user, "2026-10");
        usage.releaseStory(user, "2026-10");
        assertThat(jdbc.queryForObject("SELECT story_generations FROM ai_insight_usage WHERE user_id = ? AND month = ?",
                Integer.class, user, "2026-10")).isZero();
    }

    @Test
    void takeOverStaleOnlyTakesOldPendingOrOldFailed() {
        UUID user = newUser();
        Instant now = Instant.now();
        Instant pendingBefore = now.minusSeconds(60);
        Instant failedBefore = now.minusSeconds(600);

        UUID freshPending = UUID.randomUUID();
        pending(freshPending, user, "b".repeat(64));
        assertThat(texts.takeOverStale(freshPending, pendingBefore, failedBefore)).isZero();

        UUID oldPending = UUID.randomUUID();
        pending(oldPending, user, "c".repeat(64));
        age(oldPending, "PENDING", 61);
        assertThat(texts.takeOverStale(oldPending, pendingBefore, failedBefore)).isEqualTo(1);

        UUID recentFailed = UUID.randomUUID();
        pending(recentFailed, user, "d".repeat(64));
        age(recentFailed, "FAILED", 9 * 60);
        assertThat(texts.takeOverStale(recentFailed, pendingBefore, failedBefore)).isZero();

        UUID oldFailed = UUID.randomUUID();
        pending(oldFailed, user, "e".repeat(64));
        age(oldFailed, "FAILED", 11 * 60);
        assertThat(texts.takeOverStale(oldFailed, pendingBefore, failedBefore)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_insight_texts WHERE id = ?", String.class, oldFailed))
                .isEqualTo("PENDING");
    }

    @Test
    void readyRowsAreFoundNewestFirstAndOnlyByTheirOwner() {
        UUID user = newUser();
        UUID other = newUser();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        pending(older, user, "f".repeat(64));
        pending(newer, user, "0".repeat(64));
        jdbc.update("UPDATE ai_insight_texts SET status = 'READY', created_at = now() - interval '2 days' WHERE id = ?", older);
        jdbc.update("UPDATE ai_insight_texts SET status = 'READY' WHERE id = ?", newer);

        assertThat(texts.findLatestReady(user, "STORY", "MONTH", "2026-10")).get()
                .satisfies(t -> assertThat(t.getId()).isEqualTo(newer));
        assertThat(texts.countCreatedSince(user, "STORY", "2026-10", Instant.now().minusSeconds(86_400))).isEqualTo(1L);
        assertThat(texts.findByIdAndUserId(newer, user)).isPresent();
        assertThat(texts.findByIdAndUserId(newer, other)).isEmpty();
    }

    @Test
    void rowsGoWithTheUser() {
        UUID user = newUser();
        pending(UUID.randomUUID(), user, "1".repeat(64));
        usage.reserveWhy(user, "2026-10", 5);

        users.deleteById(user);
        users.flush();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_insight_texts WHERE user_id = ?", Long.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_insight_usage WHERE user_id = ?", Long.class, user)).isZero();
    }

    @Test
    void purgeDeletesBothTables() {
        UUID user = newUser();
        pending(UUID.randomUUID(), user, "2".repeat(64));
        usage.reserveWhy(user, "2026-10", 5);

        assertThat(texts.deleteByUserId(user)).isEqualTo(1);
        assertThat(usage.deleteByUserId(user)).isEqualTo(1);
    }

    @Test
    void featureEntitlementGrantedToAllThreePlans() {
        List<String> plans = jdbc.queryForList("""
                SELECT p.code FROM feature_entitlements fe JOIN plans p ON p.id = fe.plan_id
                WHERE fe.feature_key = 'FYN_INSIGHTS' AND fe.enabled ORDER BY p.code""", String.class);
        assertThat(plans).containsExactly("FREE", "PLUS", "PREMIUM");
    }

    @Test
    void auditLogCarriesPlanAndReason() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'ai_audit_log' AND column_name IN ('plan_code', 'reason_code')
                ORDER BY column_name""", String.class)).containsExactly("plan_code", "reason_code");
    }
}
