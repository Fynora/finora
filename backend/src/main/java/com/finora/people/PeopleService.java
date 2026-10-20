package com.finora.people;

import com.finora.entity.Category;
import com.finora.entity.LoanEntry;
import com.finora.entity.PeoplePreference;
import com.finora.entity.PersonSetting;
import com.finora.entity.ShareLink;
import com.finora.entity.SplitRule;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.CategoryRepository;
import com.finora.repository.LoanEntryRepository;
import com.finora.repository.PeoplePreferenceRepository;
import com.finora.repository.PersonSettingRepository;
import com.finora.repository.ShareLinkRepository;
import com.finora.repository.SplitRuleRepository;
import com.finora.repository.TransactionRepository;
import com.finora.transactions.TransactionDto;
import com.finora.transactions.TransactionService;
import com.finora.util.CounterpartyIdentity;
import com.finora.util.PayeeLabel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the user confirms about money with people (spec §5, §7): a share of a bill, a loan entry, a
 * settlement, a split rule, a person's family switch, their own UPI id. Every figure follows through
 * PeopleLedger; nothing here edits a figure directly, except "let it go", which re-files the lent
 * payment under Gifts & Donations so it shows where a gift does.
 */
@Service
public class PeopleService {

    static final String GIFTS_CATEGORY = "Gifts & Donations";

    private final ShareLinkRepository shareLinks;
    private final SplitRuleRepository splitRules;
    private final LoanEntryRepository loanEntries;
    private final PersonSettingRepository personSettings;
    private final PeoplePreferenceRepository preferences;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final PeopleLedgerService ledgers;
    private final TransactionService transactionService;
    private final com.finora.repository.AccountRepository accounts;
    private final com.finora.repository.UserRepository users;
    private final com.finora.repository.InsightAcknowledgementRepository acknowledgements;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public PeopleService(ShareLinkRepository shareLinks, SplitRuleRepository splitRules, LoanEntryRepository loanEntries,
                         PersonSettingRepository personSettings, PeoplePreferenceRepository preferences,
                         TransactionRepository transactions, CategoryRepository categories,
                         PeopleLedgerService ledgers, TransactionService transactionService,
                         com.finora.repository.AccountRepository accounts, com.finora.repository.UserRepository users,
                         com.finora.repository.InsightAcknowledgementRepository acknowledgements,
                         org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.shareLinks = shareLinks;
        this.splitRules = splitRules;
        this.loanEntries = loanEntries;
        this.personSettings = personSettings;
        this.preferences = preferences;
        this.transactions = transactions;
        this.categories = categories;
        this.ledgers = ledgers;
        this.transactionService = transactionService;
        this.accounts = accounts;
        this.users = users;
        this.acknowledgements = acknowledgements;
        this.jdbc = jdbc;
    }

    /**
     * One write at a time per user, held to the end of the transaction. Every write here reads the
     * user's balances or links and then writes from what it read: without this, two taps at once
     * ("Paid in cash" twice) both read the same open balance and settle it twice.
     */
    private void oneWriteAtATime(UUID userId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "people:" + userId);
    }

    // ---- suggestions ----

    /** The Insights rows that ask about money with people, and the shares expected in (spec §6, §7). */
    @Transactional(readOnly = true)
    public PeopleSuggestions.PeopleSuggestionsResult suggestions(UUID userId) {
        List<UUID> liveAccounts = accounts.findByUserId(userId).stream().map(com.finora.entity.Account::getId).toList();
        if (liveAccounts.isEmpty()) return new PeopleSuggestions.PeopleSuggestionsResult(List.of(), List.of());
        List<Transaction> rows = transactions.findByUserIdAndAccountIdIn(userId, liveAccounts);
        Map<UUID, String> categoryNames = new HashMap<>();
        for (Category c : categories.findByUserId(userId)) categoryNames.put(c.getId(), c.getName());
        Set<String> acked = new HashSet<>();
        acknowledgements.findByUserId(userId).forEach(a -> acked.add(a.getKind() + "|" + a.getItemKey()));
        LocalDate today = LocalDate.now(com.finora.util.UserZone.of(
                users.findById(userId).map(com.finora.entity.User::getTimezone).orElse(null)));
        return PeopleSuggestions.build(new PeopleSuggestions.Input(ledgers.forUser(userId), rows, categoryNames, acked,
                familyKeys(userId), splitRules.findByUserId(userId), loanEntries.findByUserId(userId),
                ledgers.repaymentSenderKeys(userId), today, ledgers.senderKeysWithAKind(userId)));
    }

    // ---- shares ----

    @Transactional
    public PeopleDtos.ShareDto link(UUID userId, PeopleDtos.LinkShareRequest req) {
        oneWriteAtATime(userId);
        Transaction credit = owned(userId, req.creditId());
        Transaction bill = owned(userId, req.billId());
        if (!PeopleLedger.isCredit(credit) || !PeopleLedger.isBill(bill)) {
            throw new ApiException(ErrorCode.PEOPLE_WRONG_DIRECTION);
        }
        // One answer per payment: a bill already marked lent or repaid is not a shared bill, and a
        // credit's earlier "paid back" or "borrowed" gives way to the share.
        if (!loanEntries.findByUserIdAndTxnId(userId, bill.getId()).isEmpty()) {
            throw new ApiException(ErrorCode.PEOPLE_WRONG_DIRECTION);
        }
        // A whole-credit answer gives way to the share; a leftover answer (part of the credit,
        // beside its shares) stays, and what it covers is not free for this link.
        List<LoanEntry> creditAnswers = loanEntries.findByUserIdAndTxnId(userId, credit.getId());
        loanEntries.deleteAll(creditAnswers.stream().filter(e -> !isLeftover(e, credit)).toList());
        BigDecimal answeredLeftover = creditAnswers.stream().filter(e -> isLeftover(e, credit))
                .map(LoanEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        loanEntries.flush();
        PeopleLedger ledger = ledgers.forUser(userId);
        // This pair's own earlier link (a second tap, or a changed amount) is replaced, not added to.
        BigDecimal ownEarlier = ledger.sharesOf(bill.getId()).stream()
                .filter(s -> s.creditId().equals(credit.getId()) && !s.fromRule())
                .map(PeopleLedger.Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditLeft = credit.getAmount().subtract(ledger.sharedFromCredit(credit.getId())).add(ownEarlier)
                .subtract(answeredLeftover);
        BigDecimal billLeft = bill.getAmount().subtract(ledger.sharedByBillId().getOrDefault(bill.getId(), BigDecimal.ZERO))
                .add(ownEarlier);
        BigDecimal cap = creditLeft.min(billLeft);
        BigDecimal amount = req.amount() == null ? cap : req.amount().min(cap);
        if (amount.signum() <= 0) throw new ApiException(ErrorCode.PEOPLE_NOTHING_LEFT);
        // One statement, so a double tap cannot race into the (credit, bill) unique key.
        shareLinks.upsert(userId, credit.getId(), bill.getId(), amount);
        return new PeopleDtos.ShareDto(credit.getId(), bill.getId(), PayeeLabel.of(credit, "Unknown"),
                credit.getTxnDate(), amount, false);
    }

    @Transactional
    public void unlink(UUID userId, UUID creditId, UUID billId) {
        oneWriteAtATime(userId);
        shareLinks.deleteAll(shareLinks.findByUserIdAndCreditTxnId(userId, creditId).stream()
                .filter(l -> l.getBillTxnId().equals(billId)).toList());
        shareLinks.flush();
        // With no share left on the credit, a leftover answer has nothing left over from: it would
        // read the whole credit as paid back while only part of it was answered.
        transactions.findById(creditId).filter(c -> userId.equals(c.getUserId())).ifPresent(credit -> {
            if (ledgers.forUser(userId).sharedFromCredit(creditId).signum() == 0) {
                loanEntries.deleteAll(loanEntries.findByUserIdAndTxnId(userId, creditId).stream()
                        .filter(e -> isLeftover(e, credit)).toList());
            }
        });
    }

    /** An answer on part of a credit: the leftover beside its shares. */
    private static boolean isLeftover(LoanEntry e, Transaction credit) {
        return (e.getDirection() == LoanEntry.Direction.PAID_BACK || e.getDirection() == LoanEntry.Direction.BORROWED)
                && e.getAmount().compareTo(credit.getAmount()) < 0;
    }

    @Transactional(readOnly = true)
    public List<PeopleDtos.ShareDto> billShares(UUID userId, UUID billId) {
        owned(userId, billId);
        List<PeopleLedger.Share> shares = ledgers.forUser(userId).sharesOf(billId);
        Map<UUID, Transaction> credits = byId(shares.stream().map(PeopleLedger.Share::creditId).toList());
        return shares.stream().map(s -> {
            Transaction c = credits.get(s.creditId());
            return new PeopleDtos.ShareDto(s.creditId(), billId, c == null ? "Unknown" : PayeeLabel.of(c, "Unknown"),
                    c == null ? null : c.getTxnDate(), s.amount(), s.fromRule());
        }).toList();
    }

    // ---- loans ----

    @Transactional
    public PeopleDtos.EntryDto addEntry(UUID userId, PeopleDtos.EntryRequest req) {
        oneWriteAtATime(userId);
        Transaction t = owned(userId, req.txnId());
        boolean out = PeopleLedger.isBill(t);
        boolean fits = switch (req.direction()) {
            case LENT, REPAID -> out;
            case PAID_BACK, BORROWED -> !out;
            case CASH_SETTLED, LET_GO -> false;
        };
        if (!fits) throw new ApiException(ErrorCode.PEOPLE_WRONG_DIRECTION);
        String person = t.getCounterpartyKey();
        if (!CounterpartyIdentity.identifiesOnePayee(person)) throw new ApiException(ErrorCode.PEOPLE_NOT_ONE_PAYEE);
        // One answer per payment. A bill other people paid shares of is not a loan; a credit's
        // share links give way to the new answer; any earlier answer on this payment is replaced.
        if (out && !shareLinks.findByUserIdAndBillTxnId(userId, t.getId()).isEmpty()) {
            throw new ApiException(ErrorCode.PEOPLE_WRONG_DIRECTION);
        }
        BigDecimal amount = t.getAmount();
        if (req.amount() != null) {
            // Only part of the payment: on a credit, the part left over after the bills it is linked
            // to, which keeps its share links.
            BigDecimal shared = out ? BigDecimal.ZERO : ledgers.forUser(userId).sharedFromCredit(t.getId());
            // Only the leftover beside a credit's shares can be answered in part: anywhere else the
            // rest of the payment would read as answered too.
            if (shared.signum() == 0) throw new ApiException(ErrorCode.PEOPLE_WRONG_DIRECTION);
            if (req.amount().compareTo(t.getAmount().subtract(shared)) > 0) throw new ApiException(ErrorCode.PEOPLE_NOTHING_LEFT);
            amount = req.amount();
        } else if (!out) {
            shareLinks.deleteAll(shareLinks.findByUserIdAndCreditTxnId(userId, t.getId()));
        }
        loanEntries.deleteAll(loanEntries.findByUserIdAndTxnId(userId, t.getId()).stream()
                .filter(e -> e.getDirection() != req.direction()).toList());
        loanEntries.flush();
        // One statement, so a double tap cannot race into the (txn, direction) unique key.
        loanEntries.upsert(userId, person, t.getId(), req.direction().name(), amount);
        LoanEntry entry = loanEntries.findByUserIdAndTxnId(userId, t.getId()).stream()
                .filter(e -> e.getDirection() == req.direction()).findFirst().orElseThrow();
        return entryDto(entry);
    }

    @Transactional
    public void deleteEntry(UUID userId, UUID entryId) {
        oneWriteAtATime(userId);
        loanEntries.findById(entryId).filter(e -> e.getUserId().equals(userId)).ifPresent(e -> {
            if (e.getDirection() == LoanEntry.Direction.LENT && e.getTxnId() != null) {
                loanEntries.deleteAll(loanEntries.findByUserIdAndTxnId(userId, e.getTxnId()).stream()
                        .filter(x -> x.getDirection() == LoanEntry.Direction.LET_GO).toList());
            }
            loanEntries.delete(e);
        });
    }

    /** CASH: the open balance was paid back outside the bank. LET_GO: it never will be -- each lent
     *  payment still open is re-filed under Gifts & Donations and counts as spending for what never
     *  came back, newest first (the oldest were repaid first). */
    @Transactional
    public void settle(UUID userId, PeopleDtos.SettleRequest req) {
        oneWriteAtATime(userId);
        PeopleLedger.Balance balance = ledgers.forUser(userId).balances().get(req.personKey());
        if (balance == null || balance.owedToUser().signum() <= 0) throw new ApiException(ErrorCode.PEOPLE_NOTHING_OWED);
        BigDecimal open = balance.owedToUser();
        if (req.how() == PeopleDtos.SettleHow.CASH) {
            loanEntries.save(new LoanEntry(userId, req.personKey(), null, LoanEntry.Direction.CASH_SETTLED, open));
            return;
        }
        List<LoanEntry> entries = loanEntries.findByUserIdAndPersonKey(userId, req.personKey());
        Map<UUID, BigDecimal> alreadyGone = new HashMap<>();
        for (LoanEntry e : entries) {
            if (e.getDirection() == LoanEntry.Direction.LET_GO && e.getTxnId() != null) {
                alreadyGone.merge(e.getTxnId(), e.getAmount(), BigDecimal::add);
            }
        }
        Map<UUID, Transaction> rows = byId(entries.stream().filter(e -> e.getDirection() == LoanEntry.Direction.LENT)
                .map(LoanEntry::getTxnId).toList());
        List<Transaction> lent = rows.values().stream()
                .sorted(Comparator.comparing(Transaction::getTxnDate).reversed()).toList();
        for (Transaction debit : lent) {
            if (open.signum() <= 0) break;
            // Already let go (in part, the rest having come back): that payment is closed.
            if (alreadyGone.containsKey(debit.getId())) continue;
            BigDecimal stillOut = debit.getAmount();
            BigDecimal gone = stillOut.min(open);
            if (gone.signum() <= 0) continue;
            loanEntries.save(new LoanEntry(userId, req.personKey(), debit.getId(), LoanEntry.Direction.LET_GO, gone));
            transactionService.updateCategory(userId, debit.getId(), GIFTS_CATEGORY, TransactionDto.CategoryScope.ONLY_THIS);
            open = open.subtract(gone);
        }
        if (open.signum() > 0) {
            loanEntries.save(new LoanEntry(userId, req.personKey(), null, LoanEntry.Direction.LET_GO, open));
        }
    }

    // ---- split rules ----

    @Transactional
    public PeopleDtos.SplitRuleDto addSplitRule(UUID userId, PeopleDtos.SplitRuleRequest req) {
        oneWriteAtATime(userId);
        Transaction credit = owned(userId, req.creditId());
        Transaction bill = owned(userId, req.billId());
        if (!PeopleLedger.isCredit(credit) || !PeopleLedger.isBill(bill)) {
            throw new ApiException(ErrorCode.PEOPLE_WRONG_DIRECTION);
        }
        if (!CounterpartyIdentity.identifiesOnePayee(credit.getCounterpartyKey())) {
            throw new ApiException(ErrorCode.PEOPLE_NOT_ONE_PAYEE);
        }
        if (bill.getCategoryId() == null) throw new ApiException(ErrorCode.PEOPLE_BILL_HAS_NO_CATEGORY);
        String payee = CounterpartyIdentity.identifiesOnePayee(bill.getCounterpartyKey()) ? bill.getCounterpartyKey() : null;
        String merchant = payee == null && bill.getMerchant() != null && !bill.getMerchant().isBlank() ? bill.getMerchant() : null;
        if (payee == null && merchant == null) throw new ApiException(ErrorCode.PEOPLE_NOT_ONE_PAYEE);
        SplitRule rule = splitRules.findByUserId(userId).stream()
                .filter(r -> r.isActive() && r.getSenderKey().equals(credit.getCounterpartyKey())
                        && r.getBillCategoryId().equals(bill.getCategoryId())
                        && java.util.Objects.equals(r.getBillPayeeKey(), payee)
                        && java.util.Objects.equals(r.getBillMerchant(), merchant))
                .findFirst()
                .orElseGet(() -> splitRules.save(new SplitRule(userId, credit.getCounterpartyKey(), bill.getCategoryId(), payee, merchant)));
        return ruleDto(rule);
    }

    @Transactional
    public void removeSplitRule(UUID userId, UUID ruleId) {
        oneWriteAtATime(userId);
        splitRules.findById(ruleId).filter(r -> r.getUserId().equals(userId) && r.isActive()).ifPresent(r -> {
            r.setRemovedAt(Instant.now());
            splitRules.save(r);
        });
    }

    // ---- people ----

    /** Everyone with an open balance, family included (the app decides what to show). */
    @Transactional(readOnly = true)
    public List<PeopleDtos.PersonDto> people(UUID userId) {
        Map<String, PeopleLedger.Balance> balances = ledgers.forUser(userId).balances();
        if (balances.isEmpty()) return List.of();
        Set<String> family = familyKeys(userId);
        Map<String, String> labels = labels(userId, balances.keySet());
        return balances.values().stream()
                .sorted(Comparator.comparing(PeopleLedger.Balance::owedToUser).reversed())
                .map(b -> new PeopleDtos.PersonDto(b.personKey(), labels.getOrDefault(b.personKey(), "Unknown"),
                        b.owedToUser(), b.since(), family.contains(b.personKey())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PeopleDtos.PersonDetailDto person(UUID userId, String personKey) {
        PeopleLedger ledger = ledgers.forUser(userId);
        PeopleLedger.Balance balance = ledger.balances().get(personKey);
        List<PeopleDtos.TimelineItem> timeline = new ArrayList<>();
        List<LoanEntry> entries = loanEntries.findByUserIdAndPersonKey(userId, personKey);
        Map<UUID, Transaction> rows = byId(entries.stream().map(LoanEntry::getTxnId).filter(java.util.Objects::nonNull).toList());
        for (LoanEntry e : entries) {
            Transaction t = e.getTxnId() == null ? null : rows.get(e.getTxnId());
            LocalDate date = t != null ? t.getTxnDate() : e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            timeline.add(new PeopleDtos.TimelineItem(date, e.getDirection().name(), e.getAmount(), e.getTxnId()));
        }
        Map<UUID, Transaction> credits = byId(ledger.allShares().stream().map(PeopleLedger.Share::creditId).toList());
        for (PeopleLedger.Share s : ledger.allShares()) {
            Transaction c = credits.get(s.creditId());
            if (c != null && personKey.equals(c.getCounterpartyKey())) {
                timeline.add(new PeopleDtos.TimelineItem(c.getTxnDate(), "SHARE", s.amount(), c.getId()));
            }
        }
        timeline.sort(Comparator.comparing(PeopleDtos.TimelineItem::date));
        List<PeopleDtos.SplitRuleDto> rules = splitRules.findByUserId(userId).stream()
                .filter(r -> r.isActive() && r.getSenderKey().equals(personKey)).map(this::ruleDto).toList();
        return new PeopleDtos.PersonDetailDto(personKey, labels(userId, Set.of(personKey)).getOrDefault(personKey, "Unknown"),
                balance == null ? BigDecimal.ZERO : balance.owedToUser(), balance == null ? null : balance.since(),
                familyKeys(userId).contains(personKey), timeline, rules);
    }

    @Transactional
    public void setPersonSettings(UUID userId, PeopleDtos.PersonSettingsRequest req) {
        oneWriteAtATime(userId);
        personSettings.upsert(userId, req.personKey(), req.family());
    }

    @Transactional(readOnly = true)
    public PeopleDtos.PreferencesDto preferences(UUID userId) {
        return new PeopleDtos.PreferencesDto(preferences.findById(userId).map(PeoplePreference::getOwnUpiId).orElse(null));
    }

    @Transactional
    public PeopleDtos.PreferencesDto setPreferences(UUID userId, PeopleDtos.PreferencesRequest req) {
        oneWriteAtATime(userId);
        String upi = req.ownUpiId() == null || req.ownUpiId().isBlank() ? null : req.ownUpiId().trim();
        preferences.upsert(userId, upi);
        return new PeopleDtos.PreferencesDto(upi);
    }

    /** Keys of the people the user marked as family. */
    Set<String> familyKeys(UUID userId) {
        Set<String> out = new HashSet<>();
        for (PersonSetting s : personSettings.findByUserId(userId)) if (s.isFamily()) out.add(s.getPersonKey());
        return out;
    }

    /** Each key's name, from its newest row. */
    Map<String, String> labels(UUID userId, Set<String> keys) {
        Map<String, Transaction> newest = new HashMap<>();
        for (Transaction t : transactions.findByUserIdAndCounterpartyKeyIn(userId, keys)) {
            Transaction seen = newest.get(t.getCounterpartyKey());
            if (seen == null || t.getTxnDate().isAfter(seen.getTxnDate())) newest.put(t.getCounterpartyKey(), t);
        }
        Map<String, String> out = new HashMap<>();
        newest.forEach((k, t) -> out.put(k, PayeeLabel.of(t, "Unknown")));
        return out;
    }

    private Transaction owned(UUID userId, UUID txnId) {
        return transactions.findById(txnId).filter(t -> userId.equals(t.getUserId()))
                .orElseThrow(() -> new ApiException(ErrorCode.TXN_NOT_FOUND));
    }

    private Map<UUID, Transaction> byId(List<UUID> ids) {
        Map<UUID, Transaction> out = new HashMap<>();
        transactions.findAllById(ids).forEach(t -> out.put(t.getId(), t));
        return out;
    }

    private PeopleDtos.SplitRuleDto ruleDto(SplitRule r) {
        String category = categories.findById(r.getBillCategoryId()).map(Category::getName).orElse(null);
        return new PeopleDtos.SplitRuleDto(r.getId(), r.getSenderKey(), r.getBillCategoryId(), category,
                r.getBillPayeeKey(), r.getBillMerchant());
    }

    private static PeopleDtos.EntryDto entryDto(LoanEntry e) {
        return new PeopleDtos.EntryDto(e.getId(), e.getPersonKey(), e.getTxnId(), e.getDirection(), e.getAmount());
    }
}
