package com.finora.service;

import com.finora.dto.InsightsDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.SenderInflowRuleRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A credit card's bill is a whole month of card purchases paid at once, so Insights never calls it
 * a spending spike, yet it still counts as spending (Sid, 2026-10-16). A bill with no card statement
 * to link it to is reported as {@code unlinkedCardBills}, so the app can ask for that statement.
 * Design: docs/superpowers/specs/2026-10-16-mobile-insights-redesign-design.md. Values synthetic.
 */
class InsightsServiceCardBillTest {

    private static final String CRED_BILL = "UPI-CREDCLUB-CREDCLUB@ICICI-XXXX0000114-"; // synthetic-ok

    private TransactionRepository transactionRepository;
    private TransactionGraphService transactionGraphService;
    private InsightsService insightsService;
    private final UUID userId = UUID.randomUUID();
    private Category dining;
    private Category cardBill;
    private Category other;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        BudgetRepository budgetRepository = mock(BudgetRepository.class);
        StatementImportRepository statementImportRepository = mock(StatementImportRepository.class);
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());
        transactionGraphService = mock(TransactionGraphService.class);
        when(transactionGraphService.ccPaymentFromTransactionIds(any())).thenReturn(Set.of());

        Account liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        liveAccount.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount));
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(any(), any())).thenReturn(List.of());

        insightsService = new InsightsService(transactionRepository, accountRepository, categoryRepository, budgetRepository,
                mock(UserRepository.class), transactionGraphService, statementImportRepository,
                new InflowChoiceService(mock(InflowKindRepository.class), mock(SenderInflowRuleRepository.class)));

        dining = category("Dining");
        cardBill = category("Credit Card bill");
        other = category("Other");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(dining, cardBill, other));
    }

    private Category category(String name) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setUserId(userId);
        c.setName(name);
        return c;
    }

    private Transaction expense(LocalDate date, long amount, Category category, String description) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setCategoryId(category.getId());
        t.setTxnDate(date);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setMerchant(description);
        t.setDescription(description);
        return t;
    }

    /** One small bill a month in May-July, a large one in August, and steady dining throughout. */
    private List<Transaction> billJumpsInAugust(Category billCategory, String billDescription) {
        List<Transaction> txns = new ArrayList<>();
        for (int month = 5; month <= 8; month++) {
            txns.add(expense(LocalDate.of(2026, month, 3), 1000, dining, "Cafe"));
            txns.add(expense(LocalDate.of(2026, month, 20), month == 8 ? 30000 : 500, billCategory, billDescription));
        }
        return txns;
    }

    private void givenTransactions(List<Transaction> txns) {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(txns);
    }

    @Test
    void cardBillCategory_isNeverAMover() {
        givenTransactions(billJumpsInAugust(cardBill, "SAMPLE CARD PAYMENT"));

        InsightsDto result = insightsService.build(userId);

        assertThat(result.movers()).extracting(InsightsDto.CategoryMover::category).doesNotContain("Credit Card bill");
        assertThat(result.sentences()).noneMatch(s -> s.contains("Credit Card bill spend was") || s.contains("budget for Credit Card bill"));
    }

    @Test
    void cardBillByDescription_isNeverAMover_evenUnderOther() {
        givenTransactions(billJumpsInAugust(other, CRED_BILL));

        InsightsDto result = insightsService.build(userId);

        assertThat(result.movers()).extracting(InsightsDto.CategoryMover::category).doesNotContain("Other");
        assertThat(result.sentences()).noneMatch(s -> s.contains("Other spend was"));
    }

    @Test
    void cardBills_stayInTheTotalSentence() {
        givenTransactions(billJumpsInAugust(cardBill, "SAMPLE CARD PAYMENT"));

        InsightsDto result = insightsService.build(userId);

        assertThat(result.sentences()).anyMatch(s -> s.contains("total spend was ₹31,000"));
    }

    @Test
    void unlinkedCardBills_sumsTheMonthsCardBills_byCategoryOrDescription() {
        List<Transaction> txns = new ArrayList<>(billJumpsInAugust(cardBill, "SAMPLE CARD PAYMENT"));
        txns.add(expense(LocalDate.of(2026, 8, 25), 2000, other, CRED_BILL));
        givenTransactions(txns);

        InsightsDto.UnlinkedCardBills bills = insightsService.build(userId).unlinkedCardBills();

        assertThat(bills).isNotNull();
        assertThat(bills.month()).isEqualTo("2026-08");
        assertThat(bills.total()).isEqualByComparingTo("32000");
        assertThat(bills.count()).isEqualTo(2);
    }

    @Test
    void unlinkedCardBills_nullWithoutCardBills() {
        givenTransactions(List.of(expense(LocalDate.of(2026, 8, 3), 1000, dining, "Cafe")));

        assertThat(insightsService.build(userId).unlinkedCardBills()).isNull();
    }

    @Test
    void linkedCardPayment_isNotUnlinked() {
        Transaction bill = expense(LocalDate.of(2026, 8, 20), 30000, cardBill, "SAMPLE CARD PAYMENT");
        givenTransactions(List.of(expense(LocalDate.of(2026, 8, 3), 1000, dining, "Cafe"), bill));
        when(transactionGraphService.ccPaymentFromTransactionIds(any())).thenReturn(Set.of(bill.getId()));

        assertThat(insightsService.build(userId).unlinkedCardBills()).isNull();
    }
}
