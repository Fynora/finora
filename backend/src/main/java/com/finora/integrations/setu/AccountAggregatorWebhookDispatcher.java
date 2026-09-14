package com.finora.integrations.setu;

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
    private final AuditService auditService;
    private final AccountAggregatorIdentityResolutionService identityResolutionService;
    private final SetuDataFetchService fetchService;
    private final AccountAggregatorLinkManagementService linkManagementService;

    public AccountAggregatorWebhookDispatcher(AccountAggregatorLinkRepository links,
                                               AuditService auditService,
                                               AccountAggregatorIdentityResolutionService identityResolutionService,
                                               SetuDataFetchService fetchService,
                                               AccountAggregatorLinkManagementService linkManagementService) {
        this.links = links;
        this.auditService = auditService;
        this.identityResolutionService = identityResolutionService;
        this.fetchService = fetchService;
        this.linkManagementService = linkManagementService;
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
                links.save(link);
                auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_CONSENT_REJECTED",
                        "AccountAggregatorLink", link.getId());
            }
            case "consent.revoked" ->
                    linkManagementService.revoke(link, "ACCOUNT_AGGREGATOR_CONSENT_REVOKED");
            case "consent.approved" -> identityResolutionService.resolveAndAttach(link);
            case "data.ready" -> {
                if (link.getStatus() != AccountAggregatorLinkStatus.ACTIVE) {
                    log.info("Ignoring data.ready for link {} not yet ACTIVE (status {}).",
                            link.getId(), link.getStatus());
                } else if (!fetchService.syncSinceLastAttempt(link)) {
                    log.info("Skipping data.ready for link {}: already synced through today.", link.getId());
                }
            }
            default -> log.info("Unhandled Setu webhook event type {}, ignoring.", LogSanitizer.sanitize(eventType));
        }
    }
}
