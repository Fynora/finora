package com.finora.integrations.setu;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AccountAggregatorWebhookDispatcherTest's consentRejectedMarksTheLinkRejected only proves the
 * in-memory entity's setStatus() was called, against a mocked repository -- it would pass even if
 * the transition never persisted. dispatch() isn't @Transactional (unlike
 * RazorpayWebhookDispatcher.dispatch(), which explicitly is, for the self-invocation reason
 * documented on that method), so the read at the top of dispatch() closes its own mini-transaction
 * before returning: the link this method works with is detached, and only branches that call
 * links.save(link) themselves actually persist their transition. This proves the real path: a
 * link persisted in one transaction, dispatch() called, then a FRESH read from the repository.
 */
class AccountAggregatorConsentRejectedIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountAggregatorLinkRepository links;
    @Autowired private AccountAggregatorWebhookDispatcher dispatcher;

    private UUID userId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("aa-consent-rejected-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AA Consent Rejected Test User");
        userId = userRepository.save(user).getId();
    }

    @Test
    void consentRejectedPersistsTheRejectedStatus() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        link.setLinkIdempotencyKey("aa-consent-rejected-it-" + UUID.randomUUID());
        link.setConsentHandleId("handle-" + UUID.randomUUID());
        links.save(link);

        dispatcher.dispatch("consent.rejected", link.getConsentHandleId());

        AccountAggregatorLink reloaded = links.findById(link.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REJECTED);
    }
}
