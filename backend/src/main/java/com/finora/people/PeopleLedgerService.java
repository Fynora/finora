package com.finora.people;

import com.finora.entity.InflowKind;
import com.finora.entity.LoanEntry;
import com.finora.entity.SenderInflowRule;
import com.finora.entity.ShareLink;
import com.finora.entity.SplitRule;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.LoanEntryRepository;
import com.finora.repository.SenderInflowRuleRepository;
import com.finora.repository.ShareLinkRepository;
import com.finora.repository.SplitRuleRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads what {@link PeopleLedger} needs for one user. Cheap for a user who never confirmed a share,
 * a rule or a loan -- four small reads and no transactions -- so every figure can ask for it.
 * Otherwise it reads only the rows those answers can touch: the rows they name, and for each rule its
 * sender's credits and its category's bills. Rows on a deleted account are left out (they keep
 * deleted_at unset, see DashboardService).
 */
@Service
public class PeopleLedgerService {

    private final ShareLinkRepository shareLinks;
    private final SplitRuleRepository splitRules;
    private final LoanEntryRepository loanEntries;
    private final InflowKindRepository inflowKinds;
    private final SenderInflowRuleRepository senderRules;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public PeopleLedgerService(ShareLinkRepository shareLinks, SplitRuleRepository splitRules,
                               LoanEntryRepository loanEntries, InflowKindRepository inflowKinds,
                               SenderInflowRuleRepository senderRules, AccountRepository accounts,
                               TransactionRepository transactions) {
        this.shareLinks = shareLinks;
        this.splitRules = splitRules;
        this.loanEntries = loanEntries;
        this.inflowKinds = inflowKinds;
        this.senderRules = senderRules;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public PeopleLedger forUser(UUID userId) {
        List<ShareLink> links = shareLinks.findByUserId(userId);
        List<SplitRule> rules = splitRules.findByUserId(userId).stream().filter(SplitRule::isActive).toList();
        List<LoanEntry> entries = loanEntries.findByUserId(userId);
        if (links.isEmpty() && rules.isEmpty() && entries.isEmpty()) return PeopleLedger.EMPTY;

        Set<String> repaymentKeys = repaymentSenderKeys(userId);
        Set<UUID> ids = new HashSet<>();
        links.forEach(l -> { ids.add(l.getCreditTxnId()); ids.add(l.getBillTxnId()); });
        entries.forEach(e -> { if (e.getTxnId() != null) ids.add(e.getTxnId()); });
        Set<String> senderKeys = new HashSet<>(repaymentKeys);
        Set<UUID> ruleCategories = new HashSet<>();
        rules.forEach(r -> { senderKeys.add(r.getSenderKey()); ruleCategories.add(r.getBillCategoryId()); });

        Map<UUID, Transaction> rows = new LinkedHashMap<>();
        transactions.findAllById(ids).forEach(t -> rows.put(t.getId(), t));
        if (!senderKeys.isEmpty()) {
            transactions.findByUserIdAndCounterpartyKeyIn(userId, senderKeys).forEach(t -> rows.put(t.getId(), t));
        }
        if (!ruleCategories.isEmpty()) {
            transactions.findByUserIdAndCategoryIdIn(userId, ruleCategories).forEach(t -> rows.put(t.getId(), t));
        }
        Set<UUID> liveAccounts = new HashSet<>();
        accounts.findByUserId(userId).forEach(a -> liveAccounts.add(a.getId()));
        List<Transaction> live = rows.values().stream()
                .filter(t -> userId.equals(t.getUserId()) && liveAccounts.contains(t.getAccountId())).toList();
        return PeopleLedger.build(live, links, rules, entries, repaymentKeys);
    }

    /** Senders the user said always pay them back (a sender rule on the built-in Paid back kind). */
    Set<String> repaymentSenderKeys(UUID userId) {
        Set<UUID> paidBackKinds = new HashSet<>();
        for (InflowKind k : inflowKinds.findByUserId(userId)) {
            if (k.getBuiltIn() == InflowKind.BuiltIn.PAID_BACK) paidBackKinds.add(k.getId());
        }
        if (paidBackKinds.isEmpty()) return Set.of();
        Set<String> keys = new HashSet<>();
        for (SenderInflowRule r : senderRules.findByUserId(userId)) {
            if (paidBackKinds.contains(r.getInflowKindId())) keys.add(r.getCounterpartyKey());
        }
        return keys;
    }
}
