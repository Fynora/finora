package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether an outflow is excluded from spend as {@code INVESTMENT_TRANSFER} follows the row's
 * CATEGORY, in both directions, through the real services and a real database.
 *
 * <p>Two things here cannot be shown against mocks. First, that the category edit endpoints
 * actually trigger the reconciliation pass -- before this, {@code updateCategory} and
 * {@code bulkRecategorize} never called it, so a row moved into or out of Investments kept whatever
 * status it had until some unrelated later write happened to re-run reconciliation. Second, that
 * the status the pass writes is what a fresh read of the row returns.
 *
 * <p>The narration used throughout is deliberately one CategoryRules does not recognise, so the
 * category is the only thing that can be driving the outcome.
 */
class InvestmentExclusionFollowsCategoryIT extends AbstractIntegrationTest {

    private static final String UNKNOWN_BROKER = "UPI-SOME NEW WEALTH APP-REF778899";

    @Autowired private TransactionService transactionService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private record Fixture(User user, Account account) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("investment-exclusion-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Investment Exclusion IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Investment Exclusion IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("500000.00"));
        return new Fixture(savedUser, accountRepository.save(account));
    }

    private TransactionDto add(Fixture f, String category, String description, String type, String amount) {
        return transactionService.create(f.user().getId(), new TransactionDto.CreateRequest(
                f.account().getId(), category, LocalDate.of(2026, 7, 10), description,
                new BigDecimal(amount), type, null));
    }

    private Transaction reload(UUID id) {
        return transactionRepository.findById(id).orElseThrow();
    }

    @Test
    void anOutflowFiledUnderInvestments_isExcluded_evenWhenNoKeywordMatches() {
        Fixture f = fixture();

        TransactionDto created = add(f, "Investments", UNKNOWN_BROKER, "EXPENSE", "7000.00");

        assertThat(reload(created.id()).getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
    }

    @Test
    void anOutflowWithBrokerKeywords_butFiledElsewhere_isSpend() {
        Fixture f = fixture();

        TransactionDto created = add(f, "Groceries", "UPI-GROWW INVEST TECH", "EXPENSE", "3000.00");

        assertThat(reload(created.id()).getReconciliationStatus())
                .as("no keyword fallback: the category is the decision")
                .isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    void anIncomeRowInInvestments_isNeverExcluded() {
        Fixture f = fixture();

        TransactionDto redemption = add(f, "Investments", "REDEMPTION PROCEEDS REF5521", "INCOME", "9000.00");

        assertThat(reload(redemption.id()).getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    void recategorizingAnExcludedRowAwayFromInvestments_returnsItToSpend_andBackAgain() {
        Fixture f = fixture();
        TransactionDto created = add(f, "Investments", UNKNOWN_BROKER, "EXPENSE", "7000.00");
        assertThat(reload(created.id()).getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);

        transactionService.updateCategory(f.user().getId(), created.id(), "Groceries");
        Transaction spend = reload(created.id());
        assertThat(spend.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(spend.getReconciliationExplanation()).isNull();

        transactionService.updateCategory(f.user().getId(), created.id(), "Investments");
        assertThat(reload(created.id()).getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
    }

    @Test
    void bulkRecategorizing_movesEveryRowInAndOutOfTheExclusion() {
        Fixture f = fixture();
        TransactionDto a = add(f, "Shopping", UNKNOWN_BROKER + "-A", "EXPENSE", "1000.00");
        TransactionDto b = add(f, "Shopping", UNKNOWN_BROKER + "-B", "EXPENSE", "2000.00");
        List<UUID> ids = List.of(a.id(), b.id());
        assertThat(ids).allSatisfy(id ->
                assertThat(reload(id).getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK));

        transactionService.bulkRecategorize(f.user().getId(), ids, "Investments", f.user().getId());
        assertThat(ids).allSatisfy(id ->
                assertThat(reload(id).getReconciliationStatus())
                        .isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER));

        transactionService.bulkRecategorize(f.user().getId(), ids, "Shopping", f.user().getId());
        assertThat(ids).allSatisfy(id ->
                assertThat(reload(id).getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK));
    }

    @Test
    void recategorizingBetweenTwoOrdinaryCategories_leavesAnUnrelatedExcludedRowAlone() {
        Fixture f = fixture();
        TransactionDto excluded = add(f, "Investments", UNKNOWN_BROKER, "EXPENSE", "7000.00");
        TransactionDto other = add(f, "Shopping", "BOOK STORE", "EXPENSE", "400.00");

        transactionService.updateCategory(f.user().getId(), other.id(), "Groceries");

        assertThat(reload(excluded.id()).getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        assertThat(reload(other.id()).getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    void anotherUsersInvestmentsRow_isNotAffected() {
        Fixture mine = fixture();
        Fixture theirs = fixture();
        TransactionDto theirRow = add(theirs, "Investments", UNKNOWN_BROKER, "EXPENSE", "7000.00");
        TransactionDto myRow = add(mine, "Investments", UNKNOWN_BROKER, "EXPENSE", "7000.00");

        transactionService.updateCategory(mine.user().getId(), myRow.id(), "Groceries");

        assertThat(reload(myRow.id()).getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(reload(theirRow.id()).getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
    }
}
