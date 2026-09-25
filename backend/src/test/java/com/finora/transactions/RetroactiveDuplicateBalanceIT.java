package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
 * BH-003 for a mark written AFTER the import that inserted the row.
 *
 * <p>The confirm-time reversal only ever saw its own batch. A row that became a duplicate later --
 * here, because the user corrected its narration to match an earlier statement's row, which re-runs
 * reconciliation -- was marked, excluded from every total, and left in {@code Account.balance} for
 * good. The reversal now belongs to the reconciliation run that writes the mark, whichever caller
 * triggered it; and deleting the marked row afterwards must not move the balance a second time.
 *
 * <p>End to end for the same reason as ImportAccountBalanceIT: the defect is three services'
 * balance writes disagreeing, which a mock of any one of them cannot show.
 */
class RetroactiveDuplicateBalanceIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    /** Same cleanup, same reason, as ImportAccountBalanceIT's own @AfterEach. */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private record Fixture(User user, Account account) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("retroactive-dup-balance-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Retroactive Duplicate Balance IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Retroactive Duplicate Balance IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("1000.00"));
        return new Fixture(savedUser, accountRepository.save(account));
    }

    private ConfirmedRow row(String description, String amount) {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal(amount), "EXPENSE",
                "Other", true, "rule", null, false, null, null, false);
    }

    private void importRows(Fixture f, String fileName, ConfirmedRow... rows) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "text/csv",
                fileName.getBytes(StandardCharsets.UTF_8));
        importService.confirm(f.user().getId(), file,
                new ConfirmRequest(null, List.of(rows), f.account().getId(), null, null, null, null));
    }

    private BigDecimal balanceOf(Fixture f) {
        return accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
    }

    private Transaction only(Fixture f, String description) {
        return transactionRepository.findByUserId(f.user().getId()).stream()
                .filter(t -> description.equals(t.getDescription())).findFirst().orElseThrow();
    }

    @Test
    void aRowThatBecomesADuplicateAfterAnEdit_comesBackOffTheBalance_andItsDeleteDoesNotMoveItAgain() throws Exception {
        Fixture f = fixture();

        importRows(f, "june.csv", row("METRO FARE", "45.00"));
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");

        // A second statement whose row is the same fare under a narration the engine cannot match
        // at confirm time (no running balance to key on either), so both are counted.
        importRows(f, "june-again.csv", row("MTR FARE TYPO", "45.00"));
        assertThat(balanceOf(f)).isEqualByComparingTo("910.00");
        Transaction typo = only(f, "MTR FARE TYPO");
        assertThat(typo.getIsDuplicateOf()).isNull();
        UUID originalId = only(f, "METRO FARE").getId();

        // The user corrects the narration. update() re-runs reconciliation, which now sees two
        // identical rows from two imports and marks the later one.
        transactionService.update(f.user().getId(), typo.getId(),
                new TransactionDto.UpdateRequest(null, "METRO FARE", null, null, null, null, null, null));
        Transaction marked = transactionRepository.findById(typo.getId()).orElseThrow();
        assertThat(marked.getIsDuplicateOf()).isEqualTo(originalId);
        // Its 45.00 is no longer counted, so it is no longer in the balance either.
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");

        // Deleting the marked row removes nothing from the balance: its contribution already left.
        transactionService.delete(f.user().getId(), marked.getId(), f.user().getId());
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
    }

    @Test
    void deletingTheCanonicalRow_putsTheUnmarkedSurvivorsContributionBack() throws Exception {
        Fixture f = fixture();

        importRows(f, "june.csv", row("METRO FARE", "45.00"));
        importRows(f, "june-again.csv", row("METRO FARE", "45.00"));
        // The second copy is marked at its own confirm and reversed: one fare in the balance.
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
        List<Transaction> fares = transactionRepository.findByUserId(f.user().getId());
        Transaction canonical = fares.stream().filter(t -> t.getIsDuplicateOf() == null).findFirst().orElseThrow();
        Transaction copy = fares.stream().filter(t -> t.getIsDuplicateOf() != null).findFirst().orElseThrow();

        transactionService.delete(f.user().getId(), canonical.getId(), f.user().getId());

        // The canonical's 45.00 came off; the survivor is counted again, so its 45.00 went back on.
        // The ledger holds one real fare and the balance reflects one.
        assertThat(transactionRepository.findById(copy.getId()).orElseThrow().getIsDuplicateOf()).isNull();
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
    }
}
