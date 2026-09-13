package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import com.finora.service.AuditService;
import com.finora.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * One method per Setu event type this application acts on -- named and shaped after
 * RazorpayWebhookDispatcher deliberately. consent.approved is handled by Task 8, which adds the
 * AccountAggregatorIdentityResolutionService dependency and its switch branch; this task only
 * wires the two simpler terminal-state transitions.
 */
@Component
public class AccountAggregatorWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorWebhookDispatcher.class);

    private final AccountAggregatorLinkRepository links;
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final AccountAggregatorIdentityResolutionService identityResolutionService;
    private final SetuDataFetchService fetchService;

    public AccountAggregatorWebhookDispatcher(AccountAggregatorLinkRepository links, AccountRepository accountRepository,
                                               AuditService auditService,
                                               AccountAggregatorIdentityResolutionService identityResolutionService,
                                               SetuDataFetchService fetchService) {
        this.links = links;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.identityResolutionService = identityResolutionService;
        this.fetchService = fetchService;
    }

    public void dispatch(String eventType, String consentHandleId) {
        Optional<AccountAggregatorLink> maybeLink = links.findByConsentHandleId(consentHandleId);
        if (maybeLink.isEmpty()) {
            log.info("Setu webhook {} for unknown consent handle {}, ignoring.",
                    LogSanitizer.sanitize(eventType), LogSanitizer.sanitize(consentHandleId));
            return;
        }
        AccountAggregatorLink link = maybeLink.get();

        switch (eventType) {
            case "consent.rejected" -> {
                link.setStatus(AccountAggregatorLinkStatus.REJECTED);
                auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_CONSENT_REJECTED",
                        "AccountAggregatorLink", link.getId());
            }
            case "consent.revoked" -> {
                link.setStatus(AccountAggregatorLinkStatus.REVOKED);
                if (link.getAccountId() != null) {
                    accountRepository.findById(link.getAccountId()).ifPresent(account -> {
                        account.setPrimarySource(Account.PrimarySource.MANUAL);
                        accountRepository.save(account);
                    });
                }
                auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_CONSENT_REVOKED",
                        "AccountAggregatorLink", link.getId());
            }
            case "consent.approved" -> identityResolutionService.resolveAndAttach(link);
            case "data.ready" -> {
                if (link.getStatus() != AccountAggregatorLinkStatus.ACTIVE) {
                    log.info("Ignoring data.ready for link {} not yet ACTIVE (status {}).",
                            link.getId(), link.getStatus());
                } else {
                    java.time.LocalDate to = java.time.LocalDate.now();
                    java.time.LocalDate from = link.getLastSyncedAt() != null
                            ? link.getLastSyncedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1)
                            : to.minusMonths(3);
                    fetchService.sync(link, from, to);
                }
            }
            default -> log.info("Unhandled Setu webhook event type {}, ignoring.", LogSanitizer.sanitize(eventType));
        }
    }
}
