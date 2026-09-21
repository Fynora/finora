package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Investments are not a paid feature: a Free-plan user confirming a fixed-deposit / RD / PPF /
 * mutual-fund statement must get an INVESTMENT account created, through the REAL path
 * ImportService.confirm() takes into AccountService.create, against a real database.
 *
 * <p>Before the Premium gate was removed, AccountService.create threw for every self-service
 * INVESTMENT creation without INVESTMENT_INSIGHTS, and ImportService calls it with the user's own id
 * for both arguments -- so a Free or Plus user was blocked at the very last step of importing such a
 * statement, after reviewing it. A mocked repository cannot show that; this can.
 * {@link AccountLimitDuringConfirmIT} covers the sibling rule for regular accounts.
 */
class InvestmentAccountDuringConfirmIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionService subscriptionService;

    private User createFreeUserWithSavingsAccounts(int savingsAccounts) {
        User user = new User();
        user.setEmail("investment-confirm-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Investment Confirm IT User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(saved.getId());
        for (int i = 0; i < savingsAccounts; i++) {
            Account a = new Account();
            a.setUserId(saved.getId());
            a.setName("Existing Account " + i);
            a.setAccountType(Account.Type.SAVINGS);
            a.setBalance(BigDecimal.ZERO);
            accountRepository.save(a);
        }
        return saved;
    }

    private MockMultipartFile statementFile() {
        return new MockMultipartFile("file", "deposit.csv", "text/csv",
                "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    private ConfirmedRow row() {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), "Interest credited", new BigDecimal("1250.00"),
                "INCOME", "Income", true, "file", null, false, null, null);
    }

    /** A new account whose detected product is a fixed deposit -- ImportService routes that to INVESTMENT. */
    private NewAccountRequest newFixedDeposit() {
        return new NewAccountRequest("Fixed Deposit", "SAVINGS", BigDecimal.ZERO, null, null,
                null, null, null, null, null, "FIXED_DEPOSIT", null, null, null, null, null, null, null, null);
    }

    private long investmentCount(UUID userId) {
        return accountRepository.countByUserIdAndAccountType(userId, Account.Type.INVESTMENT);
    }

    @Test
    void confirmingADepositStatement_onFreePlanWithNoOtherAccounts_createsTheInvestmentAccount() throws Exception {
        User user = createFreeUserWithSavingsAccounts(0);

        importService.confirm(user.getId(), statementFile(),
                new ConfirmRequest(null, List.of(row()), null, newFixedDeposit(), null, null, null));

        assertThat(investmentCount(user.getId())).isEqualTo(1);
    }

    @Test
    void confirmingADepositStatement_onFreePlanAlreadyAtTheTwoAccountCap_stillCreatesTheInvestmentAccount() throws Exception {
        User user = createFreeUserWithSavingsAccounts(2);

        importService.confirm(user.getId(), statementFile(),
                new ConfirmRequest(null, List.of(row()), null, newFixedDeposit(), null, null, null));

        assertThat(investmentCount(user.getId())).isEqualTo(1);
        assertThat(accountRepository.countByUserIdAndAccountTypeNot(user.getId(), Account.Type.INVESTMENT))
                .as("holdings must not have taken a slot from the two real accounts").isEqualTo(2);
    }

    @Test
    void confirmingADepositStatement_onPlus_createsTheInvestmentAccount() throws Exception {
        User user = createFreeUserWithSavingsAccounts(2);
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());

        importService.confirm(user.getId(), statementFile(),
                new ConfirmRequest(null, List.of(row()), null, newFixedDeposit(), null, null, null));

        assertThat(investmentCount(user.getId())).isEqualTo(1);
    }
}
