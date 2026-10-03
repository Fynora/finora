package com.finora.service;

import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.repository.TransactionRepository.CategorySuggestionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep's decisions per row, with the waterfall and the database mocked. What the JPQL and the
 * real waterfall do is CategorySuggestionSweepIT's job.
 */
class CategorySuggestionSweepServiceTest {

    private static final String INTEREST = "1234567890:Int.Pd:01-03-2026 to 31-05-2026"; // synthetic-ok

    private TransactionRepository transactionRepository;
    private CategorizationService categorizationService;
    private ReconciliationService reconciliationService;
    private CategorySuggestionSweepService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categorizationService = mock(CategorizationService.class);
        reconciliationService = mock(ReconciliationService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        // executeWithoutResult is a default method a Mockito mock does not fall through to -- the
        // established fix here, see CounterpartyBackfillSweepServiceTest.
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new CategorySuggestionSweepService(transactionRepository, categorizationService,
                reconciliationService, transactionTemplate);
        ReflectionTestUtils.setField(service, "batchSize", 10);
        when(categorizationService.ruleSetFor(userId)).thenReturn(List.of());
    }

    @Test
    void aRowTheRulesNowPlaceTakesTheCategoryAndLeavesTheQueue() {
        UUID id = UUID.randomUUID();
        CategorySuggestionRow row = row(id, INTEREST, Transaction.Type.INCOME);
        given(row);
        suggest(new CategorizationService.Suggestion("Interest & Cashback", "rule", null,
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70));
        UUID categoryId = category("Interest & Cashback");
        when(categorizationService.needsCategoryReview(userId, false, 70)).thenReturn(false);
        when(transactionRepository.applyCategorySuggestion(any(), any(), any(), any(), any(), anyBoolean(), anyShort()))
                .thenReturn(1);

        CategorySuggestionSweepService.Result result = service.sweep();

        verify(transactionRepository).applyCategorySuggestion(id, categoryId,
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70, false, CategorizationService.SUGGESTION_VERSION);
        verify(reconciliationService).reconcileForUser(userId);
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.drained()).isTrue();
    }

    @Test
    void aRowTheRulesStillCannotPlaceIsOnlyStamped() {
        UUID id = UUID.randomUUID();
        CategorySuggestionRow row = row(id, "UPI/REF92/UPI", Transaction.Type.EXPENSE); // synthetic-ok
        given(row);
        suggest(new CategorizationService.Suggestion("Other", "default", null,
                Transaction.DecisionSource.MERCHANT_DEFAULT, null, 20));
        when(transactionRepository.stampSuggestionVersion(id, CategorizationService.SUGGESTION_VERSION)).thenReturn(1);

        CategorySuggestionSweepService.Result result = service.sweep();

        verify(transactionRepository).stampSuggestionVersion(id, CategorizationService.SUGGESTION_VERSION);
        verify(transactionRepository, never()).applyCategorySuggestion(any(), any(), any(), any(), any(), anyBoolean(), anyShort());
        verify(categorizationService, never()).resolveOrCreateCategory(any(), any());
        verify(reconciliationService, never()).reconcileForUser(any());
        assertThat(result.stamped()).isEqualTo(1);
    }

    @Test
    void theStructuralPersonGuessAgainIsOnlyStamped() {
        UUID id = UUID.randomUUID();
        CategorySuggestionRow row = row(id, "UPI/RAVI KUMAR/REF97", Transaction.Type.EXPENSE); // synthetic-ok
        given(row);
        suggest(new CategorizationService.Suggestion(CategorizationService.P2P_CATEGORY,
                CategorizationService.STRUCTURAL_P2P_SOURCE, null, Transaction.DecisionSource.STRUCTURAL_P2P, null, 40));
        when(transactionRepository.stampSuggestionVersion(id, CategorizationService.SUGGESTION_VERSION)).thenReturn(1);

        service.sweep();

        verify(transactionRepository).stampSuggestionVersion(id, CategorizationService.SUGGESTION_VERSION);
        verify(transactionRepository, never()).applyCategorySuggestion(any(), any(), any(), any(), any(), anyBoolean(), anyShort());
    }

    @Test
    void aStillUnconfirmedAnswerTakesTheCategoryButKeepsWaiting() {
        UUID id = UUID.randomUUID();
        CategorySuggestionRow row = row(id, "UPI/SAMPLE STORE/REF93", Transaction.Type.EXPENSE); // synthetic-ok
        given(row);
        suggest(new CategorizationService.Suggestion("Groceries", CategorizationService.SHARED_CORPUS_SOURCE, null,
                Transaction.DecisionSource.SHARED_CORPUS, null, 60));
        UUID categoryId = category("Groceries");
        when(categorizationService.needsCategoryReview(userId, true, 60)).thenReturn(true);
        when(transactionRepository.applyCategorySuggestion(any(), any(), any(), any(), any(), anyBoolean(), anyShort()))
                .thenReturn(1);

        service.sweep();

        verify(transactionRepository).applyCategorySuggestion(id, categoryId,
                Transaction.DecisionSource.SHARED_CORPUS, null, 60, true, CategorizationService.SUGGESTION_VERSION);
    }

    @Test
    void oneRowThrowingLeavesItUnstampedAndTheRestWritten() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        CategorySuggestionRow badRow = row(bad, "UPI/REF94/UPI", Transaction.Type.EXPENSE); // synthetic-ok
        CategorySuggestionRow goodRow = row(good, "UPI/REF95/UPI", Transaction.Type.EXPENSE); // synthetic-ok
        given(badRow, goodRow);
        when(categorizationService.suggestReadOnly(any(), eq(userId), eq("UPI/REF94/UPI"), any(), isNull(), isNull(), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(categorizationService.suggestReadOnly(any(), eq(userId), eq("UPI/REF95/UPI"), any(), isNull(), isNull(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null,
                        Transaction.DecisionSource.MERCHANT_DEFAULT, null, 20));
        when(transactionRepository.stampSuggestionVersion(good, CategorizationService.SUGGESTION_VERSION)).thenReturn(1);

        CategorySuggestionSweepService.Result result = service.sweep();

        verify(transactionRepository, never()).stampSuggestionVersion(eq(bad), anyShort());
        verify(transactionRepository).stampSuggestionVersion(good, CategorizationService.SUGGESTION_VERSION);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.drained()).isFalse();
    }

    @Test
    void aRowAnsweredBetweenDiscoveryAndWriteIsCountedSkipped() {
        UUID id = UUID.randomUUID();
        CategorySuggestionRow row = row(id, INTEREST, Transaction.Type.INCOME);
        given(row);
        suggest(new CategorizationService.Suggestion("Interest & Cashback", "rule", null,
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70));
        category("Interest & Cashback");
        when(transactionRepository.applyCategorySuggestion(any(), any(), any(), any(), any(), anyBoolean(), anyShort()))
                .thenReturn(0);

        CategorySuggestionSweepService.Result result = service.sweep();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.changed()).isZero();
        verify(reconciliationService, never()).reconcileForUser(any());
    }

    @Test
    void nothingWaitingIsDrainedWithoutWork() {
        given();

        CategorySuggestionSweepService.Result result = service.sweep();

        assertThat(result.drained()).isTrue();
        verify(categorizationService, never()).ruleSetFor(any());
    }

    private void given(CategorySuggestionRow... rows) {
        when(transactionRepository.findWaitingRowsBelowSuggestionVersion(eq(CategorizationService.SUGGESTION_VERSION), any()))
                .thenReturn(List.of(rows));
    }

    private void suggest(CategorizationService.Suggestion suggestion) {
        when(categorizationService.suggestReadOnly(any(), eq(userId), any(), any(), isNull(), isNull(), any()))
                .thenReturn(suggestion);
    }

    private UUID category(String name) {
        Category c = mock(Category.class);
        UUID id = UUID.randomUUID();
        when(c.getId()).thenReturn(id);
        when(categorizationService.resolveOrCreateCategory(userId, name)).thenReturn(c);
        return id;
    }

    private CategorySuggestionRow row(UUID id, String description, Transaction.Type type) {
        CategorySuggestionRow r = mock(CategorySuggestionRow.class);
        when(r.getId()).thenReturn(id);
        when(r.getUserId()).thenReturn(userId);
        when(r.getDescription()).thenReturn(description);
        when(r.getAmount()).thenReturn(BigDecimal.valueOf(250));
        when(r.getTxnType()).thenReturn(type);
        return r;
    }
}
