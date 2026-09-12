package com.finora.integrations.setu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAggregatorWebhookDispatcherTest {

    private AccountAggregatorLinkRepository links;
    private com.finora.service.AuditService auditService;
    private AccountAggregatorWebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        links = mock(AccountAggregatorLinkRepository.class);
        auditService = mock(com.finora.service.AuditService.class);
        dispatcher = new AccountAggregatorWebhookDispatcher(links, auditService, null);
        // The third constructor argument (identity resolution, Task 8) is null here because
        // neither test in this task exercises the consent.approved branch -- Task 8 replaces this
        // constructor call with a real mock once that branch exists.
    }

    @Test
    void consentRejectedMarksTheLinkRejected() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        when(links.findByConsentHandleId("consent-handle-1")).thenReturn(Optional.of(link));

        dispatcher.dispatch("consent.rejected", "consent-handle-1");

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REJECTED);
    }

    @Test
    void consentRevokedMarksTheLinkRevoked() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByConsentHandleId("consent-handle-2")).thenReturn(Optional.of(link));

        dispatcher.dispatch("consent.revoked", "consent-handle-2");

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REVOKED);
    }

    @Test
    void anUnknownConsentHandleIsIgnoredNotThrown() {
        when(links.findByConsentHandleId("unknown")).thenReturn(Optional.empty());

        dispatcher.dispatch("consent.revoked", "unknown");
        // No exception -- a webhook for a consent handle Fynora never recorded (or already
        // deleted) is logged and dropped, not a 500 that makes Setu retry-storm forever.
    }
}
