package com.finora.aisort;

import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.observability.AiSortMetrics;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.CategorizationService;
import com.finora.service.ConfidenceEngine;
import com.finora.service.FynAvailabilityGuard;
import com.finora.service.FynCategorizationFallbackService;
import com.finora.service.UserMerchantCategoryResolutionService.SavedCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Works one AI sorting run, payee by payee, on its own thread (spec
 * docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md, "Sorting").
 *
 * <p>Each payee is its own transaction: one payee's failure costs only that payee, and the progress
 * the card polls is committed as it goes. Availability is checked before every payee, so turning AI
 * off, or reaching the daily cap or the monthly budget, stops the run there, with no further model
 * call. The model call itself goes through {@link FynCategorizationFallbackService#suggest}, the one
 * entry into the model, which never sends a payment to a person.
 */
@Component
public class AiSortRunner {

    private static final Logger log = LoggerFactory.getLogger(AiSortRunner.class);

    private final Executor executor;
    private final TransactionTemplate tx;
    private final AiSortRunRepository runs;
    private final UserRepository users;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final FynAvailabilityGuard guard;
    private final FynCategorizationFallbackService fallback;
    private final CategorizationService categorization;
    private final AiSortService service;
    private final AiSortMetrics metrics;

    public AiSortRunner(@Qualifier("aiSortExecutor") Executor executor, PlatformTransactionManager transactionManager,
                        AiSortRunRepository runs, UserRepository users, AccountRepository accounts,
                        TransactionRepository transactions, FynAvailabilityGuard guard,
                        FynCategorizationFallbackService fallback, CategorizationService categorization,
                        AiSortService service, AiSortMetrics metrics) {
        this.executor = executor;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.runs = runs;
        this.users = users;
        this.accounts = accounts;
        this.transactions = transactions;
        this.guard = guard;
        this.fallback = fallback;
        this.categorization = categorization;
        this.service = service;
        this.metrics = metrics;
    }

    /** Hands the run to its thread. A full queue stops the run rather than running it on the caller's. */
    public void launch(UUID runId, UUID userId, List<UUID> statementImportIds) {
        try {
            executor.execute(() -> work(runId, userId, statementImportIds));
        } catch (RejectedExecutionException e) {
            log.warn("AI sorting run {} not started: the queue is full", runId);
            finish(runId, AiSortRun.RunStatus.STOPPED);
        }
    }

    void work(UUID runId, UUID userId, List<UUID> statementImportIds) {
        try {
            for (AiSortService.SortablePayee payee : service.sortablePayees(userId, statementImportIds)) {
                if (!guard.categorizationAvailableFor(userId)) {
                    finish(runId, stopReason(userId));
                    return;
                }
                sortOne(runId, userId, payee);
            }
            finish(runId, AiSortRun.RunStatus.DONE);
        } catch (RuntimeException e) {
            log.error("AI sorting run {} failed", runId, e);
            finish(runId, AiSortRun.RunStatus.STOPPED);
        }
    }

    /** AI turned off (the customer's answer, or globally) reads as that; anything else is a limit. */
    private AiSortRun.RunStatus stopReason(UUID userId) {
        boolean consent = users.findById(userId).map(u -> Boolean.TRUE.equals(u.getAiProcessingConsent())).orElse(false);
        return consent && guard.categorizationOffered() ? AiSortRun.RunStatus.STOPPED_LIMIT : AiSortRun.RunStatus.STOPPED_AI_OFF;
    }

    private void sortOne(UUID runId, UUID userId, AiSortService.SortablePayee payee) {
        int sorted;
        try {
            sorted = tx.execute(s -> fileWithAiAnswer(userId, payee));
        } catch (RuntimeException e) {
            log.warn("AI sorting run {}: payee not sorted", runId, e);
            sorted = 0;
        }
        int filed = sorted;
        metrics.payee(filed > 0);
        tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r -> {
            r.progress(payee.importRows().size(), filed, Instant.now());
            runs.save(r);
        }));
    }

    /**
     * Asks the model about one payee and files that payee's waiting payments out with its answer, as an
     * AI guess that stays flagged for review. Returns how many of this import's rows were filed. A saved
     * answer that is the customer's own (they answered meanwhile) files nothing here: their answer
     * already did.
     */
    private int fileWithAiAnswer(UUID userId, AiSortService.SortablePayee payee) {
        Transaction first = payee.importRows().get(0);
        Optional<SavedCategory> answer = fallback.suggest(userId, payee.counterpartyKey(), Transaction.Type.EXPENSE,
                first.getDescription());
        if (answer.isEmpty() || answer.get().chosenByUser()) return 0;
        Category category = categorization.resolveOrCreateCategory(userId, answer.get().categoryName());
        Collection<UUID> live = accounts.findByUserId(userId).stream().map(Account::getId).toList();
        if (live.isEmpty()) return 0;
        Set<UUID> importRowIds = new HashSet<>();
        payee.importRows().forEach(t -> importRowIds.add(t.getId()));
        int sorted = 0;
        for (Transaction t : transactions.findByUserIdAndCounterpartyKeyAndTxnTypeAndAccountIdIn(
                userId, payee.counterpartyKey(), Transaction.Type.EXPENSE, live)) {
            // A row the customer answered (in Quick sort, or by hand) while the run was going keeps
            // their answer.
            if (!t.isNeedsCategoryReview() || t.isCategoryManuallySet()) continue;
            t.setCategoryId(category.getId());
            t.setDecisionSource(Transaction.DecisionSource.AI_FALLBACK);
            t.setDecisionRuleId(null);
            t.setDecisionConfidence(ConfidenceEngine.INITIAL_AI_FALLBACK_CONFIDENCE);
            transactions.save(t);
            if (importRowIds.contains(t.getId())) sorted++;
        }
        return sorted;
    }

    private void finish(UUID runId, AiSortRun.RunStatus status) {
        tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r -> {
            if (r.getStatus() != AiSortRun.RunStatus.RUNNING) return;
            r.finish(status, Instant.now());
            runs.save(r);
            metrics.runFinished(status);
        }));
    }
}
