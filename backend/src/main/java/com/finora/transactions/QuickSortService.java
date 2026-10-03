package com.finora.transactions;

import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.util.CounterpartyIdentity;
import com.finora.util.PayeeLabel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quick sort: the rows waiting for review, asked as a few payee questions, biggest money first.
 *
 * <h2>Why</h2>
 *
 * <p>Measured on a tester's account (2026-10-03): 210 rows waited for review from 145 payees, and
 * the review screens asked about them one row at a time with the full category list. The money is
 * concentrated -- the 10 biggest payees held 78% of it, the 20 biggest 89% -- so a few questions
 * cover most of what matters. One question per payee files every waiting payment to it (see
 * {@link #batch} and the answer endpoint).
 *
 * <h2>The batch</h2>
 *
 * <p>Groups are ranked by money, then by their latest payment, then by id, so a batch is
 * deterministic. A batch takes groups until it covers {@code coverageTarget} of the money from the
 * skip point on, or {@code maxQuestions} -- both properties, because 80% rests on one account.
 * A key that names no one payee (CounterpartyIdentity.identifiesOnePayee) never groups: each of its
 * rows is its own question, so one answer cannot file a stranger's payment (#1930, #1947).
 */
@Service
public class QuickSortService {

    static final BigDecimal LARGE_ONE_OFF = new BigDecimal("5000");
    /** No spending category is offered for a person by default: nothing in the narration says what
     *  a payment to a person was for, and a ready-made "Groceries" button would nudge a guess. */
    static final List<String> PERSON_DEFAULTS = List.of("Personal Transfer", "Friend Repayment");
    static final List<String> SHOP_DEFAULTS = List.of("Groceries", "Dining", "Shopping", "Transport", "Health");
    static final List<String> MONEY_IN_DEFAULTS = List.of("Friend Repayment", "Salary", "Gifts & Donations", "Transfer");
    static final int MAX_ANSWERS = 5;
    static final int MAX_SAMPLES = 3;

    @Value("${app.quick-sort.coverage-target:0.80}")
    private BigDecimal coverageTarget;

    @Value("${app.quick-sort.max-questions:10}")
    private int maxQuestions;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;

    public QuickSortService(TransactionRepository transactionRepository, AccountRepository accountRepository,
                            CategoryRepository categoryRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
    }

    /** One batch of questions, starting after the first {@code skip} groups (ones the user skipped). */
    @Transactional(readOnly = true)
    public QuickSortDto.Batch batch(UUID userId, int skip) {
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream().map(Account::getId).toList();
        if (liveAccountIds.isEmpty()) return empty();
        List<Group> groups = groups(transactionRepository
                .findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(userId, liveAccountIds));
        if (groups.isEmpty()) return empty();

        BigDecimal waitingTotal = sum(groups, 0, groups.size());
        int start = Math.min(Math.max(skip, 0), groups.size());
        BigDecimal target = sum(groups, start, groups.size()).multiply(coverageTarget);
        int end = start;
        BigDecimal covered = BigDecimal.ZERO;
        while (end < groups.size() && end - start < maxQuestions
                && (end == start || covered.compareTo(target) < 0)) {
            covered = covered.add(groups.get(end).total);
            end++;
        }

        Map<UUID, String> categoryNames = new HashMap<>();
        for (Category c : categoryRepository.findByUserId(userId)) categoryNames.put(c.getId(), c.getName());
        Map<String, List<String>> ownChoices = new HashMap<>();
        List<QuickSortDto.Question> questions = new ArrayList<>();
        for (Group g : groups.subList(start, end)) {
            questions.add(question(userId, g, categoryNames, ownChoices));
        }

        List<UUID> restIds = new ArrayList<>();
        int restPayments = 0;
        for (Group g : groups.subList(end, groups.size())) {
            restPayments += g.rows.size();
            g.rows.forEach(t -> restIds.add(t.getId()));
        }
        return new QuickSortDto.Batch(questions, waitingTotal,
                new QuickSortDto.Rest(groups.size() - end, restPayments, sum(groups, end, groups.size()), restIds));
    }

    private QuickSortDto.Question question(UUID userId, Group g, Map<UUID, String> categoryNames,
                                           Map<String, List<String>> ownChoices) {
        Transaction anchor = g.rows.get(0);
        QuickSortDto.Kind kind = kindOf(anchor);
        String current = categoryNames.get(anchor.getCategoryId());

        Set<String> answers = new LinkedHashSet<>();
        if (kind == QuickSortDto.Kind.GUESS && current != null) answers.add(current);
        String choicesKey = anchor.getCounterpartyType() + "|" + anchor.getTxnType();
        answers.addAll(ownChoices.computeIfAbsent(choicesKey, k ->
                transactionRepository.countManualChoicesByCategory(userId, anchor.getCounterpartyType(), anchor.getTxnType())
                        .stream().map(c -> categoryNames.get(c.getCategoryId())).filter(n -> n != null).toList()));
        answers.addAll(defaultsFor(kind));
        List<String> offered = answers.stream().filter(categoryNames::containsValue).limit(MAX_ANSWERS).toList();

        List<QuickSortDto.Sample> samples = g.rows.stream().limit(MAX_SAMPLES)
                .map(t -> new QuickSortDto.Sample(t.getId(), t.getTxnDate(), t.getDescription(), t.getAmount(),
                        t.getTxnType().name()))
                .toList();
        boolean largeOneOff = g.rows.size() == 1 && anchor.getAmount().abs().compareTo(LARGE_ONE_OFF) >= 0;
        return new QuickSortDto.Question(g.id, anchor.getId(), kind, PayeeLabel.of(anchor, "Unknown payee"),
                g.rows.size(), g.total, anchor.getTxnDate(), largeOneOff, current, offered, samples);
    }

    static QuickSortDto.Kind kindOf(Transaction anchor) {
        if (anchor.getTxnType() == Transaction.Type.INCOME) return QuickSortDto.Kind.MONEY_IN;
        Transaction.DecisionSource source = anchor.getDecisionSource();
        if (source == Transaction.DecisionSource.STRUCTURAL_P2P) return QuickSortDto.Kind.PERSON_PAID;
        if (source == Transaction.DecisionSource.MERCHANT_DEFAULT) return QuickSortDto.Kind.SHOP;
        return QuickSortDto.Kind.GUESS;
    }

    private static List<String> defaultsFor(QuickSortDto.Kind kind) {
        return switch (kind) {
            case PERSON_PAID -> PERSON_DEFAULTS;
            case MONEY_IN -> MONEY_IN_DEFAULTS;
            case SHOP, GUESS -> SHOP_DEFAULTS;
        };
    }

    /** Waiting rows (latest first) as payee groups, ranked: money, then latest payment, then id. */
    private static List<Group> groups(List<Transaction> rows) {
        Map<String, Group> byId = new LinkedHashMap<>();
        for (Transaction t : rows) {
            if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.DUPLICATE) continue;
            String key = t.getCounterpartyKey();
            String id = CounterpartyIdentity.identifiesOnePayee(key) ? key + "|" + t.getTxnType() : "row:" + t.getId();
            byId.computeIfAbsent(id, Group::new).add(t);
        }
        List<Group> groups = new ArrayList<>(byId.values());
        groups.sort(Comparator.comparing((Group g) -> g.total).reversed()
                .thenComparing((Group g) -> g.rows.get(0).getTxnDate(), Comparator.reverseOrder())
                .thenComparing(g -> g.id));
        return groups;
    }

    private static BigDecimal sum(List<Group> groups, int from, int to) {
        BigDecimal total = BigDecimal.ZERO;
        for (Group g : groups.subList(from, to)) total = total.add(g.total);
        return total;
    }

    private static QuickSortDto.Batch empty() {
        return new QuickSortDto.Batch(List.of(), BigDecimal.ZERO, new QuickSortDto.Rest(0, 0, BigDecimal.ZERO, List.of()));
    }

    private static final class Group {
        final String id;
        final List<Transaction> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        Group(String id) { this.id = id; }

        void add(Transaction t) {
            rows.add(t);
            total = total.add(t.getAmount().abs());
        }
    }
}
