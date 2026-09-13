package com.finora.integrations.setu;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccountAggregatorLinkManagementServiceTest} proves the transition's decision logic
 * against mocks; this proves the same transition still fires correctly through the REAL webhook
 * path after Task 4's refactor moved its implementation out of
 * AccountAggregatorWebhookDispatcher -- a regression test for the refactor itself, not a new
 * requirement.
 */
class AccountAggregatorLinkManagementServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountAggregatorLinkRepository links;
    @Autowired private AccountAggregatorWebhookDispatcher dispatcher;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("aa-mgmt-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AA Management Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Linked Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountId = accountRepository.save(account).getId();
    }

    @Test
    void theWebhookPathStillRevokesAndRevertsPrimarySourceAfterTheRefactor() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLinkIdempotencyKey("aa-mgmt-it-" + UUID.randomUUID());
        link.setConsentHandleId("handle-" + UUID.randomUUID());
        links.save(link);

        dispatcher.dispatch("consent.revoked", link.getConsentHandleId());

        AccountAggregatorLink reloaded = links.findById(link.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REVOKED);
        Account reloadedAccount = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloadedAccount.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
    }
}
