package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The account list says when a card's amount owed is not known: a card whose only statements gave
 * no closing balance, so its figure was summed from zero. Design:
 * docs/superpowers/specs/2026-10-26-card-amount-owed-prompt-design.md.
 */
class AmountOwedKnownIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    /** Same cleanup, for the same reason, as ImportAccountBalanceIT.removeQueuedLearningEvents. */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private UUID user() {
        User user = new User();
        user.setEmail("amount-owed-known-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Amount Owed IT User");
        user.setPhoneVerified(true);
        UUID id = userRepository.save(user).getId();
        createdUserIds.add(id);
        return id;
    }

    private UUID account(UUID userId, Account.Type type) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Amount Owed IT " + type);
        account.setAccountType(type);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account).getId();
    }

    private ConfirmedRow row(LocalDate date, String description, String amount, String type) {
        return new ConfirmedRow(date, description, new BigDecimal(amount), type,
                "Other", true, "rule", null, false, null, null, false);
    }

    private UUID importWithoutClosing(UUID userId, UUID accountId) throws Exception {
        LocalDate start = LocalDate.of(2025, 4, 1);
        importService.confirm(userId, new MockMultipartFile("file", "card.csv", "text/csv",
                        "irrelevant".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null, List.of(
                        row(start.plusDays(2), "PAYMENT RECEIVED", "2000.00", "INCOME"),
                        row(start.plusDays(5), "GROCERIES", "1000.00", "EXPENSE"),
                        row(start.plusDays(9), "FUEL", "500.00", "EXPENSE")),
                        accountId, null, null, null, null, start, start.plusMonths(1).minusDays(1)));
        return statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId).get(0).getId();
    }

    private AccountDto listed(UUID userId, UUID accountId) {
        return accountService.listForUser(userId).stream()
                .filter(a -> a.id().equals(accountId)).findFirst().orElseThrow();
    }

    @Test
    void aCardSummedFromZeroIsNotKnown_andDeletingItsOnlyStatementMakesItKnownAgain() throws Exception {
        UUID user = user();
        UUID card = account(user, Account.Type.CREDIT_CARD);
        UUID savings = account(user, Account.Type.SAVINGS);
        assertThat(listed(user, card).amountOwedKnown()).as("made by hand, no statements").isTrue();

        UUID statement = importWithoutClosing(user, card);
        importWithoutClosing(user, savings);

        assertThat(listed(user, card).amountOwedKnown()).isFalse();
        assertThat(listed(user, savings).amountOwedKnown()).isTrue();

        statementImportService.delete(user, statement, user);
        assertThat(listed(user, card).amountOwedKnown()).as("no statements left").isTrue();
    }

    /** What the review screen asks on: a card nothing has stated a figure for. A card made by hand
     *  at zero is known (no statements to doubt) but not stated, so its first statement with no
     *  closing balance must be asked about -- that import is what makes it summed from zero. */
    @Test
    void balanceStated_saysWhetherAnythingEverStatedTheFigure() throws Exception {
        UUID user = user();
        UUID handMadeAtZero = account(user, Account.Type.CREDIT_CARD);
        assertThat(listed(user, handMadeAtZero).amountOwedKnown()).isTrue();
        assertThat(listed(user, handMadeAtZero).balanceStated()).isFalse();

        UUID typedAtCreation = accountService.create(user, new AccountDto.CreateRequest("Typed card", "CREDIT_CARD",
                new BigDecimal("900.00"), null, null, null, null, null, null, null, null), user).id();
        assertThat(listed(user, typedAtCreation).balanceStated()).isTrue();

        importWithoutClosing(user, handMadeAtZero);
        assertThat(listed(user, handMadeAtZero).balanceStated()).isFalse();

        AccountDto typed = accountService.update(user, handMadeAtZero, new AccountDto.CreateRequest("Card",
                "CREDIT_CARD", new BigDecimal("0.00"), null, null, null, null, null, null, null, null), user);
        assertThat(typed.balanceStated()).as("an edit to zero is a stated zero").isTrue();
        assertThat(listed(user, handMadeAtZero).balanceStated()).isTrue();
        assertThat(listed(user, handMadeAtZero).amountOwedKnown()).isTrue();
    }

    @Test
    void typingTheBalanceMakesItKnown_butARenameDoesNot() throws Exception {
        UUID user = user();
        UUID card = account(user, Account.Type.CREDIT_CARD);
        importWithoutClosing(user, card);

        AccountDto renamed = accountService.update(user, card, new AccountDto.CreateRequest("Renamed card",
                "CREDIT_CARD", null, null, null, null, null, null, null, null, null), user);
        assertThat(renamed.amountOwedKnown()).isFalse();
        assertThat(accountRepository.findById(card).orElseThrow().getBalanceTypedAt()).isNull();

        AccountDto typed = accountService.update(user, card, new AccountDto.CreateRequest("Renamed card",
                "CREDIT_CARD", new BigDecimal("4200.00"), null, null, null, null, null, null, null, null), user);
        assertThat(typed.amountOwedKnown()).isTrue();
        assertThat(listed(user, card).amountOwedKnown()).isTrue();
    }
}
