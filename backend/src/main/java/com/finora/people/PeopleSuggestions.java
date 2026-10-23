package com.finora.people;

import com.finora.entity.LoanEntry;
import com.finora.entity.SplitRule;
import com.finora.entity.Transaction;
import com.finora.service.FlowClassifier;
import com.finora.util.CounterpartyIdentity;
import com.finora.util.CounterpartyType;
import com.finora.util.PayeeLabel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Insights rows that ask about money with people (spec §6 A-G), and the shares expected in. Pure
 * and deterministic: the person, the user's category, dates, amounts and words in the UPI note. No
 * AI; nothing here changes a figure -- every row waits for the user's answer.
 */
public final class PeopleSuggestions {

    /** At most this many rows at once, so a long history is asked about a little at a time. */
    static final int MAX_ITEMS = 5;
    static final int BILL_WINDOW_DAYS = 14;
    static final int LENT_AFTER_DAYS = 30;
    static final int SETTLE_AFTER_DAYS = 60;
    static final int LOOK_BACK_DAYS = 180;
    static final int MAX_BILL_OPTIONS = 5;
    /** Questions only about money this recent, and at most MAX_LENT_CHECKS "lent?" rows at once: a user
     *  who pays people often would otherwise get one question per payment, all of their history. */
    static final int ASK_WITHIN_DAYS = 90;
    static final int MAX_LENT_CHECKS = 2;

    /** Categories that say nothing about what a credit was for. */
    private static final Set<String> NOT_SPENDING = Set.of(
            "friend repayment", "personal transfer", "other", "transfer", "salary", "uncategorized");
    private static final Pattern SHARE_WORDS = Pattern.compile("(?i)\\b(rent|share|split|trip|dinner|lunch|bill)\\b");
    private static final List<String> ORDER = List.of(
            "SHARE_CHECK", "LEFTOVER_CHECK", "PAYBACK_CHECK", "EVERY_MONTH", "ALWAYS_PERSON", "LENT_CHECK", "SETTLE");

    /** {@code relatedTxnId}: the earlier payment a PAYBACK_CHECK would mark as lent, or the bill an
     *  EVERY_MONTH rule is made from. {@code owed}: what the person owes, when they owe. */
    public record Suggestion(String kind, String ackKey, UUID txnId, UUID relatedTxnId, String personKey, String label,
                             BigDecimal amount, LocalDate date, String category, List<BillOption> bills, BigDecimal owed) {}

    public record BillOption(UUID billId, String category, String payeeLabel, LocalDate date, BigDecimal amount,
                             BigDecimal remaining) {}

    public record ExpectedShare(String personKey, String label, BigDecimal usualAmount, int usualDay, boolean late, UUID ruleId) {}

    public record PeopleSuggestionsResult(List<Suggestion> items, List<ExpectedShare> expected) {}

    /** {@code acknowledged}: "KIND|ackKey" for every row the user marked "Got it". */
    public record Input(PeopleLedger ledger, Collection<Transaction> rows, Map<UUID, String> categoryNames,
                        Set<String> acknowledged, Set<String> familyKeys, Collection<SplitRule> rules,
                        Collection<LoanEntry> entries, Set<String> repaymentKeys, LocalDate today,
                        Set<String> kindSenderKeys) {}

    private PeopleSuggestions() {}

    public static PeopleSuggestionsResult build(Input in) {
        List<Transaction> live = in.rows().stream()
                .filter(t -> t.getId() != null && t.getTxnDate() != null && t.getAmount() != null
                        && t.getIsDuplicateOf() == null
                        && t.getReconciliationStatus() != Transaction.ReconciliationStatus.SUPERSEDED)
                .toList();
        Set<UUID> entryTxns = new HashSet<>();
        for (LoanEntry e : in.entries()) if (e.getTxnId() != null) entryTxns.add(e.getTxnId());
        Set<UUID> sharedCredits = new HashSet<>();
        for (PeopleLedger.Share s : in.ledger().allShares()) sharedCredits.add(s.creditId());
        Map<String, Integer> monthsPaid = monthsPaidByKey(live);

        List<Suggestion> items = new ArrayList<>();

        for (Transaction c : live) {
            if (!PeopleLedger.isCredit(c) || !isPerson(c) || in.familyKeys().contains(c.getCounterpartyKey())) continue;
            if (ChronoUnit.DAYS.between(c.getTxnDate(), in.today()) > ASK_WITHIN_DAYS) continue;
            // The user already said what this money is: a kind on the payment, or on its sender.
            if (c.getInflowKindId() != null || in.kindSenderKeys().contains(c.getCounterpartyKey())) continue;
            // D. the part of a credit left over after the bills it is linked to: they owed you, or borrowed?
            if (sharedCredits.contains(c.getId()) && !entryTxns.contains(c.getId())
                    && CounterpartyIdentity.identifiesOnePayee(c.getCounterpartyKey())) {
                BigDecimal leftover = c.getAmount().subtract(in.ledger().sharedFromCredit(c.getId()));
                if (leftover.signum() > 0) {
                    items.add(new Suggestion("LEFTOVER_CHECK", c.getId().toString(), c.getId(), null, c.getCounterpartyKey(),
                            label(c), leftover, c.getTxnDate(), name(in, c.getCategoryId()), List.of(), null));
                }
                continue;
            }
            if (sharedCredits.contains(c.getId()) || entryTxns.contains(c.getId()) || in.ledger().creditDecision(c) != null) continue;
            // A. a share of a bill?
            String category = name(in, c.getCategoryId());
            boolean spendingCategory = category != null && !NOT_SPENDING.contains(category.toLowerCase(Locale.ROOT))
                    && FlowClassifier.categoryIsTheUsersChoice(c);
            boolean repaymentWithNothingBefore = "friend repayment".equalsIgnoreCase(category)
                    && !anyEarlierPaymentTo(live, c);
            boolean noteWord = c.getDescription() != null && SHARE_WORDS.matcher(c.getDescription()).find();
            if (spendingCategory || repaymentWithNothingBefore || noteWord) {
                items.add(new Suggestion("SHARE_CHECK", c.getId().toString(), c.getId(), null, c.getCounterpartyKey(),
                        label(c), c.getAmount(), c.getTxnDate(), category, billOptions(in, live, c), null));
                continue;
            }
            // C. paying back what you lent?
            String key = c.getCounterpartyKey();
            if (!CounterpartyIdentity.identifiesOnePayee(key) || in.repaymentKeys().contains(key)) continue;
            PeopleLedger.Balance balance = in.ledger().balances().get(key);
            if (balance != null && balance.owedToUser().signum() > 0) {
                items.add(new Suggestion("PAYBACK_CHECK", c.getId().toString(), c.getId(), null, key, label(c),
                        c.getAmount(), c.getTxnDate(), category, List.of(), balance.owedToUser()));
                continue;
            }
            Transaction earlier = earlierPaymentTo(live, c, LOOK_BACK_DAYS, entryTxns, in.ledger(), monthsPaid);
            if (earlier != null) {
                items.add(new Suggestion("PAYBACK_CHECK", c.getId().toString(), c.getId(), earlier.getId(), key, label(c),
                        c.getAmount(), c.getTxnDate(), category, List.of(), null));
            }
        }

        // B. lent, or paid for something?
        for (Transaction d : live) {
            if (!PeopleLedger.isBill(d) || !isPerson(d)) continue;
            String key = d.getCounterpartyKey();
            if (!CounterpartyIdentity.identifiesOnePayee(key) || in.familyKeys().contains(key)) continue;
            if (entryTxns.contains(d.getId()) || in.ledger().droppedIds().contains(d.getId())
                    || !in.ledger().sharesOf(d.getId()).isEmpty()) continue;
            if (monthsPaid.getOrDefault(key, 0) >= 3) continue;
            String category = name(in, d.getCategoryId());
            if (category != null && !NOT_SPENDING.contains(category.toLowerCase(Locale.ROOT))
                    && FlowClassifier.categoryIsTheUsersChoice(d)) continue;
            long age = ChronoUnit.DAYS.between(d.getTxnDate(), in.today());
            if (age < LENT_AFTER_DAYS || age > ASK_WITHIN_DAYS) continue;
            boolean cameBack = live.stream().anyMatch(c -> PeopleLedger.isCredit(c) && key.equals(c.getCounterpartyKey())
                    && c.getTxnDate().isAfter(d.getTxnDate())
                    && !c.getTxnDate().isAfter(d.getTxnDate().plusDays(LENT_AFTER_DAYS)));
            if (cameBack) continue;
            items.add(new Suggestion("LENT_CHECK", d.getId().toString(), d.getId(), null, key, label(d),
                    d.getAmount(), d.getTxnDate(), category, List.of(), null));
        }

        // E. settle it?
        Map<String, LoanEntry> newestEntry = new HashMap<>();
        for (LoanEntry e : in.entries()) {
            LoanEntry seen = newestEntry.get(e.getPersonKey());
            if (seen == null || createdAt(e).isAfter(createdAt(seen))) newestEntry.put(e.getPersonKey(), e);
        }
        for (PeopleLedger.Balance b : in.ledger().balances().values()) {
            if (b.owedToUser().signum() <= 0 || b.since() == null || in.familyKeys().contains(b.personKey())) continue;
            if (ChronoUnit.DAYS.between(b.since(), in.today()) < SETTLE_AFTER_DAYS) continue;
            LoanEntry newest = newestEntry.get(b.personKey());
            String ack = b.personKey() + "|" + (newest == null ? "" : newest.getId());
            items.add(new Suggestion("SETTLE", ack, null, null, b.personKey(), labelOfKey(live, b.personKey()),
                    b.owedToUser(), b.since(), null, List.of(), b.owedToUser()));
        }

        // F. every month?
        Map<String, Suggestion> everyMonth = new LinkedHashMap<>();
        Map<UUID, Transaction> byId = new HashMap<>();
        live.forEach(t -> byId.put(t.getId(), t));
        for (PeopleLedger.Share s : in.ledger().allShares()) {
            if (s.fromRule()) continue;
            Transaction c = byId.get(s.creditId());
            Transaction bill = byId.get(s.billId());
            if (c == null || bill == null || bill.getCategoryId() == null) continue;
            String sender = c.getCounterpartyKey();
            if (!CounterpartyIdentity.identifiesOnePayee(sender)) continue;
            boolean ruled = in.rules().stream().anyMatch(r -> r.isActive() && r.getSenderKey().equals(sender)
                    && r.getBillCategoryId().equals(bill.getCategoryId()));
            if (ruled || !sameShapeInAnotherMonth(live, c, bill)) continue;
            String ack = sender + "|" + bill.getCategoryId();
            Suggestion existing = everyMonth.get(ack);
            if (existing == null || c.getTxnDate().isAfter(existing.date())) {
                everyMonth.put(ack, new Suggestion("EVERY_MONTH", ack, c.getId(), bill.getId(), sender, label(c),
                        s.amount(), c.getTxnDate(), name(in, bill.getCategoryId()), List.of(), null));
            }
        }
        items.addAll(everyMonth.values());

        // G. always for this person?
        Map<String, Suggestion> always = new LinkedHashMap<>();
        for (LoanEntry e : in.entries()) {
            if (e.getDirection() != LoanEntry.Direction.PAID_BACK || e.getTxnId() == null) continue;
            String key = e.getPersonKey();
            if (!CounterpartyIdentity.identifiesOnePayee(key) || in.repaymentKeys().contains(key)) continue;
            Transaction c = byId.get(e.getTxnId());
            if (c == null) continue;
            Suggestion existing = always.get(key);
            if (existing == null || c.getTxnDate().isAfter(existing.date())) {
                always.put(key, new Suggestion("ALWAYS_PERSON", key, c.getId(), null, key, label(c),
                        c.getAmount(), c.getTxnDate(), null, List.of(), null));
            }
        }
        items.addAll(always.values());

        int[] lentShown = {0};
        List<Suggestion> shown = items.stream()
                .filter(s -> !in.acknowledged().contains(s.kind() + "|" + s.ackKey()))
                .sorted(Comparator.comparing((Suggestion s) -> ORDER.indexOf(s.kind()))
                        .thenComparing(Suggestion::date, Comparator.nullsLast(Comparator.reverseOrder())))
                .filter(s -> !s.kind().equals("LENT_CHECK") || lentShown[0]++ < MAX_LENT_CHECKS)
                .limit(MAX_ITEMS)
                .toList();
        return new PeopleSuggestionsResult(shown, expected(in, byId));
    }

    /** For each split rule: the sender's latest share and the day it usually comes; late once that day
     *  has passed this month with nothing from them. */
    private static List<ExpectedShare> expected(Input in, Map<UUID, Transaction> byId) {
        List<ExpectedShare> out = new ArrayList<>();
        YearMonth thisMonth = YearMonth.from(in.today());
        for (SplitRule r : in.rules()) {
            if (!r.isActive() || !CounterpartyIdentity.identifiesOnePayee(r.getSenderKey())) continue;
            Transaction latest = null;
            BigDecimal amount = BigDecimal.ZERO;
            for (PeopleLedger.Share s : in.ledger().allShares()) {
                Transaction c = byId.get(s.creditId());
                if (c == null || !r.getSenderKey().equals(c.getCounterpartyKey())) continue;
                if (latest == null || c.getTxnDate().isAfter(latest.getTxnDate())) { latest = c; amount = s.amount(); }
                else if (c.getId().equals(latest.getId())) amount = amount.add(s.amount());
            }
            if (latest == null) continue;
            boolean cameThisMonth = YearMonth.from(latest.getTxnDate()).equals(thisMonth);
            if (cameThisMonth) continue;
            int day = latest.getTxnDate().getDayOfMonth();
            boolean late = in.today().getDayOfMonth() > day;
            out.add(new ExpectedShare(r.getSenderKey(), label(latest), amount, day, late, r.getId()));
        }
        return out;
    }

    /** Bills this credit could be a share of: same category first, then the rest; within each, the
     *  ones before the credit (nearest first), then after. */
    private static List<BillOption> billOptions(Input in, List<Transaction> live, Transaction credit) {
        LocalDate day = credit.getTxnDate();
        List<Transaction> candidates = live.stream()
                .filter(PeopleLedger::isBill)
                .filter(b -> !Objects.equals(b.getCounterpartyKey(), credit.getCounterpartyKey()))
                .filter(b -> !in.ledger().droppedIds().contains(b.getId()))
                .filter(b -> Math.abs(ChronoUnit.DAYS.between(day, b.getTxnDate())) <= BILL_WINDOW_DAYS)
                .filter(b -> remaining(in, b).signum() > 0)
                .sorted(Comparator
                        .comparing((Transaction b) -> Objects.equals(b.getCategoryId(), credit.getCategoryId()) ? 0 : 1)
                        .thenComparing(b -> b.getTxnDate().isAfter(day) ? 1 : 0)
                        .thenComparing(b -> Math.abs(ChronoUnit.DAYS.between(day, b.getTxnDate()))))
                .limit(MAX_BILL_OPTIONS)
                .toList();
        return candidates.stream().map(b -> new BillOption(b.getId(), name(in, b.getCategoryId()), label(b),
                b.getTxnDate(), b.getAmount(), remaining(in, b))).toList();
    }

    private static BigDecimal remaining(Input in, Transaction bill) {
        return bill.getAmount().subtract(in.ledger().sharedByBillId().getOrDefault(bill.getId(), BigDecimal.ZERO));
    }

    /** The nearest unanswered payment to the same person in the {@code days} before this credit. */
    private static Transaction earlierPaymentTo(List<Transaction> live, Transaction credit, int days, Set<UUID> entryTxns,
                                                PeopleLedger ledger, Map<String, Integer> monthsPaid) {
        String key = credit.getCounterpartyKey();
        if (key == null || monthsPaid.getOrDefault(key, 0) >= 3) return null;
        return live.stream()
                .filter(d -> PeopleLedger.isBill(d) && key.equals(d.getCounterpartyKey()))
                .filter(d -> !d.getTxnDate().isAfter(credit.getTxnDate())
                        && !d.getTxnDate().isBefore(credit.getTxnDate().minusDays(days)))
                .filter(d -> !entryTxns.contains(d.getId()) && !ledger.droppedIds().contains(d.getId()))
                .max(Comparator.comparing(Transaction::getTxnDate))
                .orElse(null);
    }

    private static boolean anyEarlierPaymentTo(List<Transaction> live, Transaction credit) {
        return live.stream().anyMatch(d -> PeopleLedger.isBill(d) && Objects.equals(d.getCounterpartyKey(), credit.getCounterpartyKey())
                && !d.getTxnDate().isAfter(credit.getTxnDate()));
    }

    /** Another month where this sender's credit sits within the window of a bill of the same identity. */
    private static boolean sameShapeInAnotherMonth(List<Transaction> live, Transaction credit, Transaction bill) {
        YearMonth month = YearMonth.from(credit.getTxnDate());
        return live.stream().filter(c -> PeopleLedger.isCredit(c) && credit.getCounterpartyKey().equals(c.getCounterpartyKey())
                        && !YearMonth.from(c.getTxnDate()).equals(month))
                .anyMatch(c -> live.stream().anyMatch(b -> PeopleLedger.isBill(b)
                        && Objects.equals(b.getCategoryId(), bill.getCategoryId())
                        && Objects.equals(b.getCounterpartyKey(), bill.getCounterpartyKey())
                        && Math.abs(ChronoUnit.DAYS.between(c.getTxnDate(), b.getTxnDate())) <= BILL_WINDOW_DAYS));
    }

    /** Distinct months each key was paid in: three or more is a repeating payment, never a loan. */
    private static Map<String, Integer> monthsPaidByKey(List<Transaction> live) {
        Map<String, Set<YearMonth>> months = new HashMap<>();
        for (Transaction t : live) {
            if (PeopleLedger.isBill(t) && t.getCounterpartyKey() != null) {
                months.computeIfAbsent(t.getCounterpartyKey(), k -> new HashSet<>()).add(YearMonth.from(t.getTxnDate()));
            }
        }
        Map<String, Integer> out = new HashMap<>();
        months.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    private static boolean isPerson(Transaction t) {
        return t.getCounterpartyType() == CounterpartyType.PERSON && t.getCounterpartyKey() != null;
    }

    private static String name(Input in, UUID categoryId) {
        return categoryId == null ? null : in.categoryNames().get(categoryId);
    }

    private static String label(Transaction t) { return PayeeLabel.of(t, "Unknown"); }

    private static String labelOfKey(List<Transaction> live, String key) {
        return live.stream().filter(t -> key.equals(t.getCounterpartyKey()))
                .max(Comparator.comparing(Transaction::getTxnDate)).map(PeopleSuggestions::label).orElse("Unknown");
    }

    private static Instant createdAt(LoanEntry e) { return e.getCreatedAt() == null ? Instant.EPOCH : e.getCreatedAt(); }
}
