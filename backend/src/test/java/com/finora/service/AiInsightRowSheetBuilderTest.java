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
import com.finora.service.AiInsightFacts.FactSheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
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
 * AI insights foundation §4.2: the "Why?" row sheet, built with a real InsightsService over mocked
 * repositories so every figure is checked against the mover InsightsService itself computes. Every
 * name and amount is invented. July 2026 is the month described; April-June are its usual months.
 */
class AiInsightRowSheetBuilderTest {

    private final UUID userId = UUID.randomUUID();
    private TransactionRepository transactionRepository;
    private InsightsService insightsService;
    private AiInsightRowSheetBuilder builder;
    private Category dining;
    private Category userGym;
    private final List<Transaction> txns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        BudgetRepository budgetRepository = mock(BudgetRepository.class);
        StatementImportRepository statementImportRepository = mock(StatementImportRepository.class);
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());
        TransactionGraphService graph = mock(TransactionGraphService.class);
        when(graph.ccPaymentFromTransactionIds(any())).thenReturn(Set.of());
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account));
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(any(), any())).thenReturn(List.of());
        insightsService = new InsightsService(transactionRepository, accountRepository, categoryRepository, budgetRepository,
                mock(UserRepository.class), graph, statementImportRepository,
                new InflowChoiceService(mock(InflowKindRepository.class), mock(SenderInflowRuleRepository.class)));

        dining = new Category();
        ReflectionTestUtils.setField(dining, "id", UUID.randomUUID());
        dining.setUserId(userId);
        dining.setName("Dining");
        dining.setSystem(true);
        userGym = new Category();
        ReflectionTestUtils.setField(userGym, "id", UUID.randomUUID());
        userGym.setUserId(userId);
        userGym.setName("Gym with Friend K");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(dining, userGym));
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(txns);

        builder = new AiInsightRowSheetBuilder(insightsService);
    }

    private void spend(LocalDate date, int amount, Category c, String key) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setCategoryId(c.getId());
        t.setTxnDate(date);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setMerchant(key);
        t.setDescription(key);
        t.setCounterpartyKey(key);
        txns.add(t);
    }

    /** Dining: 2 payments of 500 in each of April-June (usual 1,000 a month, 2 payments); July: 6
     *  payments, 4 of them on Saturdays, one to a payee never seen before. */
    private void diningRunningHigh() {
        for (int m = 4; m <= 6; m++) {
            spend(LocalDate.of(2026, m, 6), 500, dining, "vpa:cafe.sample");
            spend(LocalDate.of(2026, m, 20), 500, dining, "vpa:cafe.sample");
        }
        spend(LocalDate.of(2026, 7, 4), 900, dining, "vpa:cafe.sample");   // Saturday
        spend(LocalDate.of(2026, 7, 11), 700, dining, "vpa:cafe.sample");  // Saturday
        spend(LocalDate.of(2026, 7, 18), 400, dining, "vpa:cafe.sample");  // Saturday
        spend(LocalDate.of(2026, 7, 25), 600, dining, "vpa:cafe.sample");  // Saturday
        spend(LocalDate.of(2026, 7, 10), 300, dining, "vpa:cafe.sample");  // Friday
        spend(LocalDate.of(2026, 7, 14), 2400, dining, "vpa:newplace.n"); // Tuesday, a new payee
    }

    @Test
    void paymentsTotalEqualsTheMover() {
        diningRunningHigh();
        InsightsDto.CategoryMover mover = insightsService.build(userId, "2026-07").movers().stream()
                .filter(m -> m.category().equals("Dining")).findFirst().orElseThrow();

        FactSheet sheet = builder.build(userId, "2026-07", "Dining").orElseThrow();

        assertThat(sheet.fact("dining.this_month").value()).isEqualByComparingTo(mover.current()).isEqualByComparingTo("5300");
        assertThat(sheet.fact("dining.usual").value()).isEqualByComparingTo(mover.priorAverage()).isEqualByComparingTo("1000");
        assertThat(sheet.fact("dining.payments_this_month").value()).isEqualByComparingTo("6");
        assertThat(sheet.fact("dining.payments_usual").value()).isEqualByComparingTo("2");
        assertThat(sheet.fact("dining.payments_usual").display()).isEqualTo("2 payments in a usual month");
        assertThat(sheet.fact("dining.avg_payment_usual").value()).isEqualByComparingTo("500");
        assertThat(sheet.fact("dining.largest_1").value()).isEqualByComparingTo("2400");
        assertThat(sheet.fact("dining.largest_1_day").display()).isEqualTo("14th");
        assertThat(sheet.fact("dining.largest_2").value()).isEqualByComparingTo("900");
        assertThat(sheet.fact("dining.largest_3").value()).isEqualByComparingTo("700");
        assertThat(sheet.fact("dining.new_payees_count").value()).isEqualByComparingTo("1");
        assertThat(sheet.fact("dining.new_payees_total").value()).isEqualByComparingTo("2400");
        assertThat(sheet.month()).isEqualTo("2026-07");
    }

    @Test
    void weekendIsTheTransactionsOwnDate() {
        diningRunningHigh();
        assertThat(LocalDate.of(2026, 7, 10).getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);

        FactSheet sheet = builder.build(userId, "2026-07", "Dining").orElseThrow();

        // The four Saturdays; the Friday payment is not a weekend one. April-June: 6th Apr (Mon),
        // 20th Apr (Mon), 6th May (Wed), 20th May (Wed), 6th Jun (Sat), 20th Jun (Sat) -> 2 over 3 months.
        assertThat(sheet.fact("dining.weekend_payments_this_month").value()).isEqualByComparingTo("4");
        assertThat(sheet.fact("dining.weekend_payments_usual").value()).isEqualByComparingTo("1");
    }

    @Test
    void weakBaselineGivesNoSheet() {
        spend(LocalDate.of(2026, 6, 6), 1000, userGym, "vpa:gym.sample");
        spend(LocalDate.of(2026, 7, 6), 3000, userGym, "vpa:gym.sample");
        assertThat(builder.build(userId, "2026-07", "Gym with Friend K")).isEmpty();
    }

    @Test
    void aCategoryThatIsNotAMoverGivesNoSheet() {
        diningRunningHigh();
        assertThat(builder.build(userId, "2026-07", "Travel")).isEmpty();
    }

    @Test
    void noPayeeNameInModelJson() {
        for (int m = 4; m <= 6; m++) spend(LocalDate.of(2026, m, 6), 1000, userGym, "vpa:gym.sample");
        spend(LocalDate.of(2026, 7, 6), 3000, userGym, "vpa:flatmate.a");

        FactSheet sheet = builder.build(userId, "2026-07", "Gym with Friend K").orElseThrow();
        String json = sheet.modelJson();

        assertThat(json).doesNotContain("vpa:").doesNotContain("flatmate").doesNotContain("Friend K")
                .contains("[category-1]");
        assertThat(sheet.subject("category_1").displayName()).isEqualTo("Gym with Friend K");
        assertThat(sheet.rows()).isEmpty();
    }

    @Test
    void aGatewayKeyIsNeverANewPayee() {
        for (int m = 4; m <= 6; m++) spend(LocalDate.of(2026, m, 6), 1000, dining, "vpa:cafe.sample");
        spend(LocalDate.of(2026, 7, 6), 3000, dining, "vpa:pg.razorpay");

        FactSheet sheet = builder.build(userId, "2026-07", "Dining").orElseThrow();

        assertThat(sheet.fact("dining.new_payees_count")).isNull();
    }
}
