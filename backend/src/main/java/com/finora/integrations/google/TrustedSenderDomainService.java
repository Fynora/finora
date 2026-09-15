package com.finora.integrations.google;

import com.finora.config.CacheConfig;
import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import com.finora.util.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the trusted sender registry — Phase C3.
 *
 * <p><b>Every mutation here is a security decision, not configuration.</b> Adding a domain grants
 * parse-trust to a new sender: from that moment, authenticated mail from it may become financial
 * records in someone's ledger. That is why each change is audited with the acting admin, and why
 * nothing is ever hard-deleted.
 */
@Service
public class TrustedSenderDomainService {

    private static final Logger log = LoggerFactory.getLogger(TrustedSenderDomainService.class);

    private final TrustedSenderDomainRepository domains;
    private final AuditService auditService;
    private final CacheManager cacheManager;

    public TrustedSenderDomainService(TrustedSenderDomainRepository domains, AuditService auditService,
                                       CacheManager cacheManager) {
        this.domains = domains;
        this.auditService = auditService;
        this.cacheManager = cacheManager;
    }

    @Transactional(readOnly = true)
    public List<TrustedSenderDomain> listAll() {
        return domains.findAllByOrderByMerchantNameAscDomainAsc();
    }

    /**
     * Whether an already-authenticated domain is trusted right now -- {@link
     * SenderAuthenticationService#evaluate}'s hot-path check, called once per Gmail message
     * examined. Cached (see {@link CacheConfig#TRUSTED_SENDER_DOMAINS_CACHE}): a single real
     * discovery run against one backlogged mailbox made 301 of these calls against a table that
     * only ever changes through {@link #add}/{@link #setStatus} below, both of which evict
     * explicitly so a security-relevant change (disabling a compromised domain) is visible on the
     * very next message examined, not after a stale TTL.
     *
     * <p>{@code sync = true}: concurrent callers that miss the same key block behind the first
     * load rather than each independently querying -- see {@link CacheConfig}'s own doc comment.
     *
     * <p>Returns a plain {@code boolean}, not the entity or an {@code Optional} of it -- the same
     * choice {@code FeatureFlagService.isEnabled} makes, and for the same reason: the caller only
     * ever needs the yes/no answer, and a boolean has no serialization surface to get wrong the way
     * a cached entity does (see {@code CacheConfig}'s own {@code JavaTimeModule} landmine note).
     *
     * @param domain already normalized by the caller ({@link TrustedSenderDomain#normalize}) --
     *               this method does not normalize again, so the cache key matches exactly what
     *               {@link #evictCache} clears on a mutation below.
     */
    @Cacheable(cacheNames = CacheConfig.TRUSTED_SENDER_DOMAINS_CACHE, key = "#domain", sync = true)
    public boolean isActiveTrusted(String domain) {
        return domains.findByDomain(domain).filter(TrustedSenderDomain::isActive).isPresent();
    }

    /**
     * Adds a domain to the registry.
     *
     * <p>A domain that already exists is a conflict rather than an update, deliberately: silently
     * re-enabling a domain someone previously disabled would undo a security decision without
     * anyone deciding to. Re-enabling is an explicit {@link #setStatus} call, and it is audited as
     * one.
     */
    @Transactional
    public TrustedSenderDomain add(UUID actingAdminId, String rawDomain, String merchantName) {
        String domain = requireValidDomain(rawDomain);
        if (merchantName == null || merchantName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A merchant name is required.");
        }

        domains.findByDomain(domain).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    "That domain is already in the registry (" + existing.getStatus() + "). "
                            + "Change its status instead of adding it again.");
        });

        TrustedSenderDomain entry = new TrustedSenderDomain();
        entry.setDomain(domain);
        entry.setMerchantName(merchantName.trim());
        entry.setStatus(TrustedSenderDomain.Status.ACTIVE);
        entry.setAddedByUserId(actingAdminId);
        TrustedSenderDomain saved = domains.save(entry);

        auditService.record(actingAdminId, "GMAIL_TRUSTED_DOMAIN_CREATED",
                "TrustedSenderDomain", saved.getId(),
                Map.of("domain", domain, "merchantName", saved.getMerchantName()));
        log.info("Trusted sender domain {} added for {} by admin {}.", domain, merchantName, actingAdminId);
        evictCache(domain);
        return saved;
    }

    /**
     * Enables or disables a domain — the "delete" this registry has.
     *
     * <p>Nothing is ever removed. "When did we stop trusting this domain, and who decided" is the
     * question asked after an incident, and a deleted row cannot answer it. A disabled entry behaves
     * exactly as an absent one at the gate.
     */
    @Transactional
    public TrustedSenderDomain setStatus(UUID actingAdminId, UUID id, TrustedSenderDomain.Status status) {
        TrustedSenderDomain entry = domains.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such trusted sender domain."));

        TrustedSenderDomain.Status previous = entry.getStatus();
        if (previous == status) {
            return entry; // no-op, and nothing worth auditing
        }
        entry.setStatus(status);
        TrustedSenderDomain saved = domains.save(entry);

        // Both directions are audited. Disabling is the obvious one; RE-ENABLING is the one that
        // silently restores parse-trust, so it is if anything the more important of the two.
        auditService.record(actingAdminId,
                status == TrustedSenderDomain.Status.ACTIVE
                        ? "GMAIL_TRUSTED_DOMAIN_ENABLED" : "GMAIL_TRUSTED_DOMAIN_DISABLED",
                "TrustedSenderDomain", saved.getId(),
                Map.of("domain", saved.getDomain(),
                        "previousStatus", previous.name(),
                        "newStatus", status.name()));
        log.info("Trusted sender domain {} moved {} -> {} by admin {}.",
                saved.getDomain(), previous, status, actingAdminId);
        evictCache(saved.getDomain());
        return saved;
    }

    /**
     * Changes the merchant label. Deliberately cannot change the DOMAIN.
     *
     * <p>Editing a domain in place would silently move trust from one sender to another under a row
     * whose audit trail still describes the first — "amazon.in, added by X on date Y" would now mean
     * something else entirely. Trusting a different domain is an add, and untrusting one is a
     * disable; both leave a record of what actually happened.
     */
    @Transactional
    public TrustedSenderDomain rename(UUID actingAdminId, UUID id, String merchantName) {
        if (merchantName == null || merchantName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A merchant name is required.");
        }
        TrustedSenderDomain entry = domains.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such trusted sender domain."));

        String previous = entry.getMerchantName();
        entry.setMerchantName(merchantName.trim());
        TrustedSenderDomain saved = domains.save(entry);

        auditService.record(actingAdminId, "GMAIL_TRUSTED_DOMAIN_RELABELLED",
                "TrustedSenderDomain", saved.getId(),
                Map.of("domain", saved.getDomain(),
                        "previousMerchantName", previous,
                        "newMerchantName", saved.getMerchantName()));
        return saved;
    }

    /** Post-commit, not {@code @CacheEvict} -- the same choice {@code FeatureFlagService.setEnabled}
     *  makes and for the same reason: relying on {@code @Transactional}/{@code @CacheEvict}
     *  interceptor ordering to guarantee eviction happens strictly after commit is implicit and
     *  hard to verify, where an explicit post-commit callback is neither. Evicting before commit
     *  would open a window where a concurrent reader repopulates the cache with the pre-commit
     *  (stale) value between the evict and the commit actually landing. */
    private void evictCache(String domain) {
        AfterCommit.run("trusted sender domain cache invalidation", () -> {
            Cache cache = cacheManager.getCache(CacheConfig.TRUSTED_SENDER_DOMAINS_CACHE);
            if (cache != null) cache.evict(domain);
        });
    }

    /** See {@link TrustedSenderDomain#requireValid}'s own doc comment -- shared with
     *  {@code MerchantTemplateAdminService} so an admin-entered domain is validated identically
     *  by both callers. */
    private String requireValidDomain(String rawDomain) {
        try {
            return TrustedSenderDomain.requireValid(rawDomain);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
