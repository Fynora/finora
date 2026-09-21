package com.finora.accounts;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two account limits {@link AccountService#create} enforces, against a real database: the
 * Free-plan cap counts every account EXCEPT investment holdings, and holdings have their own ceiling.
 *
 * <p>AccountServiceTest proves the branching with mocked counts. What a mock cannot show is that the
 * two derived count queries ({@code countByUserIdAndAccountType} / {@code ...Not}) mean what their
 * names say against real rows, and that a soft-deleted account frees its slot -- Account carries
 * {@code @SQLRestriction("deleted_at IS NULL")}, and both limits silently depend on it.
 */
class InvestmentHoldingsLimitIT extends AbstractIntegrationTest {

    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionService subscriptionService;

    private User freeUser() {
        User user = new User();
        user.setEmail("holdings-limit-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Holdings Limit IT User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(saved.getId());
        return saved;
    }

    private Account saved(UUID userId, String name, Account.Type type) {
        Account a = new Account();
        a.setUserId(userId);
        a.setName(name);
        a.setAccountType(type);
        a.setBalance(BigDecimal.ZERO);
        return accountRepository.save(a);
    }

    private AccountDto.CreateRequest request(String name, String type) {
        return new AccountDto.CreateRequest(name, type, BigDecimal.TEN, null, null, null, null, null, null, null, null);
    }

    @Test
    void theCountQueries_splitByType_andIgnoreSoftDeletedAccounts() {
        User user = freeUser();
        saved(user.getId(), "Savings", Account.Type.SAVINGS);
        saved(user.getId(), "Card", Account.Type.CREDIT_CARD);
        saved(user.getId(), "Fund A", Account.Type.INVESTMENT);
        Account gone = saved(user.getId(), "Fund B", Account.Type.INVESTMENT);
        saved(user.getId(), "Fund C", Account.Type.INVESTMENT);

        assertThat(accountRepository.countByUserIdAndAccountType(user.getId(), Account.Type.INVESTMENT)).isEqualTo(3);
        assertThat(accountRepository.countByUserIdAndAccountTypeNot(user.getId(), Account.Type.INVESTMENT)).isEqualTo(2);

        accountService.delete(user.getId(), gone.getId(), user.getId());

        assertThat(accountRepository.countByUserIdAndAccountType(user.getId(), Account.Type.INVESTMENT))
                .as("a deleted holding frees its slot").isEqualTo(2);
        assertThat(accountRepository.countByUserIdAndAccountTypeNot(user.getId(), Account.Type.INVESTMENT)).isEqualTo(2);
    }

    @Test
    void aFreeUserAtTheTwoAccountCap_canStillAddHoldings_butNotAThirdAccount() {
        User user = freeUser();
        saved(user.getId(), "Savings", Account.Type.SAVINGS);
        saved(user.getId(), "Card", Account.Type.CREDIT_CARD);

        AccountDto holding = accountService.create(user.getId(), request("Index Fund", "INVESTMENT"), user.getId());
        assertThat(holding.accountType()).isEqualTo("INVESTMENT");

        assertThatThrownBy(() -> accountService.create(user.getId(), request("Third Bank", "SAVINGS"), user.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.ACCOUNT_LIMIT_REACHED);
    }

    @Test
    void theHoldingsCeiling_rejectsTheOneAfterTheLimit_andADeletionReopensASlot() {
        User user = freeUser();
        List<Account> holdings = new ArrayList<>();
        for (int i = 0; i < AccountService.MAX_INVESTMENT_HOLDINGS; i++) {
            holdings.add(saved(user.getId(), "Holding " + i, Account.Type.INVESTMENT));
        }

        assertThatThrownBy(() -> accountService.create(user.getId(), request("One Too Many", "INVESTMENT"), user.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.INVESTMENT_HOLDING_LIMIT_REACHED);
        assertThat(accountRepository.countByUserIdAndAccountType(user.getId(), Account.Type.INVESTMENT))
                .isEqualTo(AccountService.MAX_INVESTMENT_HOLDINGS);

        // A full house of holdings does not stop a Free user adding a real account either.
        assertThat(accountService.create(user.getId(), request("Savings", "SAVINGS"), user.getId()).accountType())
                .isEqualTo("SAVINGS");

        // The support-agent carve-out holds against real rows too.
        UUID admin = UUID.randomUUID();
        assertThat(accountService.create(user.getId(), request("Admin-added Fund", "INVESTMENT"), admin).name())
                .isEqualTo("Admin-added Fund");

        // Delete one holding (the admin-added one is not in this list) and the ceiling has room again
        // -- but the admin-added row now counts, so it is exactly one over: still rejected.
        accountService.delete(user.getId(), holdings.get(0).getId(), user.getId());
        assertThatThrownBy(() -> accountService.create(user.getId(), request("After One Delete", "INVESTMENT"), user.getId()))
                .isInstanceOf(ApiException.class);

        accountService.delete(user.getId(), holdings.get(1).getId(), user.getId());
        assertThat(accountService.create(user.getId(), request("After Two Deletes", "INVESTMENT"), user.getId()).name())
                .isEqualTo("After Two Deletes");
    }
}
