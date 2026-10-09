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
import com.finora.util.CategoryRules;
import com.finora.util.CounterpartyIdentity;
import com.finora.util.CounterpartyTyping;
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

    /**
     * A saved answer's rule priority: ahead of the user's ordinary rules (100 unless set otherwise;
     * lower runs first). An answer names one payee and an amount range, and it is the user's latest
     * word on that payee, so a broader rule of theirs that also matches must not override it.
     */
    static final int ANSWER_PRIORITY = 50;

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

    /** The amounts a saved payee rule already covers. */
    private static Range savedRange(CategoryRule rule) {
        return new Range(rule.getAmountMin(), rule.getAmountMax());
    }

    /** The range the detector's grouped payments span, around their average. */
    private Range detectedRange(RecurringDto group, List<Transaction> payeeRows) {
        BigDecimal average = group.averageAmount();
        Range range = Range.around(average, tolerance(average));
        // Cover every payment the detector grouped -- the exact label, money out, not a transfer,
        // not a duplicate (RecurringService's own grouping). They already sit within the
        // tolerance by the detector's construction; this only guards rounding at the edges, and
        // never widens over a payment outside the group.
        String grouped = group.merchant();
        for (Transaction t : payeeRows) {
            if (grouped.equals(t.getMerchant()) && !t.isTransfer() && t.getIsDuplicateOf() == null) {
                range = range.cover(t.getAmount());
            }
        }
        return range;
    }

    @Transactional
    public Result categorize(UUID userId, String merchant, String categoryName) {
        if (!featureFlagService.isEnabled(FEATURE_FLAG)) throw notFound();
        String label = merchant.trim();
        // First, before any read: a concurrent answer from this user waits here, then sees ours --
        // for the same payee (both would re-file the same rows) or another (both could create the
        // same new category).
        categoryRuleRepository.lockAnswers("recurring-answer:" + userId);
        Optional<RecurringDto> group = recurringService.detectForUser(userId).stream()
                .filter(r -> payeeKey(r.merchant()).equals(payeeKey(label)))
                .findFirst();
        Optional<CategoryRule> existing = answerKnownAs(userId, label);
        if (group.isEmpty() && existing.isEmpty()) throw notFound();
        // The rule is the answer's: a group under one of its printed names updates it rather than
        // starting a second answer for the same payee.
        String answerLabel = existing.map(CategoryRule::getComparisonValue).map(String::trim).orElse(label);

        java.util.Set<String> labels = new java.util.LinkedHashSet<>(List.of(payeeKey(label)));
        existing.ifPresent(rule -> labels.addAll(payeeLabelsOf(rule)));
        Map<String, List<Transaction>> rowsByPayee = expenseRowsByPayee(userId);
        List<Transaction> labelRows = rowsUnder(rowsByPayee, labels);
        List<String> aliases = payeeAliases(existing.map(CategoryRule::getPayeeAliases).orElse(List.of()), answerLabel,
                labelRows, claimedByOtherAnswers(userId, existing.map(CategoryRule::getId).orElse(null)));
        // The payee's payments the bank printed under another name, by their UPI id -- re-filed and
        // taken as the latest payment like the rest.
        List<Transaction> payeeRows = withRowsOfKeys(labelRows, rowsByPayee, aliases);
        // Never null: with a detected group the range is built from it; without one, the check above
        // guarantees a saved rule exists and its own range is the answer.
        Range range = group.isPresent()
                ? detectedRange(group.get(), payeeRows)
                : savedRange(existing.get());
        if (group.isPresent() && existing.isPresent()) {
            range = range.union(savedRange(existing.get()));
        }
        if (existing.isPresent()) {
            // "Still Rent?" for a payee whose amount moved: the answer now covers the latest payment.
            Optional<Transaction> latest = latestNotFiledByHand(payeeRows);
            if (latest.isPresent()) {
                range = range.union(Range.around(latest.get().getAmount(), tolerance(latest.get().getAmount())));
            }
        }

        Category category = categorizationService.resolveOrCreateCategory(userId, categoryName);
        String kind = existing.isEmpty() ? "FIRST"
                : category.getName().equalsIgnoreCase(existing.get().getActionValue()) ? "STILL" : "CHANGE";

        // Insert-or-nothing against uq_category_rules_user_payee, then one update path for every
        // answer: a concurrent second answer for the same payee updates the same rule.
        categoryRuleRepository.insertPayeeRuleIfAbsent(UUID.randomUUID(), userId, answerLabel, category.getName(),
                range.min(), range.max(), ANSWER_PRIORITY);
        CategoryRule rule = categoryRuleRepository.findUserPayeeRule(userId, answerLabel).orElseThrow();
        rule.setActionValue(category.getName());
        rule.setAmountMin(range.min());
        rule.setAmountMax(range.max());
        rule.setPriority(ANSWER_PRIORITY);
        rule.setEnabled(true);
        rule.setPayeeAliases(aliases);
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
        List<CategoryRule> answers = categoryRuleRepository.findUserPayeeRules(userId);
        if (answers.isEmpty()) return List.of();
        // A detected group reports AMOUNT_CHANGED on its own row, for the payments it holds; dismissed
        // payees the user asked not to see.
        Set<String> detected = recurringService.detectForUser(userId).stream()
                .map(r -> payeeKey(r.merchant()))
                .collect(Collectors.toSet());
        Set<String> dismissed = recurringService.dismissedPayees(userId).stream()
                .map(RecurringAnswerService::payeeKey)
                .collect(Collectors.toSet());
        // Dashboard and Insights call this on every load: read the user's rows once, not once per answer.
        Map<String, List<Transaction>> rowsByPayee = expenseRowsByPayee(userId);
        List<ChangedAmountDto> changed = new ArrayList<>();
        for (CategoryRule rule : answers) {
            String label = rule.getComparisonValue().trim();
            List<String> labels = payeeLabelsOf(rule);
            if (labels.stream().anyMatch(dismissed::contains)) continue;
            Optional<Transaction> latestRow = latestNotFiledByHand(
                    withRowsOfKeys(rowsUnder(rowsByPayee, labels), rowsByPayee, rule.getPayeeAliases()));
            if (latestRow.isEmpty()) continue;
            Transaction latest = latestRow.get();
            // Reported on the detected group that holds this payment. A group under another of the
            // answer's names holds only older payments -- the user renamed those, and the later one
            // arrived under the bank's name -- so it cannot show the change.
            if (detected.contains(payeeKey(latest.getMerchant()))) continue;
            if (new Range(rule.getAmountMin(), rule.getAmountMax()).contains(latest.getAmount())) continue;
            changed.add(new ChangedAmountDto(label, rule.getActionValue(), latest.getAmount(), latest.getTxnDate(),
                    rule.getAmountMin(), rule.getAmountMax()));
        }
        return changed;
    }

    /**
     * The latest of {@code rows} (oldest first) the user did not file by hand. A payment they filed
     * themselves -- a one-off deposit moved to its own category -- is already decided; asking "still
     * Rent?" about it, or widening Rent's range over it, would undo that.
     */
    private static Optional<Transaction> latestNotFiledByHand(List<Transaction> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (!rows.get(i).isCategoryManuallySet()) return Optional.of(rows.get(i));
        }
        return Optional.empty();
    }

    /**
     * The user's live-account money-out rows with a payee label, grouped by that label, each oldest
     * first -- without transfers and hidden duplicates, as RecurringService groups them. The question
     * is about those payments; an own-account transfer or a duplicate copy that shares the label is
     * neither re-filed by an answer nor taken as the payee's latest payment.
     */
    private Map<String, List<Transaction>> expenseRowsByPayee(UUID userId) {
        List<UUID> live = accountRepository.findByUserId(userId).stream().map(Account::getId).toList();
        if (live.isEmpty()) return Map.of();
        return transactionRepository.findByUserIdAndAccountIdIn(userId, live).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE && t.getMerchant() != null
                        && !t.isTransfer() && t.getIsDuplicateOf() == null)
                .sorted(Comparator.comparing(Transaction::getTxnDate))
                .collect(Collectors.groupingBy(t -> payeeKey(t.getMerchant())));
    }

    /**
     * The other ways the answer recognises this payee on a later import (V259), added to what it
     * already knew: the name the bank printed on each answered payment, when it is not the label the
     * user was asked about -- both apps let a user edit that label, and import reads the payee from
     * the narration -- and each payment's UPI id, when it names one payee, since a person's printed
     * name can vary between payments while the id stays the same. A gateway's own id or a cut id is
     * never kept: it carries other shops' payments too.
     */
    static List<String> payeeAliases(List<String> known, String label, List<Transaction> payeeRows,
                                     Set<String> claimedElsewhere) {
        java.util.TreeSet<String> aliases = new java.util.TreeSet<>(known == null ? List.of() : known);
        for (Transaction t : payeeRows) {
            String printed = CategoryRules.extractMerchantLabel(t.getDescription());
            if (printed != null && !printed.isBlank() && !payeeKey(printed).equals(payeeKey(label))) {
                aliases.add(RuleEngineService.PAYEE_LABEL_ALIAS + payeeKey(printed));
            }
            String key = t.getDescription() == null ? null : CounterpartyTyping.of(t.getDescription()).key();
            if (key != null && CounterpartyIdentity.identifiesOnePayee(key)) {
                aliases.add(RuleEngineService.PAYEE_KEY_ALIAS + key);
            }
        }
        // One payee, one answer: a name or id another answer already holds stays with it, so which
        // answer files a payment never depends on rule order.
        aliases.removeAll(claimedElsewhere);
        return new ArrayList<>(aliases);
    }

    /** Every alias the user's other answers hold, with each one's own label as a label alias.
     *  {@code ruleId} is the answer being saved, null when it is new. */
    private Set<String> claimedByOtherAnswers(UUID userId, UUID ruleId) {
        Set<String> claimed = new java.util.HashSet<>();
        for (CategoryRule other : categoryRuleRepository.findUserPayeeRules(userId)) {
            if (other.getId() != null && other.getId().equals(ruleId)) continue;
            claimed.addAll(other.getPayeeAliases());
            claimed.add(RuleEngineService.PAYEE_LABEL_ALIAS + payeeKey(other.getComparisonValue()));
        }
        return claimed;
    }

    /**
     * Every payee label an answer is known by, compared ignoring case: the one the user was asked
     * about, then the bank's printed names it recorded (V259). Later payments are stored under the
     * printed name, so detection, "still Rent?" and a new answer for them find this answer through it
     * rather than treating them as a payee never answered.
     */
    static List<String> payeeLabelsOf(CategoryRule rule) {
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        labels.add(payeeKey(rule.getComparisonValue()));
        for (String alias : rule.getPayeeAliases()) {
            if (alias.startsWith(RuleEngineService.PAYEE_LABEL_ALIAS)) {
                String printed = alias.substring(RuleEngineService.PAYEE_LABEL_ALIAS.length());
                if (!printed.isBlank()) labels.add(payeeKey(printed));
            }
        }
        return new ArrayList<>(labels);
    }

    /** The user's answer for this payee label: one asked about under it, else one that knows it as a
     *  printed name. */
    private Optional<CategoryRule> answerKnownAs(UUID userId, String label) {
        Optional<CategoryRule> direct = categoryRuleRepository.findUserPayeeRule(userId, label);
        if (direct.isPresent()) return direct;
        String key = payeeKey(label);
        return categoryRuleRepository.findUserPayeeRules(userId).stream()
                .filter(rule -> payeeLabelsOf(rule).contains(key))
                .findFirst();
    }

    /**
     * {@code rows} plus the user's other payee rows whose UPI id is one of {@code aliases}' keys,
     * oldest first. Their own printed names are not taken as aliases: a drifted name is often a bare
     * word ("owner"), which would file other payees too.
     */
    private static List<Transaction> withRowsOfKeys(List<Transaction> rows, Map<String, List<Transaction>> rowsByPayee,
                                                    List<String> aliases) {
        Set<String> keys = aliases.stream()
                .filter(a -> a.startsWith(RuleEngineService.PAYEE_KEY_ALIAS))
                .map(a -> a.substring(RuleEngineService.PAYEE_KEY_ALIAS.length()))
                .collect(Collectors.toSet());
        if (keys.isEmpty()) return rows;
        Set<UUID> seen = rows.stream().map(Transaction::getId).collect(Collectors.toSet());
        List<Transaction> all = new ArrayList<>(rows);
        rowsByPayee.values().stream().flatMap(List::stream)
                .filter(t -> t.getCounterpartyKey() != null && keys.contains(t.getCounterpartyKey()))
                .filter(t -> seen.add(t.getId()))
                .forEach(all::add);
        all.sort(Comparator.comparing(Transaction::getTxnDate));
        return all;
    }

    /** The rows under any of {@code labels}, oldest first. */
    private static List<Transaction> rowsUnder(Map<String, List<Transaction>> rowsByPayee, java.util.Collection<String> labels) {
        return labels.stream().map(RecurringAnswerService::payeeKey).distinct()
                .flatMap(k -> rowsByPayee.getOrDefault(k, List.of()).stream())
                .sorted(Comparator.comparing(Transaction::getTxnDate))
                .toList();
    }

    /** Payee labels compare ignoring case, as the PAYEE rule and its unique index do. */
    private static String payeeKey(String label) {
        return label.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "No recurring payment found for that payee.");
    }
}
