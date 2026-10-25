package com.finora.people;

import com.finora.entity.LoanEntry;
import com.finora.entity.ShareLink;
import com.finora.entity.SplitRule;
import com.finora.entity.Transaction;
import com.finora.service.FlowClassifier;
import com.finora.util.CounterpartyIdentity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the user's confirmed shares, split rules and loan entries mean for their figures
 * (docs/superpowers/specs/2026-10-18-shared-payments-and-loans-design.md §4-5). Pure: built from the
 * rows and the user's answers, nothing stored.
 *
 * <p>Rules are applied here, at read time, and never copied into share_links: a rule covers every past
 * month it matches, and removing it undoes every share it made. A row that is not among {@code rows},
 * or is a duplicate or superseded, makes every link and entry naming it vanish -- a re-upload that
 * replaced a credit must not keep lowering a bill.
 */
public final class PeopleLedger {

    /** How far either side of a credit a split rule looks for its bill (spec §6 A). */
    static final int RULE_WINDOW_DAYS = 14;

    public static final PeopleLedger EMPTY = new PeopleLedger(Map.of(), Map.of(), Set.of(), Map.of(), Map.of());

    public record Share(UUID creditId, UUID billId, BigDecimal amount, boolean fromRule) {}

    /** Positive: the person owes the user. Negative: the user owes them. {@code since}: the oldest
     *  date still open on that side. */
    public record Balance(String personKey, BigDecimal owedToUser, LocalDate since) {}

    private static final FlowClassifier.FlowDecision SHARE = new FlowClassifier.FlowDecision(
            FlowClassifier.FlowClass.ADJUSTMENT, FlowClassifier.FlowReason.SHARE_OF_BILL);
    private static final FlowClassifier.FlowDecision PAID_BACK = new FlowClassifier.FlowDecision(
            FlowClassifier.FlowClass.ADJUSTMENT, FlowClassifier.FlowReason.PAID_BACK);
    private static final FlowClassifier.FlowDecision BORROWED = new FlowClassifier.FlowDecision(
            FlowClassifier.FlowClass.LIABILITY, FlowClassifier.FlowReason.BORROWED);

    private final Map<UUID, BigDecimal> sharedByBillId;
    private final Map<UUID, List<Share>> sharesByBillId;
    private final Set<UUID> droppedIds;
    private final Map<UUID, FlowClassifier.FlowDecision> creditDecisions;
    private final Map<String, Balance> balances;

    private PeopleLedger(Map<UUID, BigDecimal> sharedByBillId, Map<UUID, List<Share>> sharesByBillId, Set<UUID> droppedIds,
                         Map<UUID, FlowClassifier.FlowDecision> creditDecisions, Map<String, Balance> balances) {
        this.sharedByBillId = sharedByBillId;
        this.sharesByBillId = sharesByBillId;
        this.droppedIds = droppedIds;
        this.creditDecisions = creditDecisions;
        this.balances = balances;
    }

    /** Bill id -> the total of its shares, never more than the bill. */
    public Map<UUID, BigDecimal> sharedByBillId() { return sharedByBillId; }

    /** Rows no report counts: every credit with a share, every LENT or REPAID debit not let go. */
    public Set<UUID> droppedIds() { return droppedIds; }

    /** The ledger's reading of a credit, or null when it says nothing about it. */
    public FlowClassifier.FlowDecision creditDecision(Transaction t) {
        return t == null || t.getId() == null ? null : creditDecisions.get(t.getId());
    }

    public List<Share> sharesOf(UUID billId) { return sharesByBillId.getOrDefault(billId, List.of()); }

    /** Every share, all bills. */
    public List<Share> allShares() {
        return sharesByBillId.values().stream().flatMap(List::stream).toList();
    }

    /** How much of this credit is already linked as shares. */
    public BigDecimal sharedFromCredit(UUID creditId) {
        return allShares().stream().filter(s -> s.creditId().equals(creditId))
                .map(Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** By person key; a settled person (balance exactly zero) is absent. */
    public Map<String, Balance> balances() { return balances; }

    public static PeopleLedger build(Collection<Transaction> rows, Collection<ShareLink> links,
                                     Collection<SplitRule> rules, Collection<LoanEntry> entries,
                                     Set<String> repaymentSenderKeys) {
        Map<UUID, Transaction> live = new HashMap<>();
        for (Transaction t : rows) {
            if (t.getId() != null && isLive(t)) live.put(t.getId(), t);
        }

        Map<UUID, BigDecimal> billLeft = new HashMap<>();
        Map<UUID, BigDecimal> creditLeft = new HashMap<>();
        Map<UUID, List<Share>> sharesByBill = new LinkedHashMap<>();
        Set<UUID> dropped = new HashSet<>();
        Map<UUID, FlowClassifier.FlowDecision> decisions = new HashMap<>();

        // Credits the user answered with a loan entry: a rule never reads them as a share.
        Set<UUID> answered = new HashSet<>();
        for (LoanEntry e : entries) if (e.getTxnId() != null) answered.add(e.getTxnId());

        // 1. Explicit links, oldest first.
        Set<UUID> explicitlyLinked = new HashSet<>();
        links.stream().sorted(Comparator.comparing(ShareLink::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(link -> {
                    Transaction credit = live.get(link.getCreditTxnId());
                    Transaction bill = live.get(link.getBillTxnId());
                    if (!isCredit(credit) || !isBill(bill)) return;
                    explicitlyLinked.add(credit.getId());
                    BigDecimal amount = min(link.getAmount(), left(creditLeft, credit), left(billLeft, bill));
                    record(credit, bill, amount, false, creditLeft, billLeft, sharesByBill);
                });

        // 2. Split rules: credits from the sender with no explicit link and no loan entry, in date order.
        List<Transaction> byDate = live.values().stream()
                .sorted(Comparator.comparing(Transaction::getTxnDate).thenComparing(Transaction::getId)).toList();
        for (SplitRule rule : rules) {
            if (!rule.isActive() || !CounterpartyIdentity.identifiesOnePayee(rule.getSenderKey())) continue;
            for (Transaction credit : byDate) {
                if (!isCredit(credit) || !rule.getSenderKey().equals(credit.getCounterpartyKey())) continue;
                if (explicitlyLinked.contains(credit.getId()) || answered.contains(credit.getId())) continue;
                for (Transaction bill : ruleCandidates(rule, credit, byDate)) {
                    // A bill the user answered as a loan is not a shared bill.
                    if (answered.contains(bill.getId())) continue;
                    BigDecimal amount = left(creditLeft, credit).min(left(billLeft, bill));
                    if (amount.signum() <= 0) continue;
                    record(credit, bill, amount, true, creditLeft, billLeft, sharesByBill);
                    if (left(creditLeft, credit).signum() <= 0) break;
                }
            }
        }

        Map<UUID, BigDecimal> shared = new LinkedHashMap<>();
        sharesByBill.forEach((billId, list) -> {
            BigDecimal sum = list.stream().map(Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.signum() > 0) shared.put(billId, sum);
            for (Share s : list) {
                dropped.add(s.creditId());
                decisions.put(s.creditId(), SHARE);
            }
        });

        // 3. Loan entries, each for what its payment says now (entryAmount): an entry whose payment was
        //    corrected follows the correction, and one whose payment no longer fits it is gone.
        Map<UUID, BigDecimal> sharedFromCredit = new HashMap<>();
        sharesByBill.values().forEach(list -> list.forEach(s -> sharedFromCredit.merge(s.creditId(), s.amount(), BigDecimal::add)));
        List<Event> events = new ArrayList<>();
        for (LoanEntry e : entries) {
            Transaction t = e.getTxnId() == null ? null : live.get(e.getTxnId());
            BigDecimal amount = entryAmount(e, t, t == null ? BigDecimal.ZERO : sharedFromCredit.getOrDefault(t.getId(), BigDecimal.ZERO));
            if (amount == null) continue;
            LocalDate when = t != null ? t.getTxnDate()
                    : e.getCreatedAt() == null ? LocalDate.now(ZoneOffset.UTC) : e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            Cap cap = switch (e.getDirection()) {
                // Money back moves only a balance they owe: past it, it flips (more back than lent), but
                // with nothing owed it is a debt Fynora never saw ("they owed you"), not money the user
                // owes -- or, when the user let a loan of theirs go, that loan coming back after all.
                case PAID_BACK -> Cap.WHILE_OWED;
                // "Paid in cash" and the part of a let-go no lent payment carries close what is owed and
                // never more: a repayment the user marks later, dated before them, already closed it.
                case CASH_SETTLED -> Cap.TO_ZERO;
                case LET_GO -> t == null ? Cap.TO_ZERO : Cap.NONE;
                default -> Cap.NONE;
            };
            BigDecimal signed = signed(e.getDirection(), amount);
            events.add(new Event(e.getPersonKey(), when, signed, cap,
                    e.getDirection() == LoanEntry.Direction.LET_GO, t == null ? null : t.getId()));
        }
        for (LoanEntry e : entries) {
            Transaction t = e.getTxnId() == null ? null : live.get(e.getTxnId());
            if (t == null) continue; // its row is gone: so is the entry
            switch (e.getDirection()) {
                case PAID_BACK -> { if (isCredit(t) && !decisions.containsKey(t.getId())) decisions.put(t.getId(), PAID_BACK); }
                case BORROWED -> { if (isCredit(t) && !decisions.containsKey(t.getId())) decisions.put(t.getId(), BORROWED); }
                default -> { }
            }
        }

        // 4. The repayment rule: a credit from the sender with no link or entry counts toward what
        //    they owe, never past zero.
        for (Transaction t : byDate) {
            if (!isCredit(t) || t.getCounterpartyKey() == null || !repaymentSenderKeys.contains(t.getCounterpartyKey())) continue;
            if (answered.contains(t.getId()) || decisions.containsKey(t.getId())) continue;
            if (!CounterpartyIdentity.identifiesOnePayee(t.getCounterpartyKey())) continue;
            events.add(new Event(t.getCounterpartyKey(), t.getTxnDate(), t.getAmount().negate(), Cap.TO_ZERO, false, null));
        }

        Map<UUID, BigDecimal> reclaimed = new HashMap<>();
        Map<String, Balance> balances = balances(events, reclaimed);

        // A lent payment let go becomes spending, but only the part that never came back: the rest
        // nets off it the way a share does, so a half-repaid loan let go costs half. Money back marked
        // after the let-go (reclaimed) takes that much off it again.
        Map<UUID, BigDecimal> letGoAmount = new HashMap<>();
        for (Event ev : events) {
            if (ev.letGo() && ev.txnId() != null) letGoAmount.merge(ev.txnId(), ev.amount().negate(), BigDecimal::add);
        }
        reclaimed.forEach((txnId, back) -> letGoAmount.merge(txnId, back.negate(), BigDecimal::add));
        letGoAmount.values().removeIf(v -> v.signum() <= 0);
        for (LoanEntry e : entries) {
            Transaction t = e.getTxnId() == null ? null : live.get(e.getTxnId());
            if ((e.getDirection() == LoanEntry.Direction.LENT || e.getDirection() == LoanEntry.Direction.REPAID)
                    && isBill(t) && !letGoAmount.containsKey(t.getId())) {
                dropped.add(t.getId());
            }
        }
        letGoAmount.forEach((txnId, gone) -> {
            Transaction debit = live.get(txnId);
            if (!isBill(debit) || debit.getAmount() == null) return;
            BigDecimal cameBack = debit.getAmount().subtract(gone).min(debit.getAmount());
            if (cameBack.signum() > 0) shared.merge(txnId, cameBack, BigDecimal::add);
        });

        return new PeopleLedger(Map.copyOf(shared), Map.copyOf(sharesByBill), Set.copyOf(dropped),
                Map.copyOf(decisions), balances);
    }

    /**
     * What an entry counts for against its payment as it reads now, or null when it counts for
     * nothing: its payment is gone, or no longer the kind of payment the entry was made on (a lent
     * payment edited into money in). A whole-payment entry follows a corrected amount; the leftover
     * beside a credit's shares, and a let-go, never count past what the payment still holds. Cash
     * settled and let-go entries without a payment are their own amount.
     */
    static BigDecimal entryAmount(LoanEntry e, Transaction t, BigDecimal sharedFromCredit) {
        if (e.getTxnId() == null) return e.getAmount();
        if (t == null || t.getAmount() == null) return null;
        BigDecimal out = switch (e.getDirection()) {
            case LENT, REPAID -> isBill(t) ? t.getAmount() : null;
            // A credit with shares beside the entry: the entry is the leftover (PeopleService.addEntry).
            case PAID_BACK, BORROWED -> !isCredit(t) ? null
                    : sharedFromCredit.signum() > 0 ? e.getAmount().min(t.getAmount().subtract(sharedFromCredit)) : t.getAmount();
            case LET_GO -> isBill(t) ? e.getAmount().min(t.getAmount()) : null;
            case CASH_SETTLED -> e.getAmount();
        };
        return out == null || out.signum() <= 0 ? null : out;
    }

    /** How far money back may move a balance. TO_ZERO: only bring a positive balance to zero (the
     *  repayment rule). WHILE_OWED: only while one is owed, then in full, so the excess flips it. */
    private enum Cap { NONE, TO_ZERO, WHILE_OWED }

    /** One movement of a person's balance. {@code letGo}: a let-go entry, on lent payment {@code txnId}
     *  (null for the part no lent payment carries). */
    private record Event(String personKey, LocalDate date, BigDecimal amount, Cap cap, boolean letGo, UUID txnId) {}

    private static BigDecimal signed(LoanEntry.Direction direction, BigDecimal a) {
        return switch (direction) {
            case LENT, REPAID -> a;
            case PAID_BACK, CASH_SETTLED, LET_GO, BORROWED -> a.negate();
        };
    }

    /**
     * Running balance per person in date order, with the oldest open side's date (first in, first
     * out). On one day, what is owed comes before what closes it, so a close capped to the balance
     * sees the loan it closes. Money back with nothing owed takes back that much of an earlier let-go
     * of the person's -- the part no lent payment carries first, then the newest lent payment's --
     * and {@code reclaimed} gets, per lent payment, how much of its let-go came back.
     */
    private static Map<String, Balance> balances(List<Event> events, Map<UUID, BigDecimal> reclaimed) {
        Map<String, List<Event>> byPerson = new LinkedHashMap<>();
        for (Event e : events) byPerson.computeIfAbsent(e.personKey(), k -> new ArrayList<>()).add(e);
        Map<String, Balance> out = new LinkedHashMap<>();
        byPerson.forEach((person, list) -> {
            list.sort(Comparator.comparing(Event::date).thenComparing(e -> -e.amount().signum()));
            // Open amounts on each side: what they owe the user (+) and what the user owes them (-).
            Deque<Object[]> open = new ArrayDeque<>(); // {LocalDate date, BigDecimal amount}, one sign at a time
            // Let-go seen so far that money back could still take back: {Event, BigDecimal left}.
            List<Object[]> letGoLeft = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (Event e : list) {
                BigDecimal amount = e.amount();
                if (e.cap() != Cap.NONE && total.signum() <= 0) {
                    if (e.cap() == Cap.WHILE_OWED) reclaim(amount.abs(), letGoLeft, reclaimed);
                    continue;
                }
                if (e.cap() == Cap.TO_ZERO) amount = amount.abs().min(total).negate();
                // More back than is owed: the excess takes back a let-go before it flips the balance.
                if (e.cap() == Cap.WHILE_OWED && amount.abs().compareTo(total) > 0) {
                    BigDecimal back = reclaim(amount.abs().subtract(total), letGoLeft, reclaimed);
                    amount = amount.add(back);
                }
                if (e.letGo()) letGoLeft.add(new Object[]{e, amount.abs()});
                total = total.add(amount);
                BigDecimal rest = amount;
                while (rest.signum() != 0 && !open.isEmpty()
                        && ((BigDecimal) open.peekFirst()[1]).signum() != rest.signum()) {
                    Object[] head = open.peekFirst();
                    BigDecimal headAmt = (BigDecimal) head[1];
                    BigDecimal sum = headAmt.add(rest);
                    if (sum.signum() == headAmt.signum()) { head[1] = sum; rest = BigDecimal.ZERO; }
                    else { open.pollFirst(); rest = sum; }
                }
                if (rest.signum() != 0) open.addLast(new Object[]{e.date(), rest});
            }
            if (total.signum() != 0) {
                LocalDate since = open.isEmpty() ? null : (LocalDate) open.peekFirst()[0];
                out.put(person, new Balance(person, total, since));
            }
        });
        return Map.copyOf(out);
    }

    /** Takes up to {@code amount} back from the let-go seen so far (see {@link #balances}); returns how
     *  much it took. */
    private static BigDecimal reclaim(BigDecimal amount, List<Object[]> letGoLeft, Map<UUID, BigDecimal> reclaimed) {
        List<Object[]> order = new ArrayList<>(letGoLeft);
        order.sort(Comparator.comparing((Object[] p) -> ((Event) p[0]).txnId() != null)
                .thenComparing((Object[] p) -> ((Event) p[0]).date(), Comparator.reverseOrder()));
        BigDecimal want = amount;
        for (Object[] pot : order) {
            if (want.signum() <= 0) break;
            BigDecimal take = want.min((BigDecimal) pot[1]);
            if (take.signum() <= 0) continue;
            pot[1] = ((BigDecimal) pot[1]).subtract(take);
            want = want.subtract(take);
            UUID txnId = ((Event) pot[0]).txnId();
            if (txnId != null) reclaimed.merge(txnId, take, BigDecimal::add);
        }
        return amount.subtract(want);
    }

    /** Bills a rule may fill for this credit: same identity, within the window, the ones before the
     *  credit first (nearest first), then the ones after (nearest first). */
    private static List<Transaction> ruleCandidates(SplitRule rule, Transaction credit, List<Transaction> byDate) {
        LocalDate day = credit.getTxnDate();
        List<Transaction> before = new ArrayList<>();
        List<Transaction> after = new ArrayList<>();
        for (Transaction b : byDate) {
            if (!isBill(b) || !rule.getBillCategoryId().equals(b.getCategoryId())) continue;
            boolean samePayee = rule.getBillPayeeKey() != null
                    ? rule.getBillPayeeKey().equals(b.getCounterpartyKey())
                    : b.getMerchant() != null && b.getMerchant().equalsIgnoreCase(rule.getBillMerchant());
            if (!samePayee) continue;
            long gap = b.getTxnDate().toEpochDay() - day.toEpochDay();
            if (gap < -RULE_WINDOW_DAYS || gap > RULE_WINDOW_DAYS) continue;
            (gap <= 0 ? before : after).add(b);
        }
        before.sort(Comparator.comparing(Transaction::getTxnDate).reversed());
        after.sort(Comparator.comparing(Transaction::getTxnDate));
        List<Transaction> out = new ArrayList<>(before);
        out.addAll(after);
        return out;
    }

    private static void record(Transaction credit, Transaction bill, BigDecimal amount, boolean fromRule,
                               Map<UUID, BigDecimal> creditLeft, Map<UUID, BigDecimal> billLeft,
                               Map<UUID, List<Share>> sharesByBill) {
        if (amount.signum() <= 0) return;
        creditLeft.put(credit.getId(), left(creditLeft, credit).subtract(amount));
        billLeft.put(bill.getId(), left(billLeft, bill).subtract(amount));
        sharesByBill.computeIfAbsent(bill.getId(), k -> new ArrayList<>()).add(new Share(credit.getId(), bill.getId(), amount, fromRule));
    }

    private static BigDecimal left(Map<UUID, BigDecimal> leftById, Transaction t) {
        return leftById.getOrDefault(t.getId(), t.getAmount() == null ? BigDecimal.ZERO : t.getAmount());
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b, BigDecimal c) {
        return a.min(b).min(c).max(BigDecimal.ZERO);
    }

    private static boolean isLive(Transaction t) {
        return t.getIsDuplicateOf() == null
                && t.getReconciliationStatus() != Transaction.ReconciliationStatus.SUPERSEDED;
    }

    /** Money in or out that is just that: not matched as a transfer, a refund, a reversal or an
     *  investment transfer. Only such a row can be a share, a bill, a loan or a repayment -- a paired
     *  row is not spending, so a share against it would take the credit out of every figure while
     *  lowering nothing. */
    static boolean isPlain(Transaction t) {
        return t != null && t.getReconciliationStatus() == Transaction.ReconciliationStatus.OK;
    }

    static boolean isCredit(Transaction t) { return isPlain(t) && t.getTxnType() == Transaction.Type.INCOME; }
    static boolean isBill(Transaction t) { return isPlain(t) && t.getTxnType() == Transaction.Type.EXPENSE; }
}
