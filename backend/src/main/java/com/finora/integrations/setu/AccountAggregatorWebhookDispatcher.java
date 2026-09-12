package com.finora.integrations.setu;

import com.finora.service.AuditService;
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

    public AccountAggregatorWebhookDispatcher(AccountAggregatorLinkRepository links, AuditService auditService,
                                               AccountAggregatorIdentityResolutionService identityResolutionService) {
        this.links = links;
        this.auditService = auditService;
        this.identityResolutionService = identityResolutionService;
    }

    public void dispatch(String eventType, String consentHandleId) {
        Optional<AccountAggregatorLink> maybeLink = links.findByConsentHandleId(consentHandleId);
        if (maybeLink.isEmpty()) {
            log.info("Setu webhook {} for unknown consent handle {}, ignoring.", eventType, consentHandleId);
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
                auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_CONSENT_REVOKED",
                        "AccountAggregatorLink", link.getId());
                // Reverting the linked Account's primarySource back to MANUAL is Task 10's job (it
                // needs AccountRepository, which this dispatcher deliberately doesn't depend on yet
                // -- see that task for how it's added).
            }
            case "consent.approved" -> identityResolutionService.resolveAndAttach(link);
            default -> log.info("Unhandled Setu webhook event type {}, ignoring.", eventType);
        }
    }
}
