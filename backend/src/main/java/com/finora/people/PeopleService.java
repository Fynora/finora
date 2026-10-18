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

    public PeopleService(ShareLinkRepository shareLinks, SplitRuleRepository splitRules, LoanEntryRepository loanEntries,
                         PersonSettingRepository personSettings, PeoplePreferenceRepository preferences,
                         TransactionRepository transactions, CategoryRepository categories,
                         PeopleLedgerService ledgers, TransactionService transactionService) {
        this.shareLinks = shareLinks;
        this.splitRules = splitRules;
        this.loanEntries = loanEntries;
        this.personSettings = personSettings;
        this.preferences = preferences;
        this.transactions = transactions;
        this.categories = categories;
        this.ledgers = ledgers;
        this.transactionService = transactionService;
    }

    // ---- shares ----

    @Transactional
    public PeopleDtos.ShareDto link(UUID userId, PeopleDtos.LinkShareRequest req) {
        Transaction credit = owned(userId, req.creditId());
        Transaction bill = owned(userId, req.billId());
        if (!PeopleLedger.isCredit(credit) || !PeopleLedger.isBill(bill)) {
            throw new ApiException(ErrorCode.PEOPLE_WRONG_DIRECTION);
        }
        List<ShareLink> existing = shareLinks.findByUserIdAndCreditTxnId(userId, credit.getId()).stream()
                .filter(l -> l.getBillTxnId().equals(bill.getId())).toList();
        shareLinks.deleteAll(existing);
        shareLinks.flush();
        PeopleLedger ledger = ledgers.forUser(userId);
        BigDecimal creditLeft = credit.getAmount().subtract(ledger.sharedFromCredit(credit.getId()));
        BigDecimal billLeft = bill.getAmount().subtract(ledger.sharedByBillId().getOrDefault(bill.getId(), BigDecimal.ZERO));
        BigDecimal cap = creditLeft.min(billLeft);
        BigDecimal amount = req.amount() == null ? cap : req.amount().min(cap);
        if (amount.signum() <= 0) throw new ApiException(ErrorCode.PEOPLE_NOTHING_LEFT);
        shareLinks.save(new ShareLink(userId, credit.getId(), bill.getId(), amount));
        return new PeopleDtos.ShareDto(credit.getId(), bill.getId(), PayeeLabel.of(credit, "Unknown"),
                credit.getTxnDate(), amount, false);
    }

    @Transactional
    public void unlink(UUID userId, UUID creditId, UUID billId) {
        shareLinks.deleteAll(shareLinks.findByUserIdAndCreditTxnId(userId, creditId).stream()
                .filter(l -> l.getBillTxnId().equals(billId)).toList());
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
        LoanEntry entry = loanEntries.findByUserIdAndPersonKey(userId, person).stream()
                .filter(e -> t.getId().equals(e.getTxnId()) && e.getDirection() == req.direction()).findFirst()
                .orElseGet(() -> loanEntries.save(new LoanEntry(userId, person, t.getId(), req.direction(), t.getAmount())));
        return entryDto(entry);
    }

    @Transactional
    public void deleteEntry(UUID userId, UUID entryId) {
        loanEntries.findById(entryId).filter(e -> e.getUserId().equals(userId)).ifPresent(loanEntries::delete);
    }

    /** CASH: the open balance was paid back outside the bank. LET_GO: it never will be -- each lent
     *  payment still open is re-filed under Gifts & Donations and counts as spending for what never
     *  came back, newest first (the oldest were repaid first). */
    @Transactional
    public void settle(UUID userId, PeopleDtos.SettleRequest req) {
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
            BigDecimal stillOut = debit.getAmount().subtract(alreadyGone.getOrDefault(debit.getId(), BigDecimal.ZERO));
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
        PersonSetting s = personSettings.findByUserIdAndPersonKey(userId, req.personKey())
                .orElseGet(() -> new PersonSetting(userId, req.personKey(), req.family()));
        s.setFamily(req.family());
        personSettings.save(s);
    }

    @Transactional(readOnly = true)
    public PeopleDtos.PreferencesDto preferences(UUID userId) {
        return new PeopleDtos.PreferencesDto(preferences.findById(userId).map(PeoplePreference::getOwnUpiId).orElse(null));
    }

    @Transactional
    public PeopleDtos.PreferencesDto setPreferences(UUID userId, PeopleDtos.PreferencesRequest req) {
        String upi = req.ownUpiId() == null || req.ownUpiId().isBlank() ? null : req.ownUpiId().trim();
        PeoplePreference p = preferences.findById(userId).orElseGet(() -> new PeoplePreference(userId, upi));
        p.setOwnUpiId(upi);
        preferences.save(p);
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
