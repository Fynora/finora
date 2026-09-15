package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.config.CacheConfig;
import com.finora.entity.AiAuditLog;
import com.finora.repository.AiAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code FynCostGovernanceService.monthlyBudget()}'s new {@code @Cacheable} actually
 * caches through a real proxied bean against real Redis + Postgres -- a plain unit test
 * instantiating the service with {@code new} (as {@code FynCostGovernanceServiceTest} does)
 * bypasses Spring's caching AOP entirely and would prove nothing about whether the annotation
 * works. Generic Redis-backed-cache behavior (fail-open, java.time serialization, sync=true
 * stampede protection) is already covered once for the whole {@code CacheManager} by {@code
 * CacheConfigIT}; this class only tests what's specific to wiring this particular method into
 * that infrastructure.
 */
class FynCostGovernanceServiceCachingIT extends AbstractIntegrationTest {

    @Autowired private FynCostGovernanceService costGovernanceService;
    @Autowired private AiAuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CacheManager cacheManager;

    // Same shared-table leakage reasoning as AiAuditLogRepositoryIT's own emptyAiAuditLog --
    // sumCostSince is org-wide and unscoped, so another test class's rows within this class's
    // "since this month" window would otherwise change what monthlyBudget() returns here.
    @BeforeEach
    void emptyAiAuditLogAndCache() {
        jdbcTemplate.update("DELETE FROM ai_audit_log");
        Cache cache = cacheManager.getCache(CacheConfig.FYN_MONTHLY_BUDGET_CACHE);
        assertThat(cache).isNotNull();
        cache.clear();
    }

    private void saveAuditRow(BigDecimal cost) {
        AiAuditLog log = new AiAuditLog();
        log.setUserId(UUID.randomUUID());
        log.setModel("claude-haiku-4-5-20251001");
        log.setPromptVersion("v1");
        log.setTokensIn(100);
        log.setTokensOut(50);
        log.setCost(cost);
        log.setLatencyMs(500);
        auditLogRepository.save(log);
    }

    /** The whole point of caching this method: a second call within the TTL must not reflect
     *  spend that arrived in between -- if it did, the annotation would be a no-op and every Fyn
     *  request would still be paying for the full org-wide SUM every time. */
    @Test
    void secondCallWithinTtlDoesNotReflectSpendInsertedInBetween() {
        saveAuditRow(new BigDecimal("1.00"));

        BigDecimal firstSpend = costGovernanceService.monthlyBudget().spent();
        assertThat(firstSpend).isEqualByComparingTo(new BigDecimal("1.00"));

        saveAuditRow(new BigDecimal("5.00")); // would push spend to 6.00 if not cached

        BigDecimal secondSpend = costGovernanceService.monthlyBudget().spent();
        assertThat(secondSpend).isEqualByComparingTo(firstSpend);
    }

    /** Rules out the opposite failure mode -- a cache that never actually refreshes would make
     *  the monthly STOPPED budget permanently stuck at whatever it read once. Evicting manually
     *  stands in for the 30s TTL expiring so this test doesn't have to sleep 30 real seconds. */
    @Test
    void reflectsNewSpendOnceTheCacheEntryIsGone() {
        saveAuditRow(new BigDecimal("1.00"));
        BigDecimal firstSpend = costGovernanceService.monthlyBudget().spent();

        saveAuditRow(new BigDecimal("5.00"));
        cacheManager.getCache(CacheConfig.FYN_MONTHLY_BUDGET_CACHE).clear();

        BigDecimal secondSpend = costGovernanceService.monthlyBudget().spent();
        assertThat(secondSpend).isEqualByComparingTo(new BigDecimal("6.00"));
        assertThat(secondSpend).isNotEqualByComparingTo(firstSpend);
    }
}
