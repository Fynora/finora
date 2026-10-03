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
 * "This is my account" (ConfirmRequest.holderIsMine) through the real confirm against a real
 * database: the profile name must be on the account once the import's own later writes to that
 * account (its balance, among others) have run in the same transaction.
 */
class HolderIsMineConfirmIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionService subscriptionService;

    private User user() {
        User user = new User();
        user.setEmail("holder-is-mine-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Ravi Kumar"); // synthetic-ok
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(saved.getId());
        return saved;
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    private static List<ConfirmedRow> rows() {
        return List.of(new ConfirmedRow(LocalDate.of(2026, 7, 10), "Salary", new BigDecimal("50000.00"),
                "INCOME", "Income", true, "file", null, false, null, null));
    }

    private static ConfirmRequest request(UUID existingAccountId, NewAccountRequest newAccount, Boolean holderIsMine) {
        return new ConfirmRequest(null, rows(), existingAccountId, newAccount, null, null, null,
                null, null, null, null, true, null, holderIsMine);
    }

    @Test
    void aNewAccountClaimedAsMineCarriesTheProfileName() throws Exception {
        User user = user();
        NewAccountRequest newAccount = new NewAccountRequest("Sample Card", "SAVINGS", BigDecimal.ZERO, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        importService.confirm(user.getId(), file(), request(null, newAccount, true));

        List<Account> accounts = accountRepository.findByUserId(user.getId());
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).getAccountHolderName()).isEqualTo("Ravi Kumar"); // synthetic-ok
    }

    @Test
    void anExistingAccountClaimedAsMineReplacesThePrintedHolder_continueAnywayKeepsIt() throws Exception {
        User user = user();
        Account mine = new Account();
        mine.setUserId(user.getId());
        mine.setName("Claimed");
        mine.setAccountType(Account.Type.SAVINGS);
        mine.setBalance(BigDecimal.ZERO);
        mine.setAccountHolderName("Sunil Verma"); // synthetic-ok
        mine = accountRepository.save(mine);
        Account theirs = new Account();
        theirs.setUserId(user.getId());
        theirs.setName("Kept");
        theirs.setAccountType(Account.Type.SAVINGS);
        theirs.setBalance(BigDecimal.ZERO);
        theirs.setAccountHolderName("Sunil Verma"); // synthetic-ok
        theirs = accountRepository.save(theirs);

        importService.confirm(user.getId(), file(), request(mine.getId(), null, true));
        importService.confirm(user.getId(), file(), request(theirs.getId(), null, null));

        assertThat(accountRepository.findById(mine.getId()).orElseThrow().getAccountHolderName())
                .isEqualTo("Ravi Kumar"); // synthetic-ok
        assertThat(accountRepository.findById(theirs.getId()).orElseThrow().getAccountHolderName())
                .isEqualTo("Sunil Verma"); // synthetic-ok
    }
}
