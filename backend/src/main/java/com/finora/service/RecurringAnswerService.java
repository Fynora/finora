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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The recurring-payment question's answer (docs/superpowers/specs/2026-10-02-recurring-payment-answer-design.md
 * §2): the user says what a repeating payment is for, once, and it is filed that way from then on.
 *
 * <p>The answer is stored as the user's PAYEE rule -- payee label plus an amount range -- because
 * a user rule already outranks every other categorisation source and is already loaded once per
 * import, so future statements get it with no new loading code. Past payments in the range are
 * re-filed as "filed by your rule", never as "set by hand": a later answer must be able to move
 * them again. Nothing is taught to merchant learning or the shared corpus -- merchants are grouped
 * by the payee's first word, and a rule is the user's whole decision already.
 */
@Service
public class RecurringAnswerService {

    private static final String FEATURE_FLAG = "RECURRING_DETECTION_ENABLED";

    private final RecurringService recurringService;
    private final CategoryRuleRepository categoryRuleRepository;
    private final CategorizationService categorizationService;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final ReconciliationService reconciliationService;
    private final AuditService auditService;
    private final FeatureFlagService featureFlagService;

    public RecurringAnswerService(RecurringService recurringService, CategoryRuleRepository categoryRuleRepository,
                                  CategorizationService categorizationService, TransactionRepository transactionRepository,
                                  AccountRepository accountRepository, ReconciliationService reconciliationService,
                                  AuditService auditService, FeatureFlagService featureFlagService) {
        this.recurringService = recurringService;
        this.categoryRuleRepository = categoryRuleRepository;
        this.categorizationService = categorizationService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.reconciliationService = reconciliationService;
        this.auditService = auditService;
        this.featureFlagService = featureFlagService;
    }

    /** What an answer did: how many past payments it re-filed, and FIRST, CHANGE or STILL. */
    public record Result(int refiled, String kind) {}

    /** Same tolerance as RecurringService's amount-consistency test: 20% of the amount plus ₹1. */
    static BigDecimal tolerance(BigDecimal amount) {
        return amount.multiply(new BigDecimal("0.20")).add(BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP);
    }

    /** An inclusive amount range; a null side is unbounded. */
    record Range(BigDecimal min, BigDecimal max) {
        static Range around(BigDecimal amount, BigDecimal tolerance) {
            BigDecimal centre = amount.setScale(2, RoundingMode.HALF_UP);
            return new Range(centre.subtract(tolerance), centre.add(tolerance));
        }

        Range cover(BigDecimal amount) {
            return union(new Range(amount, amount));
        }

        Range union(Range other) {
            BigDecimal lo = min == null || other.min == null ? null : min.min(other.min);
            BigDecimal hi = max == null || other.max == null ? null : max.max(other.max);
            return new Range(lo, hi);
        }

        boolean contains(BigDecimal amount) {
            return amount != null
                    && (min == null || amount.compareTo(min) >= 0)
                    && (max == null || amount.compareTo(max) <= 0);
        }
    }

    @Transactional
    public Result categorize(UUID userId, String merchant, String categoryName) {
        if (!featureFlagService.isEnabled(FEATURE_FLAG)) throw notFound();
        String label = merchant.trim();
        // First, before any read: a concurrent answer for the same payee waits here, then sees ours.
        categoryRuleRepository.lockPayeeAnswer(userId + ":" + label.toLowerCase(Locale.ROOT));
        Optional<RecurringDto> group = recurringService.detectForUser(userId).stream()
                .filter(r -> r.merchant().equalsIgnoreCase(label))
                .findFirst();
        Optional<CategoryRule> existing = categoryRuleRepository.findUserPayeeRule(userId, label);
        if (group.isEmpty() && existing.isEmpty()) throw notFound();

        List<Transaction> payeeRows = payeeExpenseRows(userId, label);
        Range range = null;
        if (group.isPresent()) {
            BigDecimal average = group.get().averageAmount();
            range = Range.around(average, tolerance(average));
            // Cover every payment the detector grouped -- the exact label, money out, not a transfer,
            // not a duplicate (RecurringService's own grouping). They already sit within the
            // tolerance by the detector's construction; this only guards rounding at the edges, and
            // never widens over a payment outside the group.
            String grouped = group.get().merchant();
            for (Transaction t : payeeRows) {
                if (grouped.equals(t.getMerchant()) && !t.isTransfer() && t.getIsDuplicateOf() == null) {
                    range = range.cover(t.getAmount());
                }
            }
        }
        if (existing.isPresent()) {
            Range saved = new Range(existing.get().getAmountMin(), existing.get().getAmountMax());
            range = range == null ? saved : range.union(saved);
            // "Still Rent?" for a payee whose amount moved: the answer now covers the latest payment.
            if (!payeeRows.isEmpty()) {
                BigDecimal latest = payeeRows.get(payeeRows.size() - 1).getAmount();
                range = range.union(Range.around(latest, tolerance(latest)));
            }
        }

        Category category = categorizationService.resolveOrCreateCategory(userId, categoryName);
        String kind = existing.isEmpty() ? "FIRST"
                : category.getName().equalsIgnoreCase(existing.get().getActionValue()) ? "STILL" : "CHANGE";

        // Insert-or-nothing against uq_category_rules_user_payee, then one update path for every
        // answer: a concurrent second answer for the same payee updates the same rule.
        categoryRuleRepository.insertPayeeRuleIfAbsent(UUID.randomUUID(), userId, label, category.getName(),
                range.min(), range.max());
        CategoryRule rule = categoryRuleRepository.findUserPayeeRule(userId, label).orElseThrow();
        rule.setActionValue(category.getName());
        rule.setAmountMin(range.min());
        rule.setAmountMax(range.max());
        rule.setEnabled(true);
        rule.setUpdatedAt(Instant.now());
        categoryRuleRepository.save(rule);

        List<Transaction> refiled = new ArrayList<>();
        for (Transaction t : payeeRows) {
            if (t.isCategoryManuallySet() || !range.contains(t.getAmount())) continue;
            t.setCategoryId(category.getId());
            t.setDecisionSource(Transaction.DecisionSource.USER_RULE);
            t.setDecisionRuleId(rule.getId());
            t.setDecisionConfidence(ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
            t.setNeedsCategoryReview(false);
            refiled.add(t);
        }
        // Entity saves, so each row's version moves and the mobile change stamp with it.
        transactionRepository.saveAll(refiled);
        reconciliationService.reconcileIfInvestmentExclusionMayChange(userId, refiled, category);

        auditService.record(userId, "RECURRING_ANSWERED", "CategoryRule", rule.getId(),
                Map.of("merchant", label, "category", category.getName(), "kind", kind, "refiled", refiled.size()));
        recurringService.confirm(userId, label);
        return new Result(refiled.size(), kind);
    }

    /**
     * Saved answers whose payee's latest payment is outside the saved range, for payees the
     * detector no longer groups -- see ChangedAmountDto. Detected payees report AMOUNT_CHANGED on
     * their own RecurringDto instead, so they are left out here.
     */
    public List<ChangedAmountDto> changedAmounts(UUID userId) {
        if (!featureFlagService.isEnabled(FEATURE_FLAG)) return List.of();
        Set<String> detected = recurringService.detectForUser(userId).stream()
                .map(r -> r.merchant().trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<ChangedAmountDto> changed = new ArrayList<>();
        for (CategoryRule rule : categoryRuleRepository.findUserPayeeRules(userId)) {
            String label = rule.getComparisonValue().trim();
            if (detected.contains(label.toLowerCase(Locale.ROOT))) continue;
            List<Transaction> rows = payeeExpenseRows(userId, label);
            if (rows.isEmpty()) continue;
            Transaction latest = rows.get(rows.size() - 1);
            if (new Range(rule.getAmountMin(), rule.getAmountMax()).contains(latest.getAmount())) continue;
            changed.add(new ChangedAmountDto(label, rule.getActionValue(), latest.getAmount(), latest.getTxnDate(),
                    rule.getAmountMin(), rule.getAmountMax()));
        }
        return changed;
    }

    /** The user's live-account money-out rows whose payee label is {@code label}, oldest first. */
    private List<Transaction> payeeExpenseRows(UUID userId, String label) {
        List<UUID> live = accountRepository.findByUserId(userId).stream().map(Account::getId).toList();
        if (live.isEmpty()) return List.of();
        return transactionRepository.findByUserIdAndAccountIdIn(userId, live).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE
                        && t.getMerchant() != null && t.getMerchant().trim().equalsIgnoreCase(label))
                .sorted(Comparator.comparing(Transaction::getTxnDate))
                .toList();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "No recurring payment found for that payee.");
    }
}
