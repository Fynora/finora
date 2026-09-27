package com.finora.inflow;

import com.finora.entity.InflowKind;
import com.finora.entity.SenderInflowRule;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.SenderInflowRuleRepository;
import com.finora.repository.TransactionRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.FlowClassifier;
import com.finora.service.FlowTotals;
import com.finora.service.InflowChoiceService;
import com.finora.service.InflowChoices;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The user's inflow kinds and their choices (Plan 2, docs/superpowers/specs/2026-09-27-inflow-kinds-design.md).
 * Reads that may create the built-ins on first use are read-write transactions: a readOnly one would
 * drop that insert silently.
 */
@Service
public class InflowKindService {

    private static final Map<FlowClassifier.FlowReason, String> AUTOMATIC_SUMMARY = new EnumMap<>(Map.ofEntries(
            Map.entry(FlowClassifier.FlowReason.SALARY, "Income · salary"),
            Map.entry(FlowClassifier.FlowReason.INTEREST, "Income · interest"),
            Map.entry(FlowClassifier.FlowReason.DIVIDEND, "Income · dividend"),
            Map.entry(FlowClassifier.FlowReason.REWARD, "Income · reward or cashback"),
            Map.entry(FlowClassifier.FlowReason.TAX_REFUND, "Income · tax refund"),
            Map.entry(FlowClassifier.FlowReason.OTHER_INCOME, "Income"),
            Map.entry(FlowClassifier.FlowReason.USER_ENTERED, "Income · added by you"),
            Map.entry(FlowClassifier.FlowReason.LINKED_REFUND, "Refund of a purchase"),
            Map.entry(FlowClassifier.FlowReason.UNLINKED_REFUND, "Refund"),
            Map.entry(FlowClassifier.FlowReason.REVERSAL, "Reversal"),
            Map.entry(FlowClassifier.FlowReason.CARD_ADJUSTMENT, "Card adjustment"),
            Map.entry(FlowClassifier.FlowReason.OWN_ACCOUNT_TRANSFER, "Transfer between your accounts"),
            Map.entry(FlowClassifier.FlowReason.CARD_PAYMENT_RECEIVED, "Card bill payment"),
            Map.entry(FlowClassifier.FlowReason.INVESTMENT_WITHDRAWAL, "Money back from an investment"),
            Map.entry(FlowClassifier.FlowReason.LOAN_DRAWDOWN, "Loan money received"),
            Map.entry(FlowClassifier.FlowReason.PERSON_INFLOW, "Not counted yet · from a person"),
            Map.entry(FlowClassifier.FlowReason.CARD_UNEXPLAINED_CREDIT, "Not counted yet · card credit")));

    private final InflowKindRepository kinds;
    private final SenderInflowRuleRepository rules;
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final InflowChoiceService inflowChoices;

    public InflowKindService(InflowKindRepository kinds, SenderInflowRuleRepository rules,
                             TransactionRepository transactions, AccountRepository accounts,
                             CategoryRepository categories, InflowChoiceService inflowChoices) {
        this.kinds = kinds;
        this.rules = rules;
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.inflowChoices = inflowChoices;
    }

    // ---- kinds ----

    @Transactional
    public List<InflowDtos.InflowKindDto> list(UUID userId) {
        ensureBuiltIns(userId);
        List<InflowKind> all = new ArrayList<>(kinds.findByUserId(userId));
        all.sort(Comparator
                .comparing((InflowKind k) -> k.getBuiltIn() == null ? Integer.MAX_VALUE : k.getBuiltIn().ordinal())
                .thenComparing(k -> k.getName().toLowerCase(Locale.ROOT)));
        return all.stream().map(InflowDtos.InflowKindDto::from).toList();
    }

    @Transactional
    public InflowDtos.InflowKindDto create(UUID userId, InflowDtos.CreateKindRequest req) {
        ensureBuiltIns(userId);
        String name = req.name().trim();
        if (name.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "A kind needs a name.");
        if (kinds.existsByUserIdAndNameIgnoreCase(userId, name)) throw new ApiException(ErrorCode.INFLOW_KIND_NAME_TAKEN);
        InflowKind k = new InflowKind();
        k.setUserId(userId);
        k.setName(name);
        k.setCountsAsIncome(req.countsAsIncome());
        return InflowDtos.InflowKindDto.from(kinds.save(k));
    }

    @Transactional
    public InflowDtos.InflowKindDto update(UUID userId, UUID kindId, InflowDtos.UpdateKindRequest req) {
        InflowKind k = ownedKind(userId, kindId);
        if (req.countsAsIncome() != null && k.getBuiltIn() != null && req.countsAsIncome() != k.isCountsAsIncome()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A built-in kind always " + (k.isCountsAsIncome() ? "counts" : "doesn't count")
                            + " as income. Create your own kind for a different rule.");
        }
        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "A kind needs a name.");
            if (kinds.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, name, kindId)) {
                throw new ApiException(ErrorCode.INFLOW_KIND_NAME_TAKEN);
            }
            k.setName(name);
        }
        if (req.countsAsIncome() != null && k.getBuiltIn() == null) k.setCountsAsIncome(req.countsAsIncome());
        return InflowDtos.InflowKindDto.from(kinds.save(k));
    }

    @Transactional
    public void delete(UUID userId, UUID kindId) {
        InflowKind k = ownedKind(userId, kindId);
        if (k.getBuiltIn() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Built-in kinds can be renamed but not deleted.");
        }
        // A deleted row the user can no longer see must not keep the kind "in use" or trip the FK.
        transactions.clearInflowKindOnDeletedRows(kindId);
        long rowCount = transactions.countLiveByInflowKindId(kindId);
        long senderCount = rules.countByInflowKindId(kindId);
        if (rowCount > 0 || senderCount > 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INFLOW_KIND_IN_USE,
                    ErrorCode.INFLOW_KIND_IN_USE.defaultMessage(), Map.of("rows", rowCount, "senders", senderCount));
        }
        kinds.delete(k);
    }

    // ---- choices ----

    @Transactional
    public InflowDtos.CountsAsDto setChoice(UUID userId, UUID txnId, InflowDtos.SetChoiceRequest req) {
        Transaction t = ownedTransaction(userId, txnId);
        String refusal = notChoosableReason(t);
        if (refusal != null) throw new ApiException(HttpStatus.BAD_REQUEST, refusal);
        InflowKind kind = ownedKind(userId, req.kindId());
        if (req.scope() == InflowChoices.Scope.SENDER) {
            String key = t.getCounterpartyKey();
            if (key == null || key.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "We can't tell who sent this payment, so it can only be set on its own.");
            }
            SenderInflowRule rule = rules.findByUserIdAndCounterpartyKey(userId, key).orElseGet(() -> {
                SenderInflowRule r = new SenderInflowRule();
                r.setUserId(userId);
                r.setCounterpartyKey(key);
                return r;
            });
            rule.setInflowKindId(kind.getId());
            rule.touch();
            rules.save(rule);
            // Otherwise the sender choice would not reach the very row the user chose it on.
            if (t.getInflowKindId() != null) {
                t.setInflowKindId(null);
                transactions.save(t);
            }
        } else {
            t.setInflowKindId(kind.getId());
            transactions.save(t);
        }
        return countsAs(userId, txnId);
    }

    @Transactional
    public InflowDtos.CountsAsDto clearChoice(UUID userId, UUID txnId, InflowChoices.Scope scope) {
        Transaction t = ownedTransaction(userId, txnId);
        if (scope == InflowChoices.Scope.SENDER) {
            String key = t.getCounterpartyKey();
            if (key != null && !key.isBlank()) rules.findByUserIdAndCounterpartyKey(userId, key).ifPresent(rules::delete);
        } else if (t.getInflowKindId() != null) {
            t.setInflowKindId(null);
            transactions.save(t);
        }
        return countsAs(userId, txnId);
    }

    @Transactional(readOnly = true)
    public InflowDtos.CountsAsDto countsAs(UUID userId, UUID txnId) {
        Transaction t = ownedTransaction(userId, txnId);
        FlowTotals.Context ctx = inflowChoices.contextFor(userId, accounts.findByUserId(userId), categories.findByUserId(userId));
        FlowClassifier.FlowDecision d = FlowTotals.decision(t, ctx);
        String refusal = notChoosableReason(t);
        // A pairing reconciliation made outranks the user's kind (FlowClassifier), so the kind is
        // only "applied" when it is what actually decided the row.
        InflowChoices.Chosen chosen = refusal == null ? FlowTotals.chosen(t, ctx) : null;
        String key = t.getCounterpartyKey();
        boolean senderAvailable = key != null && !key.isBlank();
        String summary = chosen != null
                ? (chosen.scope() == InflowChoices.Scope.SENDER
                        ? "You marked payments from this sender as " : "You marked this payment as ") + chosen.kind().getName()
                : AUTOMATIC_SUMMARY.getOrDefault(d.reason(),
                        d.flowClass() == FlowClassifier.FlowClass.EXPENSE ? "Spending" : "Money in");
        return new InflowDtos.CountsAsDto(d.flowClass().name(), d.reason().name(),
                chosen == null ? null : InflowDtos.InflowKindDto.from(chosen.kind()),
                chosen == null ? null : chosen.scope().name(),
                refusal == null, refusal, senderAvailable,
                senderAvailable ? SenderLabel.of(t) : null,
                senderAvailable ? transactions.countLiveCreditsBySender(userId, key) : 0L,
                summary);
    }

    // ---- remembered senders ----

    @Transactional(readOnly = true)
    public List<InflowDtos.SenderRuleDto> senderRules(UUID userId) {
        Map<UUID, InflowKind> byId = new HashMap<>();
        for (InflowKind k : kinds.findByUserId(userId)) byId.put(k.getId(), k);
        List<InflowDtos.SenderRuleDto> out = new ArrayList<>();
        for (SenderInflowRule r : rules.findByUserId(userId)) {
            InflowKind k = byId.get(r.getInflowKindId());
            if (k == null) continue;
            String label = transactions.findFirstByUserIdAndCounterpartyKeyOrderByTxnDateDesc(userId, r.getCounterpartyKey())
                    .map(SenderLabel::of).orElse("A sender with no payments left");
            out.add(new InflowDtos.SenderRuleDto(r.getId(), label, InflowDtos.InflowKindDto.from(k),
                    transactions.countLiveCreditsBySender(userId, r.getCounterpartyKey())));
        }
        out.sort(Comparator.comparing(InflowDtos.SenderRuleDto::label, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    @Transactional
    public void forgetSender(UUID userId, UUID ruleId) {
        SenderInflowRule r = OwnershipGuard.requireOwned(rules.findById(ruleId), SenderInflowRule::getUserId,
                userId, "Remembered sender");
        rules.delete(r);
    }

    // ---- helpers ----

    private void ensureBuiltIns(UUID userId) {
        if (kinds.countByUserIdAndBuiltInIsNotNull(userId) >= InflowKind.BuiltIn.values().length) return;
        for (InflowKind.BuiltIn b : InflowKind.BuiltIn.values()) {
            kinds.insertBuiltInIfMissing(userId, b.defaultName(), b.countsAsIncome(), b.name());
        }
    }

    private InflowKind ownedKind(UUID userId, UUID kindId) {
        return OwnershipGuard.requireOwned(kinds.findById(kindId), InflowKind::getUserId, userId, "Kind");
    }

    private Transaction ownedTransaction(UUID userId, UUID txnId) {
        return OwnershipGuard.requireOwned(transactions.findById(txnId), Transaction::getUserId, userId, "Transaction");
    }

    /** Null when a kind may be set on this row; otherwise the plain reason it may not. */
    private static String notChoosableReason(Transaction t) {
        if (t.getTxnType() == Transaction.Type.EXPENSE) return "Only money coming in can be given a kind.";
        if (t.isTransfer()) {
            return "This payment is matched as a transfer between your accounts. Use \"Not a transfer\" first.";
        }
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND
                || t.getReconciliationStatus() == Transaction.ReconciliationStatus.REVERSAL) {
            return "This payment is already matched to the purchase it gives money back for.";
        }
        return null;
    }
}
