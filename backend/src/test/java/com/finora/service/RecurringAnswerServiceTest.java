package com.finora.service;

import com.finora.dto.ChangedAmountDto;
import com.finora.dto.RecurringDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The recurring-payment answer (docs/superpowers/specs/2026-10-02-recurring-payment-answer-design.md §2). */
class RecurringAnswerServiceTest {

    private static final String LABEL = "sample landlord";

    private RecurringService recurringService;
    private CategoryRuleRepository ruleRepository;
    private CategorizationService categorizationService;
    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private ReconciliationService reconciliationService;
    private AuditService auditService;
    private FeatureFlagService featureFlagService;
    private RecurringAnswerService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final List<Transaction> rows = new ArrayList<>();
    private final Map<String, Category> categories = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        recurringService = mock(RecurringService.class);
        ruleRepository = mock(CategoryRuleRepository.class);
        categorizationService = mock(CategorizationService.class);
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        reconciliationService = mock(ReconciliationService.class);
        auditService = mock(AuditService.class);
        featureFlagService = mock(FeatureFlagService.class);
        service = new RecurringAnswerService(recurringService, ruleRepository, categorizationService,
                transactionRepository, accountRepository, reconciliationService, auditService, featureFlagService);

        when(featureFlagService.isEnabled("RECURRING_DETECTION_ENABLED")).thenReturn(true);
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account));
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(rows);
        when(categorizationService.resolveOrCreateCategory(eq(userId), anyString()))
                .thenAnswer(inv -> category(inv.getArgument(1)));
    }

    private Category category(String name) {
        return categories.computeIfAbsent(name, n -> {
            Category c = new Category();
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            c.setUserId(userId);
            c.setName(n);
            return c;
        });
    }

    private Transaction row(String merchant, Transaction.Type type, String amount, int month) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setMerchant(merchant);
        t.setTxnType(type);
        t.setAmount(new BigDecimal(amount));
        t.setTxnDate(LocalDate.of(2026, month, 3));
        t.setCategoryId(category("Other").getId());
        rows.add(t);
        return t;
    }

    private void detected(String averageAmount) {
        when(recurringService.detectForUser(userId)).thenReturn(List.of(new RecurringDto(LABEL, "Monthly",
                new BigDecimal(averageAmount), 3, LocalDate.of(2026, 7, 3), LocalDate.of(2026, 8, 3),
                "Other", new BigDecimal(averageAmount), null, RecurringDto.QuestionState.NEEDS_ANSWER)));
    }

    private CategoryRule savedRule(String category, String min, String max) {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setUserId(userId);
        r.setScope(CategoryRule.Scope.USER);
        r.setField(CategoryRule.Field.PAYEE);
        r.setOperator(CategoryRule.Operator.EQUALS);
        r.setComparisonValue(LABEL);
        r.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        r.setActionValue(category);
        r.setAmountMin(min == null ? null : new BigDecimal(min));
        r.setAmountMax(max == null ? null : new BigDecimal(max));
        return r;
    }

    /** No saved answer before the call; the inserted rule is found afterwards. */
    private CategoryRule firstAnswerRule() {
        CategoryRule inserted = savedRule("Other", null, null);
        when(ruleRepository.findUserPayeeRule(userId, LABEL)).thenReturn(Optional.empty(), Optional.of(inserted));
        return inserted;
    }

    @Test
    void refusedWhenRecurringDetectionIsSwitchedOff() {
        when(featureFlagService.isEnabled("RECURRING_DETECTION_ENABLED")).thenReturn(false);

        assertThatThrownBy(() -> service.categorize(userId, LABEL, "Rent"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(ruleRepository, never()).insertPayeeRuleIfAbsent(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void refusedForAPayeeWithNoDetectedGroupAndNoSavedAnswer() {
        when(recurringService.detectForUser(userId)).thenReturn(List.of());
        when(ruleRepository.findUserPayeeRule(userId, LABEL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.categorize(userId, LABEL, "Rent"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aFirstAnswer_savesARuleAroundTheGroupAverage() {
        detected("10000.0000");
        for (int m = 5; m <= 7; m++) row(LABEL, Transaction.Type.EXPENSE, "10000", m);
        CategoryRule rule = firstAnswerRule();

        RecurringAnswerService.Result result = service.categorize(userId, LABEL, "Rent");

        verify(ruleRepository).insertPayeeRuleIfAbsent(any(), eq(userId), eq(LABEL), eq("Rent"),
                argThat(min -> min.compareTo(new BigDecimal("7999.00")) == 0),
                argThat(max -> max.compareTo(new BigDecimal("12001.00")) == 0), org.mockito.ArgumentMatchers.anyInt());
        assertThat(rule.getActionValue()).isEqualTo("Rent");
        assertThat(rule.getAmountMin()).isEqualByComparingTo("7999.00");
        assertThat(rule.getAmountMax()).isEqualByComparingTo("12001.00");
        verify(ruleRepository).save(rule);
        assertThat(result.kind()).isEqualTo("FIRST");
        verify(auditService).record(eq(userId), eq("RECURRING_ANSWERED"), eq("CategoryRule"), eq(rule.getId()),
                argThat(m -> "FIRST".equals(m.get("kind")) && "Rent".equals(m.get("category"))));
        verify(recurringService).confirm(userId, LABEL);
    }

    @Test
    void theRangeAlsoCoversEveryPaymentInTheGroup() {
        detected("10166.6667");
        row(LABEL, Transaction.Type.EXPENSE, "9000", 5);
        row(LABEL, Transaction.Type.EXPENSE, "10000", 6);
        row(LABEL, Transaction.Type.EXPENSE, "11500", 7);
        CategoryRule rule = firstAnswerRule();

        service.categorize(userId, LABEL, "Rent");

        assertThat(rule.getAmountMin()).isEqualByComparingTo("8132.34");
        assertThat(rule.getAmountMax()).isEqualByComparingTo("12201.00");
    }

    @Test
    void reFilesOnlyThisPayeesInRangeOutgoingRowsNotSetByHand() {
        detected("10000.0000");
        Transaction a = row(LABEL, Transaction.Type.EXPENSE, "10000", 5);
        Transaction b = row("SAMPLE LANDLORD", Transaction.Type.EXPENSE, "10000", 6);   // label differs only in case
        Transaction manual = row(LABEL, Transaction.Type.EXPENSE, "10000", 7);
        manual.setCategoryManuallySet(true);
        // Outside the detected group (a transfer), so it neither widens the range nor falls in it.
        Transaction outOfRange = row(LABEL, Transaction.Type.EXPENSE, "25000", 8);
        outOfRange.setTransfer(true);
        Transaction incoming = row(LABEL, Transaction.Type.INCOME, "10000", 9);
        Transaction otherPayee = row("sample grocer", Transaction.Type.EXPENSE, "10000", 9);
        CategoryRule rule = firstAnswerRule();
        UUID rent = category("Rent").getId();
        UUID other = category("Other").getId();

        RecurringAnswerService.Result result = service.categorize(userId, LABEL, "Rent");

        assertThat(result.refiled()).isEqualTo(2);
        for (Transaction t : List.of(a, b)) {
            assertThat(t.getCategoryId()).isEqualTo(rent);
            assertThat(t.getDecisionSource()).isEqualTo(Transaction.DecisionSource.USER_RULE);
            assertThat(t.getDecisionRuleId()).isEqualTo(rule.getId());
            assertThat(t.getDecisionConfidence()).isEqualTo(ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
            assertThat(t.isNeedsCategoryReview()).isFalse();
            assertThat(t.isCategoryManuallySet()).isFalse();
        }
        for (Transaction t : List.of(manual, outOfRange, incoming, otherPayee)) {
            assertThat(t.getCategoryId()).isEqualTo(other);
        }
        verify(transactionRepository).saveAll(argThat(saved -> {
            List<Transaction> list = new ArrayList<>();
            saved.forEach(list::add);
            return list.size() == 2 && list.contains(a) && list.contains(b);
        }));
        verify(categorizationService, never()).queueLearning(any(), any(), any());
    }

    /** The question was about the detected group, which never holds a transfer or a hidden duplicate. */
    @Test
    void reFiling_leavesTransfersAndDuplicatesAlone_evenInRange() {
        detected("10000.0000");
        for (int m = 5; m <= 7; m++) row(LABEL, Transaction.Type.EXPENSE, "10000", m);
        Transaction transfer = row(LABEL, Transaction.Type.EXPENSE, "10000", 8);
        transfer.setTransfer(true);
        Transaction duplicate = row(LABEL, Transaction.Type.EXPENSE, "10000", 9);
        duplicate.setIsDuplicateOf(UUID.randomUUID());
        firstAnswerRule();
        UUID other = category("Other").getId();

        RecurringAnswerService.Result result = service.categorize(userId, LABEL, "Rent");

        assertThat(result.refiled()).isEqualTo(3);
        assertThat(transfer.getCategoryId()).isEqualTo(other);
        assertThat(duplicate.getCategoryId()).isEqualTo(other);
    }

    /** One lock per user, not per payee: two answers creating the same new category must not race. */
    @Test
    void answersAreSerialisedPerUser_whateverThePayee() {
        detected("10000.0000");
        for (int m = 5; m <= 7; m++) row(LABEL, Transaction.Type.EXPENSE, "10000", m);
        firstAnswerRule();

        service.categorize(userId, LABEL, "Rent");

        verify(ruleRepository).lockAnswers("recurring-answer:" + userId);
    }

    /** An answer names one payee and an amount range, so it outranks a broader rule of the user's. */
    @Test
    void anAnswerIsSavedAheadOfTheUsersOrdinaryRules() {
        detected("10000.0000");
        for (int m = 5; m <= 7; m++) row(LABEL, Transaction.Type.EXPENSE, "10000", m);
        CategoryRule rule = firstAnswerRule();

        service.categorize(userId, LABEL, "Rent");

        verify(ruleRepository).insertPayeeRuleIfAbsent(any(), eq(userId), eq(LABEL), eq("Rent"), any(), any(),
                eq(RecurringAnswerService.ANSWER_PRIORITY));
        assertThat(rule.getPriority()).isEqualTo(RecurringAnswerService.ANSWER_PRIORITY);
        assertThat(RecurringAnswerService.ANSWER_PRIORITY).isLessThan(100);
    }

    @Test
    void aPaymentOutsideTheDetectedGroup_neverWidensTheRange() {
        detected("10000.0000");
        for (int m = 5; m <= 7; m++) row(LABEL, Transaction.Type.EXPENSE, "10000", m);
        row("SAMPLE LANDLORD", Transaction.Type.EXPENSE, "30000", 8);   // another detector group (exact label)
        Transaction duplicate = row(LABEL, Transaction.Type.EXPENSE, "40000", 9);
        duplicate.setIsDuplicateOf(UUID.randomUUID());
        CategoryRule rule = firstAnswerRule();

        service.categorize(userId, LABEL, "Rent");

        assertThat(rule.getAmountMin()).isEqualByComparingTo("7999.00");
        assertThat(rule.getAmountMax()).isEqualByComparingTo("12001.00");
    }

    @Test
    void answeringInvestments_runsTheInvestmentRecountOverTheReFiledRows() {
        detected("5000.0000");
        for (int m = 5; m <= 7; m++) row(LABEL, Transaction.Type.EXPENSE, "5000", m);
        firstAnswerRule();

        service.categorize(userId, LABEL, "Investments");

        verify(reconciliationService).reconcileIfInvestmentExclusionMayChange(eq(userId),
                argThat(list -> list.size() == 3), eq(category("Investments")));
    }

    @Test
    void stillTheSameAnswer_forAPayeeNoLongerDetected_widensTheRangeToTheLatestPayment() {
        when(recurringService.detectForUser(userId)).thenReturn(List.of());
        row(LABEL, Transaction.Type.EXPENSE, "10000", 5);
        Transaction latest = row(LABEL, Transaction.Type.EXPENSE, "12500", 7);
        CategoryRule existing = savedRule("Rent", "8000.00", "12000.00");
        when(ruleRepository.findUserPayeeRule(userId, LABEL)).thenReturn(Optional.of(existing));

        RecurringAnswerService.Result result = service.categorize(userId, LABEL, "Rent");

        assertThat(result.kind()).isEqualTo("STILL");
        assertThat(existing.getAmountMin()).isEqualByComparingTo("8000.00");
        assertThat(existing.getAmountMax()).isEqualByComparingTo("15001.00");
        assertThat(latest.getCategoryId()).isEqualTo(category("Rent").getId());
    }

    /** A payment the user already filed by hand (a one-off deposit) is their decision, not the latest rent. */
    @Test
    void still_widensToTheLatestPaymentTheUserDidNotFileByHand() {
        when(recurringService.detectForUser(userId)).thenReturn(List.of());
        row(LABEL, Transaction.Type.EXPENSE, "10000", 5);
        row(LABEL, Transaction.Type.EXPENSE, "12500", 6);
        Transaction deposit = row(LABEL, Transaction.Type.EXPENSE, "50000", 7);
        deposit.setCategoryManuallySet(true);
        Transaction transfer = row(LABEL, Transaction.Type.EXPENSE, "70000", 8);
        transfer.setTransfer(true);
        CategoryRule existing = savedRule("Rent", "8000.00", "12000.00");
        when(ruleRepository.findUserPayeeRule(userId, LABEL)).thenReturn(Optional.of(existing));

        service.categorize(userId, LABEL, "Rent");

        assertThat(existing.getAmountMin()).isEqualByComparingTo("8000.00");
        assertThat(existing.getAmountMax()).isEqualByComparingTo("15001.00");
    }

    @Test
    void aDifferentAnswer_isAChange_andUpdatesTheRulesCategory() {
        detected("10000.0000");
        for (int m = 5; m <= 7; m++) row(LABEL, Transaction.Type.EXPENSE, "10000", m);
        CategoryRule existing = savedRule("Rent", "7999.00", "12001.00");
        when(ruleRepository.findUserPayeeRule(userId, LABEL)).thenReturn(Optional.of(existing));

        RecurringAnswerService.Result result = service.categorize(userId, LABEL, "Loan EMI");

        assertThat(result.kind()).isEqualTo("CHANGE");
        assertThat(existing.getActionValue()).isEqualTo("Loan EMI");
    }

    @Test
    void changedAmounts_listsOnlyUndetectedPayeesWhoseLatestPaymentLeftTheRange() {
        when(recurringService.detectForUser(userId)).thenReturn(List.of(new RecurringDto("sample detected", "Monthly",
                new BigDecimal("100"), 3, LocalDate.of(2026, 7, 3), LocalDate.of(2026, 8, 3))));
        CategoryRule moved = savedRule("Rent", "8000.00", "12000.00");
        CategoryRule detectedAnswer = savedRule("Utilities", "1.00", "2.00");
        detectedAnswer.setComparisonValue("Sample Detected");
        CategoryRule stillInRange = savedRule("Insurance", "400.00", "600.00");
        stillInRange.setComparisonValue("sample insurer");
        when(ruleRepository.findUserPayeeRules(userId)).thenReturn(List.of(moved, detectedAnswer, stillInRange));
        row(LABEL, Transaction.Type.EXPENSE, "10000", 5);
        row(LABEL, Transaction.Type.EXPENSE, "12500", 7);
        row("sample detected", Transaction.Type.EXPENSE, "999", 7);
        row("sample insurer", Transaction.Type.EXPENSE, "500", 7);

        List<ChangedAmountDto> changed = service.changedAmounts(userId);

        assertThat(changed).hasSize(1);
        ChangedAmountDto c = changed.get(0);
        assertThat(c.merchant()).isEqualTo(LABEL);
        assertThat(c.category()).isEqualTo("Rent");
        assertThat(c.latestAmount()).isEqualByComparingTo("12500");
        assertThat(c.latestDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(c.amountMin()).isEqualByComparingTo("8000.00");
        assertThat(c.amountMax()).isEqualByComparingTo("12000.00");
    }

    /** "Still Rent?" must not ask about a payment the user filed by hand, nor about a transfer. */
    @Test
    void changedAmounts_ignoresALatestPaymentFiledByHandOrMarkedATransfer() {
        when(recurringService.detectForUser(userId)).thenReturn(List.of());
        when(ruleRepository.findUserPayeeRules(userId)).thenReturn(List.of(savedRule("Rent", "8000.00", "12000.00")));
        row(LABEL, Transaction.Type.EXPENSE, "10000", 5);
        Transaction deposit = row(LABEL, Transaction.Type.EXPENSE, "50000", 6);
        deposit.setCategoryManuallySet(true);
        Transaction transfer = row(LABEL, Transaction.Type.EXPENSE, "30000", 7);
        transfer.setTransfer(true);
        Transaction duplicate = row(LABEL, Transaction.Type.EXPENSE, "30000", 8);
        duplicate.setIsDuplicateOf(UUID.randomUUID());

        assertThat(service.changedAmounts(userId)).isEmpty();
    }

    /** A dismissed payee was the user saying "stop showing me this"; it is not re-asked either. */
    @Test
    void changedAmounts_skipsADismissedPayee() {
        when(recurringService.detectForUser(userId)).thenReturn(List.of());
        when(recurringService.dismissedPayees(userId)).thenReturn(java.util.Set.of("SAMPLE LANDLORD"));
        when(ruleRepository.findUserPayeeRules(userId)).thenReturn(List.of(savedRule("Rent", "8000.00", "12000.00")));
        row(LABEL, Transaction.Type.EXPENSE, "12500", 7);

        assertThat(service.changedAmounts(userId)).isEmpty();
    }

    /** Dashboard and Insights call this on every load: one read of the user's rows, however many answers. */
    @Test
    void changedAmounts_readsTheUsersRowsOnce_howeverManyAnswersExist() {
        when(recurringService.detectForUser(userId)).thenReturn(List.of());
        List<CategoryRule> answers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CategoryRule r = savedRule("Rent", "1.00", "2.00");
            r.setComparisonValue("sample payee " + i);
            answers.add(r);
            row("sample payee " + i, Transaction.Type.EXPENSE, "500", 7);
        }
        when(ruleRepository.findUserPayeeRules(userId)).thenReturn(answers);

        assertThat(service.changedAmounts(userId)).hasSize(5);
        verify(transactionRepository, org.mockito.Mockito.times(1)).findByUserIdAndAccountIdIn(eq(userId), any());
    }

    @Test
    void changedAmounts_isEmptyWhenRecurringDetectionIsSwitchedOff() {
        when(featureFlagService.isEnabled("RECURRING_DETECTION_ENABLED")).thenReturn(false);

        assertThat(service.changedAmounts(userId)).isEmpty();
        verify(ruleRepository, never()).findUserPayeeRules(any());
    }
}
