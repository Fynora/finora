package com.finora.transactions;

import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How waiting rows become a few payee questions: grouping, ranking, the batch cut and the answers
 * offered. Repositories are mocked; the endpoints and the database are QuickSortIT's job. Every
 * narration and id here is invented.
 */
class QuickSortServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private TransactionRepository transactionRepository;
    private CategoryRepository categoryRepository;
    private QuickSortService service;
    private final List<Transaction> waiting = new ArrayList<>();
    private final Map<String, UUID> categoryIds = new HashMap<>();
    private final List<Category> categories = new ArrayList<>();
    private LocalDate nextDate = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(accountId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account));
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenAnswer(inv -> waiting.stream()
                        .sorted(Comparator.comparing(Transaction::getTxnDate).reversed()).toList());
        when(categoryRepository.findByUserId(userId)).thenReturn(categories);
        when(transactionRepository.countManualChoicesByCategory(eq(userId), any(), any())).thenReturn(List.of());
        for (String name : List.of("Other", "Personal Transfer", "Friend Repayment", "Groceries", "Dining",
                "Shopping", "Transport", "Health", "Salary", "Gifts & Donations", "Transfer", "Rent")) {
            category(name);
        }
        service = new QuickSortService(transactionRepository, accountRepository, categoryRepository,
                mock(TransactionService.class), mock(com.finora.observability.QuickSortMetrics.class));
        ReflectionTestUtils.setField(service, "coverageTarget", new BigDecimal("0.80"));
        ReflectionTestUtils.setField(service, "maxQuestions", 10);
    }

    @Test
    void groupsOnePayeeIntoOneQuestionAndRanksByMoney() {
        shop("vpa:samplea", 100); shop("vpa:samplea", 100); shop("vpa:samplea", 100);
        shop("vpa:sampleb", 500);

        QuickSortDto.Batch batch = service.batch(userId, 0);

        assertThat(batch.questions()).extracting(QuickSortDto.Question::total)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("500"), new BigDecimal("300"));
        assertThat(batch.questions().get(1).payments()).isEqualTo(3);
        assertThat(batch.waitingTotal()).isEqualByComparingTo("800");
    }

    @Test
    void aKeyThatNamesNoOnePayeeIsOneQuestionPerRow() {
        shop("cut:samplepay.12", 100);
        shop("cut:samplepay.12", 90);
        shop(null, 80);

        QuickSortDto.Batch batch = service.batch(userId, 0);

        assertThat(batch.questions()).hasSize(3).allSatisfy(q -> assertThat(q.payments()).isEqualTo(1));
    }

    @Test
    void stopsAtTheCoverageTarget() {
        shop("vpa:a", 50); shop("vpa:b", 30); Transaction c = shop("vpa:c", 10); Transaction d = shop("vpa:d", 10);

        QuickSortDto.Batch batch = service.batch(userId, 0);

        assertThat(batch.questions()).hasSize(2);
        assertThat(batch.rest().questions()).isEqualTo(2);
        assertThat(batch.rest().payments()).isEqualTo(2);
        assertThat(batch.rest().amount()).isEqualByComparingTo("20");
        assertThat(batch.rest().transactionIds()).containsExactlyInAnyOrder(c.getId(), d.getId());
    }

    @Test
    void oneRupeeShortOfTheTargetTakesTheNextGroup() {
        shop("vpa:a", 50); shop("vpa:b", 29); shop("vpa:c", 11); shop("vpa:d", 10);

        assertThat(service.batch(userId, 0).questions()).hasSize(3);
    }

    @Test
    void neverMoreThanMaxQuestions() {
        for (int i = 0; i < 12; i++) shop("vpa:payee" + i, 100);

        QuickSortDto.Batch batch = service.batch(userId, 0);

        assertThat(batch.questions()).hasSize(10);
        assertThat(batch.rest().questions()).isEqualTo(2);
    }

    @Test
    void tiesAreOrderedByLatestDateThenId() {
        Transaction older = shop("vpa:older", 100);
        Transaction newer = shop("vpa:newer", 100);
        older.setTxnDate(LocalDate.of(2026, 1, 1));
        newer.setTxnDate(LocalDate.of(2026, 2, 1));

        assertThat(service.batch(userId, 0).questions().get(0).anchorTransactionId()).isEqualTo(newer.getId());
    }

    @Test
    void skipStartsTheBatchAfterTheFirstNGroups() {
        shop("vpa:a", 50); Transaction b = shop("vpa:b", 30); shop("vpa:c", 20);

        QuickSortDto.Batch batch = service.batch(userId, 1);

        // 80% of the 50 left from the skip point needs both remaining groups.
        assertThat(batch.questions()).hasSize(2);
        assertThat(batch.questions().get(0).anchorTransactionId()).isEqualTo(b.getId());
        assertThat(batch.rest().questions()).isZero();
    }

    @Test
    void skipPastTheEndIsAnEmptyBatch() {
        shop("vpa:a", 50);

        QuickSortDto.Batch batch = service.batch(userId, 5);

        assertThat(batch.questions()).isEmpty();
        assertThat(batch.rest().questions()).isZero();
        assertThat(batch.waitingTotal()).isEqualByComparingTo("50");
    }

    @Test
    void personQuestionsOfferNoSpendingCategoryByDefault() {
        person("vpa:ravi", 300);

        QuickSortDto.Question q = service.batch(userId, 0).questions().get(0);

        assertThat(q.kind()).isEqualTo(QuickSortDto.Kind.PERSON_PAID);
        assertThat(q.answers()).containsExactly("Personal Transfer", "Friend Repayment");
    }

    @Test
    void theUsersOwnChoicesComeFirst() {
        person("vpa:ravi", 300);
        TransactionRepository.CategoryCount rent = mock(TransactionRepository.CategoryCount.class);
        when(rent.getCategoryId()).thenReturn(categoryIds.get("Rent"));
        when(rent.getCount()).thenReturn(4L);
        when(transactionRepository.countManualChoicesByCategory(userId, CounterpartyType.PERSON, Transaction.Type.EXPENSE))
                .thenReturn(List.of(rent));

        assertThat(service.batch(userId, 0).questions().get(0).answers())
                .containsExactly("Rent", "Personal Transfer", "Friend Repayment");
    }

    @Test
    void onlyCategoriesTheUserHasAreOffered() {
        categories.removeIf(c -> c.getName().equals("Dining"));
        shop("vpa:a", 100);

        List<String> answers = service.batch(userId, 0).questions().get(0).answers();

        assertThat(answers).doesNotContain("Dining").hasSizeLessThanOrEqualTo(5)
                .containsExactly("Groceries", "Shopping", "Transport", "Health");
    }

    @Test
    void aRowWithAGuessIsAGuessQuestionWithItsCategoryFirst() {
        Transaction t = shop("vpa:a", 100);
        t.setDecisionSource(Transaction.DecisionSource.SHARED_CORPUS);
        t.setCategoryId(categoryIds.get("Groceries"));

        QuickSortDto.Question q = service.batch(userId, 0).questions().get(0);

        assertThat(q.kind()).isEqualTo(QuickSortDto.Kind.GUESS);
        assertThat(q.currentCategory()).isEqualTo("Groceries");
        assertThat(q.answers().get(0)).isEqualTo("Groceries");
        assertThat(q.answers()).doesNotHaveDuplicates();
    }

    @Test
    void incomeIsMoneyIn() {
        Transaction t = shop("vpa:a", 100);
        t.setTxnType(Transaction.Type.INCOME);

        QuickSortDto.Question q = service.batch(userId, 0).questions().get(0);

        assertThat(q.kind()).isEqualTo(QuickSortDto.Kind.MONEY_IN);
        assertThat(q.answers()).containsExactly("Friend Repayment", "Salary", "Gifts & Donations", "Transfer");
    }

    @Test
    void aSinglePaymentOfFiveThousandIsALargeOneOff() {
        shop("vpa:exact", 5000);
        shop("vpa:below", 4999.99);
        shop("vpa:split", 3000); shop("vpa:split", 3000);

        Map<String, Boolean> byPayee = new HashMap<>();
        service.batch(userId, 0).questions().forEach(q -> byPayee.put(q.id(), q.largeOneOff()));

        assertThat(byPayee).containsEntry("vpa:exact|EXPENSE", true)
                .containsEntry("vpa:below|EXPENSE", false)
                .containsEntry("vpa:split|EXPENSE", false);
    }

    @Test
    void nothingWaitingIsAnEmptyBatch() {
        QuickSortDto.Batch batch = service.batch(userId, 0);

        assertThat(batch.questions()).isEmpty();
        assertThat(batch.waitingTotal()).isEqualByComparingTo("0");
        assertThat(batch.rest().questions()).isZero();
        assertThat(batch.rest().transactionIds()).isEmpty();
    }

    @Test
    void duplicatesAreNotAsked() {
        Transaction dup = shop("vpa:a", 100);
        dup.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);

        QuickSortDto.Batch batch = service.batch(userId, 0);

        assertThat(batch.questions()).isEmpty();
        assertThat(batch.rest().transactionIds()).isEmpty();
    }

    @Test
    void thePayeeIsTheReadableNameNotTheKey() {
        Transaction t = shop("vpa:samplestore", 100);
        t.setDescription("UPI/DR/900011112201/SAMPLE ST/HDFC/samplestore/"); // synthetic-ok

        String payee = service.batch(userId, 0).questions().get(0).payee();

        assertThat(payee).isNotBlank().doesNotContain("vpa:").doesNotContain("cut:");
    }

    @Test
    void samplesAreTheLatestThreePayments() {
        for (int i = 0; i < 5; i++) shop("vpa:a", 10);

        assertThat(service.batch(userId, 0).questions().get(0).samples()).hasSize(3);
    }

    private Transaction shop(String key, double amount) {
        return row(key, amount, Transaction.DecisionSource.MERCHANT_DEFAULT, CounterpartyType.BUSINESS);
    }

    private Transaction person(String key, double amount) {
        return row(key, amount, Transaction.DecisionSource.STRUCTURAL_P2P, CounterpartyType.PERSON);
    }

    private Transaction row(String key, double amount, Transaction.DecisionSource source, CounterpartyType type) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(nextDate);
        nextDate = nextDate.minusDays(1);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("UPI/SAMPLE PAYEE/" + (key == null ? "REF" : key) + "/900011112200"); // synthetic-ok
        t.setCounterpartyKey(key);
        t.setCounterpartyType(type);
        t.setDecisionSource(source);
        t.setCategoryId(categoryIds.get(source == Transaction.DecisionSource.STRUCTURAL_P2P ? "Personal Transfer" : "Other"));
        t.setNeedsCategoryReview(true);
        waiting.add(t);
        return t;
    }

    private void category(String name) {
        Category c = new Category();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(c, "id", id);
        c.setUserId(userId);
        c.setName(name);
        categories.add(c);
        categoryIds.put(name, id);
    }
}
