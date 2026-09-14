package com.finora.service;

import com.finora.dto.AuditLogDto;
import com.finora.dto.WorkspaceSummaryDto;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.RelationshipRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.imports.StatementCoverageAnalyzer.StatementPeriod;
import com.finora.util.UserZone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Financial Intelligence Workspace, Module 1 (Dashboard) — see
 * docs/team-message-financial-intelligence-workspace-kickoff.md. Read-only aggregation over
 * already-persisted data, same "Analytics never writes" discipline AnalyticsService already
 * follows (financial-intelligence-engine-spec.md §2) — this is a sibling to that service, not a
 * replacement for the personal-finance DashboardService (net worth/health score/spend-by-category
 * for the Ledger-facing Dashboard page), which this does not touch or duplicate.
 *
 * Every count here reuses the same `findByUserId(...).size()` pattern already established
 * elsewhere in this codebase (e.g. CsvImportService's merchantsBefore/merchantsAfter) rather than
 * introducing dedicated COUNT queries — consistent at this data volume (personal-finance scale,
 * not a metrics warehouse), revisit only if that stops being true.
 */
@Service
public class WorkspaceDashboardService {

    // Same thresholds Merchants.tsx's confidence badge already uses (financial-intelligence-
    // engine-spec.md §6.1: green >=90, amber 60-89, red <60) -- reused, not reinvented, for the
    // confidence-distribution tile below.
    private static final int HIGH_CONFIDENCE_THRESHOLD = 90;
    private static final int MEDIUM_CONFIDENCE_THRESHOLD = 60;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantCategoryLearningRepository learningRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final RelationshipRepository relationshipRepository;
    private final StatementImportRepository statementImportRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ConfidenceEngine confidenceEngine;

    public WorkspaceDashboardService(TransactionRepository transactionRepository, AccountRepository accountRepository,
                                      MerchantRepository merchantRepository, MerchantCategoryLearningRepository learningRepository,
                                      CategoryRuleRepository categoryRuleRepository, RelationshipRepository relationshipRepository,
                                      StatementImportRepository statementImportRepository, AuditLogRepository auditLogRepository,
                                      UserRepository userRepository, ConfidenceEngine confidenceEngine) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.merchantRepository = merchantRepository;
        this.learningRepository = learningRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.relationshipRepository = relationshipRepository;
        this.statementImportRepository = statementImportRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.confidenceEngine = confidenceEngine;
    }

    @Transactional(readOnly = true)
    public WorkspaceSummaryDto summarize(UUID userId) {
        // Soft-deleting an account never touches its transactions'/statements' own deleted_at (see
        // StatementImportService.DELETED_ACCOUNT_RETENTION) -- scoping both queries below to this
        // same live-account id set keeps a deleted account's rows from feeding these totals forever
        // instead of just during that 7-day grace window. See DashboardService.summarize for the
        // original fix this mirrors.
        List<com.finora.entity.Account> accounts = accountRepository.findByUserId(userId);
        List<UUID> liveAccountIds = accounts.stream().map(com.finora.entity.Account::getId).toList();
        List<Transaction> transactions = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndAccountIdIn(userId, liveAccountIds);
        List<Merchant> merchants = merchantRepository.findByUserId(userId);

        // One bulk query grouped in-memory, not one findByUserIdAndMerchantId call per merchant --
        // same N+1 discipline AnalyticsService.categoryConfidence() already established.
        Map<UUID, List<MerchantCategoryLearning>> pairsByMerchant = learningRepository.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(MerchantCategoryLearning::getMerchantId));

        long totalTransactions = transactions.size();
        long activeRules = categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId).size();
        long learnedMerchants = pairsByMerchant.size(); // merchants with at least one confirmed pair

        long manuallySet = transactions.stream().filter(Transaction::isCategoryManuallySet).count();
        Double categorizationAccuracy = totalTransactions == 0 ? null
                : automationRate(manuallySet, totalTransactions);
        // Issue #1452's own count, deliberately narrower than manuallySet above: a Source.MANUAL
        // transaction's categoryManuallySet=true just means the user picked a category while
        // typing it in themselves -- nothing auto-categorized it first, so there is nothing to
        // have "corrected". Only an imported transaction (the engine guessed a category on the
        // way in) that was then categoryManuallySet=true represents a genuine correction.
        // categorizationAccuracy above keeps its original, broader definition unchanged -- this
        // fix is scoped to the new field this ticket introduces, not a silent redefinition of an
        // already-shipped (#1450) metric.
        long manualCorrections = transactions.stream()
                .filter(t -> t.isCategoryManuallySet() && t.getSource() != Transaction.Source.MANUAL)
                .count();
        List<AuditLogDto> recentActivity = recentActivity(userId);
        FinancialMemoryCompleteness.Result completeness = financialMemoryCompleteness(userId, accounts);

        return new WorkspaceSummaryDto(
                totalTransactions,
                accounts.size(),
                merchants.size(),
                learnedMerchants,
                activeRules,
                relationshipRepository.findByUserId(userId).size(),
                // Only .size() was ever needed here -- a COUNT, not the entity-returning finder
                // (or even a fileContent-free projection): see
                // StatementImportRepository.StatementMetadata's own doc comment for the rest of
                // that finder's removal.
                liveAccountIds.isEmpty() ? 0L
                        : statementImportRepository.countByUserIdAndAccountIdIn(userId, liveAccountIds),
                completeness.monthsOfHistory(),
                completeness.completenessPercent(),
                categorizationAccuracy,
                manualCorrections,
                confidenceDistribution(merchants, pairsByMerchant),
                countByStatus(transactions, Transaction.ReconciliationStatus.DUPLICATE),
                countByStatus(transactions, Transaction.ReconciliationStatus.TRANSFER),
                countByStatus(transactions, Transaction.ReconciliationStatus.REFUND),
                transactions.stream().filter(Transaction::isRecurring).count(),
                recentActivity,
                health(transactions, activeRules, learnedMerchants, recentActivity)
        );
    }

    /**
     * "Is this feature actually active/intact" — not all five signals are equally real yet, and
     * that's stated per-field rather than glossed over:
     *
     * - rulesEnabled/merchantLearningActive are real presence checks: does the user have any
     *   enabled rules / any merchant with a confirmed category at all.
     * - reconciliationHealthy is a real data-integrity check, not a placeholder — it's this
     *   session's own dangling-pointer bug (isDuplicateOf/transferPairId/refundOfTransactionId
     *   left pointing at a transaction that no longer exists, fixed in TransactionService and
     *   StatementImportService) turned into a live monitor: if that bug class ever regresses,
     *   or a future write path introduces a new one, this flips to false instead of failing
     *   silently again.
     * - recurringDetectionHealthy is a documented placeholder (always true) — RecurringService
     *   has no persisted failure state and no equivalent integrity signal to check yet. It stays
     *   honest rather than fabricated; becomes real once Reconciliation Monitor's run-tracking
     *   (task: instrument ReconciliationService/RecurringService for the activity feed) lands.
     * - auditLoggingHealthy checks that the activity feed isn't suspiciously empty when there's
     *   actually something on the books to have logged — not a full write-path audit, but more
     *   than a hardcoded true.
     */
    private WorkspaceSummaryDto.WorkspaceHealthDto health(List<Transaction> transactions, long activeRules,
                                                            long learnedMerchants, List<AuditLogDto> recentActivity) {
        boolean somethingToAudit = !transactions.isEmpty() || activeRules > 0;
        boolean auditLoggingHealthy = !somethingToAudit || !recentActivity.isEmpty();
        return new WorkspaceSummaryDto.WorkspaceHealthDto(
                activeRules > 0,
                learnedMerchants > 0,
                reconciliationHealthy(transactions),
                true,
                auditLoggingHealthy
        );
    }

    private boolean reconciliationHealthy(List<Transaction> transactions) {
        Set<UUID> existingIds = transactions.stream().map(Transaction::getId).collect(Collectors.toSet());
        for (Transaction t : transactions) {
            if (t.getIsDuplicateOf() != null && !existingIds.contains(t.getIsDuplicateOf())) return false;
            if (t.getTransferPairId() != null && !existingIds.contains(t.getTransferPairId())) return false;
            if (t.getRefundOfTransactionId() != null && !existingIds.contains(t.getRefundOfTransactionId())) return false;
        }
        return true;
    }

    private double automationRate(long manuallySet, long total) {
        return Math.round(((total - manuallySet) * 10000.0) / total) / 100.0;
    }

    private long countByStatus(List<Transaction> transactions, Transaction.ReconciliationStatus status) {
        return transactions.stream().filter(t -> t.getReconciliationStatus() == status).count();
    }

    private Map<String, Long> confidenceDistribution(List<Merchant> merchants,
                                                       Map<UUID, List<MerchantCategoryLearning>> pairsByMerchant) {
        Map<String, Long> distribution = new LinkedHashMap<>();
        distribution.put("HIGH", 0L);
        distribution.put("MEDIUM", 0L);
        distribution.put("LOW", 0L);
        distribution.put("UNCONFIRMED", 0L);
        for (Merchant m : merchants) {
            List<MerchantCategoryLearning> pairs = pairsByMerchant.getOrDefault(m.getId(), List.of());
            MerchantCategoryLearning top = confidenceEngine.topCategory(pairs);
            String bucket = bucketFor(top);
            distribution.merge(bucket, 1L, Long::sum);
        }
        return distribution;
    }

    private String bucketFor(MerchantCategoryLearning top) {
        if (top == null) return "UNCONFIRMED";
        if (top.getConfidence() >= HIGH_CONFIDENCE_THRESHOLD) return "HIGH";
        if (top.getConfidence() >= MEDIUM_CONFIDENCE_THRESHOLD) return "MEDIUM";
        return "LOW";
    }

    /**
     * One {@code findMetadataWithPeriodByUserIdAndAccountId} call per live account -- an N-query
     * pattern, deliberately: it's the exact query {@code AccountCoverageService} already uses per
     * account (already excludes undated/superseded rows), and this class's own doc comment above
     * already accepts N-per-user queries at this data volume. A soft-deleted account is excluded
     * by construction, since {@code accounts} here is already the live-only list {@code summarize}
     * built at the top of this method.
     *
     * <p>"Today" is resolved in the user's own timezone via {@link UserZone}, not a bare
     * {@code LocalDate.now()} -- the exact bug class {@code NetWorthService}/{@code
     * DashboardService} already hit and fixed for the same reason: a user meaningfully east or
     * west of wherever the server runs could get a freshness gap or a months-of-history count
     * computed against the wrong calendar day from their own point of view.
     */
    private FinancialMemoryCompleteness.Result financialMemoryCompleteness(UUID userId, List<com.finora.entity.Account> accounts) {
        Map<UUID, List<StatementPeriod>> periodsByAccount = accounts.stream()
                .collect(Collectors.toMap(com.finora.entity.Account::getId, account ->
                        statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(userId, account.getId())
                                .stream()
                                .map(AccountCoverageService::toStatementPeriod)
                                .toList()));
        LocalDate today = LocalDate.now(UserZone.forUser(userRepository, userId));
        return FinancialMemoryCompleteness.compute(periodsByAccount, today);
    }

    private List<AuditLogDto> recentActivity(UUID userId) {
        return auditLogRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(l -> new AuditLogDto(l.getId(), l.getUserId(), l.getAction(), l.getEntityType(),
                        l.getEntityId(), l.getMetadata(), l.getRequestId(), l.getCreatedAt()))
                .toList();
    }
}
