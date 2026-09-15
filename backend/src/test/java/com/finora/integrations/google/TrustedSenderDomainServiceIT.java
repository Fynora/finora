package com.finora.integrations.google;

import com.finora.AbstractIntegrationTest;
import com.finora.config.CacheConfig;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link TrustedSenderDomainService#isActiveTrusted} is genuinely intercepted by Spring's
 * caching AOP against the real, Testcontainer-backed Redis {@link CacheManager} -- not merely
 * that the shared {@code RedisCacheManager} machinery works in the abstract, which {@code
 * CacheConfigIT}'s synthetic probe bean already covers, but that THIS method's own {@code
 * @Cacheable(cacheNames = ..., key = "#domain")} actually resolves to the right cache under the
 * right key at runtime. {@code TrustedSenderDomainServiceTest} (the unit test) proves the
 * service's own eviction-call logic against a mocked {@code CacheManager}; neither that nor
 * {@code AdminTrustedSenderEndpointIT} (which only drives the admin CRUD endpoints, never this
 * lookup) previously verified the annotation itself does what it says once a real proxy is in
 * front of it -- exactly the gap a mock cannot close, the same reasoning every other
 * atomicity/concurrency-critical mechanism in this codebase gets a real-infrastructure IT for.
 *
 * <p>This is a security-relevant lookup, unlike most of what the existing Redis caches guard
 * ({@code customBanks}, {@code featureFlags}) -- a stale "trusted" answer is a fabricated
 * financial record's way in, not a cosmetic UI staleness, so proving eviction genuinely reaches
 * the real cache entry (not just that the service calls {@code Cache.evict}) matters more here
 * than it would for those.
 *
 * <p>Every domain used here is unique per test ({@code UUID.randomUUID()}-suffixed) rather than a
 * fixed string like {@code "amazon.in"} -- {@code AbstractIntegrationTest}'s Redis cleanup
 * between tests clears rate-limiter and import-concurrency keys but not cache entries (see its own
 * {@code resetRedisBackedLimiterState}), so a fixed domain risks a stale cross-test cache hit from
 * an earlier test in the same JVM/Testcontainer run.
 */
class TrustedSenderDomainServiceIT extends AbstractIntegrationTest {

    @Autowired private TrustedSenderDomainService service;
    @Autowired private TrustedSenderDomainRepository domains;
    @Autowired private CacheManager cacheManager;
    @Autowired private UserRepository userRepository;

    // add()/setStatus() audit the acting admin via AuditService, which foreign-keys to a real
    // users row (see AdminTrustedSenderEndpointIT's identical createOrdinaryUser() pattern) --
    // an unpersisted UUID.randomUUID() fails that insert, not the behavior this class exists to
    // prove.
    private UUID adminId;

    @BeforeEach
    void createActingAdmin() {
        User admin = new User();
        admin.setEmail("trusted-sender-cache-it-" + UUID.randomUUID() + "@example.test");
        admin.setPasswordHash("irrelevant-for-this-test");
        admin.setFullName("Cache Proof Admin");
        admin.setRole("ADMIN");
        admin.setAccountScope(User.SCOPE_USER);
        admin.setPhoneVerified(true);
        adminId = userRepository.save(admin).getId();
    }

    private String freshDomain() {
        return "cache-proof-" + UUID.randomUUID() + ".example";
    }

    private TrustedSenderDomain seed(String domain, TrustedSenderDomain.Status status) {
        TrustedSenderDomain entry = new TrustedSenderDomain();
        entry.setDomain(domain);
        entry.setMerchantName("Cache Proof Merchant");
        entry.setStatus(status);
        return domains.save(entry);
    }

    @Test
    void isActiveTrusted_writesTheRealCacheEntryUnderTheDomainKey() {
        String domain = freshDomain();
        seed(domain, TrustedSenderDomain.Status.ACTIVE);

        boolean result = service.isActiveTrusted(domain);

        assertThat(result).isTrue();
        Cache cache = cacheManager.getCache(CacheConfig.TRUSTED_SENDER_DOMAINS_CACHE);
        assertThat(cache).isNotNull();
        // If the SpEL key or cache name were wrong, this would read null -- the exact class of
        // bug a mocked CacheManager (as in TrustedSenderDomainServiceTest) cannot expose, since a
        // mock never rejects a key mismatch the way a real cache miss does.
        assertThat(cache.get(domain, Boolean.class))
                .as("the real @Cacheable proxy must have written true under this exact key")
                .isTrue();
    }

    @Test
    void isActiveTrusted_answersFromTheCacheOnASecondCallEvenAfterTheRowIsDeletedUnderneathIt() {
        String domain = freshDomain();
        TrustedSenderDomain entry = seed(domain, TrustedSenderDomain.Status.ACTIVE);
        assertThat(service.isActiveTrusted(domain)).isTrue();

        // Mutate the underlying row directly, bypassing setStatus() (and so its eviction) --
        // the only way to prove the SECOND call is actually served from cache rather than
        // coincidentally reading the same still-true row again.
        entry.setStatus(TrustedSenderDomain.Status.DISABLED);
        domains.save(entry);

        assertThat(service.isActiveTrusted(domain))
                .as("a real cache hit must still answer true here -- proving this call did not "
                        + "reach the database again, where it would now see DISABLED")
                .isTrue();
    }

    @Test
    void setStatus_evictsTheRealCacheEntrySoTheNextCallSeesTheNewAnswer() {
        String domain = freshDomain();
        TrustedSenderDomain entry = seed(domain, TrustedSenderDomain.Status.ACTIVE);
        assertThat(service.isActiveTrusted(domain))
                .as("populate the real cache entry first")
                .isTrue();

        service.setStatus(adminId, entry.getId(), TrustedSenderDomain.Status.DISABLED);

        Cache cache = cacheManager.getCache(CacheConfig.TRUSTED_SENDER_DOMAINS_CACHE);
        assertThat(cache).isNotNull();
        assertThat(cache.get(domain))
                .as("the real Redis entry, not just a mock's evict() call, must actually be gone")
                .isNull();
        assertThat(service.isActiveTrusted(domain))
                .as("recomputed fresh against the now-DISABLED row -- the security property this "
                        + "whole cache exists under: an admin's disable takes effect on the very "
                        + "next message examined, not after a stale TTL")
                .isFalse();
    }

    @Test
    void add_evictsAnyStaleFalseCachedBeforeTheDomainExisted() {
        String domain = freshDomain();
        // Nothing exists yet -- a discovery run examining this domain moments earlier would have
        // cached "not trusted", same as any never-seen domain.
        assertThat(service.isActiveTrusted(domain)).isFalse();

        service.add(adminId, domain, "Freshly Trusted Merchant");

        assertThat(service.isActiveTrusted(domain))
                .as("the stale pre-add false must not outlive the add for the cache's own TTL")
                .isTrue();
    }
}
