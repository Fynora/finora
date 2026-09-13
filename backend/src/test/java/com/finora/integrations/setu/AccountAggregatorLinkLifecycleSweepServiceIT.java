package com.finora.integrations.setu;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccountAggregatorLinkLifecycleSweepServiceTest} proves the sweep's decision logic against
 * mocks; this proves the same transitions actually persist through a real Postgres transaction --
 * the same discipline Plan 4's own audit-rollback finding required (see
 * AccountAggregatorGuardIT), and explicitly called for by this plan's own "After Task 9" section:
 * the sweep mutates two tables (account_aggregator_links and accounts) in one pass, exactly the
 * shape that class of bug lives in.
 */
class AccountAggregatorLinkLifecycleSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountAggregatorLinkRepository links;
    @Autowired private AccountAggregatorLinkLifecycleSweepService sweepService;

    private User createUser() {
        User user = new User();
        user.setEmail("aa-sweep-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AA Sweep IT Test User");
        return userRepository.save(user);
    }

    private Account createAccount(UUID userId, Account.PrimarySource primarySource) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Linked Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setPrimarySource(primarySource);
        return accountRepository.save(account);
    }

    @Test
    void pausesAnActiveLinkAndRevertsPrimarySource_whenTheUserHasNoEntitlement() {
        // A brand-new user with zero subscription rows -- EntitlementService.hasEntitlement's own
        // "no row = false" contract (see EntitlementControllerIT), the same real-world shape as a
        // downgrade: no ACCOUNT_AGGREGATOR_SYNC entitlement either way.
        User user = createUser();
        Account account = createAccount(user.getId(), Account.PrimarySource.ACCOUNT_AGGREGATOR);

        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(user.getId());
        link.setAccountId(account.getId());
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLinkIdempotencyKey("aa-sweep-it-" + UUID.randomUUID());
        links.save(link);

        int changed = sweepService.sweep();

        assertThat(changed).isGreaterThanOrEqualTo(1);
        AccountAggregatorLink reloadedLink = links.findById(link.getId()).orElseThrow();
        assertThat(reloadedLink.getStatus()).isEqualTo(AccountAggregatorLinkStatus.PAUSED);
        Account reloadedAccount = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloadedAccount.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
    }

    @Test
    void expiresAnActiveLinkPastItsConsentExpiry() {
        User user = createUser();
        Account account = createAccount(user.getId(), Account.PrimarySource.ACCOUNT_AGGREGATOR);

        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(user.getId());
        link.setAccountId(account.getId());
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setConsentExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        link.setLinkIdempotencyKey("aa-sweep-it-" + UUID.randomUUID());
        links.save(link);

        int changed = sweepService.sweep();

        assertThat(changed).isGreaterThanOrEqualTo(1);
        AccountAggregatorLink reloadedLink = links.findById(link.getId()).orElseThrow();
        assertThat(reloadedLink.getStatus()).isEqualTo(AccountAggregatorLinkStatus.EXPIRED);
    }
}
