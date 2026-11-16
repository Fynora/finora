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
import com.finora.service.UserMerchantCategoryResolutionService;
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

    /**
     * Payees in a row whose model call failed before the run stops: the AI is most likely
     * unreachable, and every further payee would wait out the same failure. The run stops as STOPPED,
     * which the customer can try again.
     */
    static final int MAX_FAILURES_IN_A_ROW = 3;

    /** One payee's outcome: this import's payments filed, and whether the model call failed. */
    record PayeeOutcome(int sorted, boolean callFailed) {}

    /** Hands attempt {@code attempt} of the run to its thread. A full queue stops the run rather than running it on the caller's. */
    public void launch(UUID runId, int attempt, UUID userId, List<UUID> statementImportIds) {
        try {
            executor.execute(() -> work(runId, attempt, userId, statementImportIds));
        } catch (RejectedExecutionException e) {
            log.warn("AI sorting run {} not started: the queue is full", runId);
            finish(runId, attempt, AiSortRun.RunStatus.STOPPED, userId, statementImportIds);
        }
    }

    /**
     * Before each payee the run must still be this attempt's and RUNNING: a run read as cut off
     * (AiSortService.run), or tried again, stops this worker before any further model call.
     */
    void work(UUID runId, int attempt, UUID userId, List<UUID> statementImportIds) {
        try {
            boolean anyFailed = false;
            int failuresInARow = 0;
            for (AiSortService.SortablePayee payee : service.sortablePayees(userId, statementImportIds)) {
                if (!runs.stillRunning(runId, attempt)) return;
                if (!guard.categorizationAvailableFor(userId)) {
                    finish(runId, attempt, stopReason(userId), userId, statementImportIds);
                    return;
                }
                PayeeOutcome outcome = sortOne(runId, userId, payee);
                metrics.payee(outcome.sorted() > 0);
                Integer saved = tx.execute(s -> runs.progress(runId, attempt, payee.importRows().size(),
                        outcome.sorted(), Instant.now()));
                if (saved == null || saved == 0) return;
                if (outcome.callFailed()) {
                    anyFailed = true;
                    if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                        finish(runId, attempt, AiSortRun.RunStatus.STOPPED, userId, statementImportIds);
                        return;
                    }
                } else {
                    failuresInARow = 0;
                }
            }
            // A payee whose call failed was not judged by the AI: the run did not finish its work, so
            // it reads as stopped and can be tried again, rather than "AI couldn't sort these".
            finish(runId, attempt, anyFailed ? AiSortRun.RunStatus.STOPPED : AiSortRun.RunStatus.DONE,
                    userId, statementImportIds);
        } catch (RuntimeException e) {
            log.error("AI sorting run {} failed", runId, e);
            finish(runId, attempt, AiSortRun.RunStatus.STOPPED, userId, statementImportIds);
        }
    }

    /** AI turned off (the customer's answer, or globally) reads as that; anything else is a limit. */
    private AiSortRun.RunStatus stopReason(UUID userId) {
        boolean consent = users.findById(userId).map(u -> Boolean.TRUE.equals(u.getAiProcessingConsent())).orElse(false);
        return consent && guard.categorizationOffered() ? AiSortRun.RunStatus.STOPPED_LIMIT : AiSortRun.RunStatus.STOPPED_AI_OFF;
    }

    private PayeeOutcome sortOne(UUID runId, UUID userId, AiSortService.SortablePayee payee) {
        try {
            PayeeOutcome outcome = tx.execute(s -> fileWithAiAnswer(userId, payee));
            return outcome == null ? new PayeeOutcome(0, true) : outcome;
        } catch (RuntimeException e) {
            log.warn("AI sorting run {}: payee not sorted", runId, e);
            return new PayeeOutcome(0, true);
        }
    }

    /**
     * Asks the model about one payee and files that payee's waiting payments out with its answer, as an
     * AI guess that stays flagged for review. Says how many of this import's rows were filed, and
     * whether the model call failed. A saved answer that is the customer's own (they answered
     * meanwhile) files nothing here: their answer already did.
     */
    private PayeeOutcome fileWithAiAnswer(UUID userId, AiSortService.SortablePayee payee) {
        Transaction first = payee.importRows().get(0);
        UserMerchantCategoryResolutionService.Attempt attempt = fallback.suggestForSort(userId,
                payee.counterpartyKey(), Transaction.Type.EXPENSE, first.getDescription());
        if (attempt.callFailed()) return new PayeeOutcome(0, true);
        Optional<SavedCategory> answer = attempt.answer();
        if (answer.isEmpty() || answer.get().chosenByUser()) return new PayeeOutcome(0, false);
        Category category = categorization.resolveOrCreateCategory(userId, answer.get().categoryName());
        Collection<UUID> live = accounts.findByUserId(userId).stream().map(Account::getId).toList();
        if (live.isEmpty()) return new PayeeOutcome(0, false);
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
        return new PayeeOutcome(sorted, false);
    }

    /**
     * Finishes attempt {@code attempt}, with what is left: this import's payments still waiting for the
     * customer that the AI could be asked about -- not the ones the AI filed, nor the ones the customer
     * answered themselves meanwhile (both now have a saved answer).
     */
    private void finish(UUID runId, int attempt, AiSortRun.RunStatus status, UUID userId, List<UUID> statementImportIds) {
        try {
            tx.executeWithoutResult(s -> {
                int left = service.sortablePayees(userId, statementImportIds).stream()
                        .mapToInt(p -> p.importRows().size()).sum();
                if (runs.finish(runId, attempt, status, left, Instant.now()) == 1) metrics.runFinished(status);
            });
        } catch (RuntimeException e) {
            log.error("AI sorting run {}: could not record its finish", runId, e);
        }
    }
}
