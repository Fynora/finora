package com.finora.service;

import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.repository.TransactionRepository.CategorySuggestionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Re-checks rows still waiting for review against the current suggestion rules.
 *
 * <h2>Why</h2>
 *
 * <p>A row's category is suggested once, when it is imported. A rule added later only helped the
 * next import: measured on a tester's account (2026-10-03), bank-interest credits and a card-bill
 * credit imported the day before #1876 added rules for them were still "Other" and waiting for the
 * user. Raising {@link CategorizationService#SUGGESTION_VERSION} makes this sweep re-run the
 * suggestion on every row that is still waiting.
 *
 * <h2>What it may change</h2>
 *
 * <p>Only a row that is waiting, not chosen by the user, and still carrying one of the two
 * unresolved guesses ("Other" or the structural person guess). The suggestion is the read-only
 * waterfall staging uses, with the same inputs ({@code accountType} null, the row's direction), so
 * a re-checked row gets exactly what a fresh import of it would. It never calls the AI model.
 * A row the rules still cannot place is only stamped.
 *
 * <h2>Shape</h2>
 *
 * <p>Same as {@link CounterpartyBackfillSweepService}: no job table, a discovery predicate a done
 * row no longer matches, one transaction per batch, a failing row left unstamped and logged without
 * its narration, and each user whose categories changed reconciled afterwards in their own
 * transaction.
 */
@Component
public class CategorySuggestionSweepService {

    private static final Logger log = LoggerFactory.getLogger(CategorySuggestionSweepService.class);

    @Value("${app.category-suggestion-sweep.enabled:true}")
    private boolean sweepEnabled;

    /** Per row: one read-only suggestion (a few indexed reads) and one single-row UPDATE. */
    @Value("${app.category-suggestion-sweep.batch-size:500}")
    private int batchSize;

    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;
    private final ReconciliationService reconciliationService;
    private final TransactionTemplate transactionTemplate;

    public CategorySuggestionSweepService(TransactionRepository transactionRepository,
                                          CategorizationService categorizationService,
                                          ReconciliationService reconciliationService,
                                          TransactionTemplate transactionTemplate) {
        this.transactionRepository = transactionRepository;
        this.categorizationService = categorizationService;
        this.reconciliationService = reconciliationService;
        this.transactionTemplate = transactionTemplate;
    }

    /** Flag-gated and fixedDelay for the reasons CounterpartyBackfillSweepService.scheduledSweep gives. */
    @Scheduled(fixedDelayString = "${app.category-suggestion-sweep.interval-ms:300000}",
            initialDelayString = "${app.category-suggestion-sweep.initial-delay-ms:180000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        if (result.changed() > 0 || result.stamped() > 0 || result.failed() > 0) {
            log.info("Category suggestion re-check at v{}: {} row(s) given a category, {} unchanged, {} skipped, {} failed.{}",
                    CategorizationService.SUGGESTION_VERSION, result.changed(), result.stamped(),
                    result.skipped(), result.failed(), result.drained() ? " Backlog drained." : "");
        }
    }

    public Result sweep() {
        short version = CategorizationService.SUGGESTION_VERSION;
        List<CategorySuggestionRow> candidates = transactionRepository
                .findWaitingRowsBelowSuggestionVersion(version, PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) return new Result(0, 0, 0, 0, true);

        int[] counts = new int[4]; // changed, stamped, skipped, failed
        Set<UUID> changedUsers = new LinkedHashSet<>();
        Map<UUID, List<CategoryRule>> rulesByUser = new HashMap<>();
        // One transaction per row, unlike the counterparty backfill's one per batch: a row here can
        // fail in the database (it may create a category, and it writes a foreign key), and in
        // Postgres one failed statement aborts the whole transaction -- every other row's write in
        // a shared batch would be rolled back with it.
        for (CategorySuggestionRow row : candidates) {
            try {
                transactionTemplate.executeWithoutResult(tx -> {
                    int outcome = recheck(row, version, rulesByUser);
                    counts[outcome]++;
                    if (outcome == CHANGED) changedUsers.add(row.getUserId());
                });
            } catch (RuntimeException e) {
                // Rolled back and left unstamped, so the next pass retries it; the narration is user
                // financial data and is not logged.
                counts[FAILED]++;
                log.error("Category suggestion re-check failed for transaction {}: {}", row.getId(), e.toString());
            }
        }

        // A new category can change what reconciliation concludes (an investment's exclusion, a card
        // bill), and nothing else re-runs it until the user next imports or edits. One transaction
        // per user, after the batch committed, for the reasons CounterpartyBackfillSweepService gives.
        for (UUID userId : changedUsers) {
            try {
                transactionTemplate.executeWithoutResult(tx -> reconciliationService.reconcileForUser(userId));
            } catch (RuntimeException e) {
                log.error("Reconciliation after category re-check failed for user {}: {}", userId, e.toString());
            }
        }

        boolean drained = candidates.size() < batchSize && counts[3] == 0;
        return new Result(counts[0], counts[1], counts[2], counts[3], drained);
    }

    private static final int CHANGED = 0;
    private static final int STAMPED = 1;
    private static final int SKIPPED = 2;
    private static final int FAILED = 3;

    /** Re-checks one row inside the caller's transaction; returns which of the counts it adds to. */
    private int recheck(CategorySuggestionRow row, short version, Map<UUID, List<CategoryRule>> rulesByUser) {
        List<CategoryRule> rules = rulesByUser.computeIfAbsent(row.getUserId(), categorizationService::ruleSetFor);
        CategorizationService.Suggestion s = categorizationService.suggestReadOnly(rules, row.getUserId(),
                row.getDescription(), row.getAmount(), null, null, row.getTxnType());
        if (isStillAGuess(s)) {
            return transactionRepository.stampSuggestionVersion(row.getId(), version) > 0 ? STAMPED : SKIPPED;
        }
        Category category = categorizationService.resolveOrCreateCategory(row.getUserId(), s.category());
        boolean needsReview = categorizationService.needsCategoryReview(row.getUserId(),
                CategorizationService.isUnconfirmedGuess(s.source(), s.category()), s.confidence());
        int written = transactionRepository.applyCategorySuggestion(row.getId(), category.getId(),
                s.decisionSource(), s.ruleId(), s.confidence(), needsReview, version);
        if (written == 0) return SKIPPED;
        categorizationService.recordRuleMatch(s.ruleId());
        return CHANGED;
    }

    /** The waterfall still ends on one of the two unresolved guesses: nothing better to write. */
    private static boolean isStillAGuess(CategorizationService.Suggestion s) {
        return s.decisionSource() == Transaction.DecisionSource.MERCHANT_DEFAULT
                || s.decisionSource() == Transaction.DecisionSource.STRUCTURAL_P2P;
    }

    /**
     * @param changed rows given a new category
     * @param stamped rows the current rules examined and left as they were
     * @param skipped rows that stopped qualifying between discovery and write (chosen by the user,
     *                answered elsewhere, deleted)
     * @param failed  rows the suggestion threw on; left for the next pass
     * @param drained whether nothing is left to examine
     */
    public record Result(int changed, int stamped, int skipped, int failed, boolean drained) {
    }
}
