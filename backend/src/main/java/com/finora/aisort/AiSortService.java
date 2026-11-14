package com.finora.aisort;

import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ResolutionIndex;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.CategorizationService;
import com.finora.service.FynAvailabilityGuard;
import com.finora.service.FynCategorizationFallbackService;
import com.finora.service.FynCostGovernanceService;
import com.finora.util.CounterpartyIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI sorting after an import (spec docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md):
 * which imports get the Done step's card, and the payees a tap would sort.
 */
@Service
public class AiSortService {

    /** As InvitePromptService.MAX_IDS: one confirm makes at most a handful of statement imports. */
    public static final int MAX_IDS = 20;

    /**
     * A payee the AI may be asked about: a key that names one payee, its waiting payments out in these
     * imports whose narration may go to the model, and no saved answer yet. {@code amount} is their total.
     */
    public record SortablePayee(String counterpartyKey, List<Transaction> importRows, BigDecimal amount) {}

    private final UserRepository users;
    private final TransactionRepository transactions;
    private final FynAvailabilityGuard guard;
    private final FynCostGovernanceService costs;
    private final FynCategorizationFallbackService fallback;

    public AiSortService(UserRepository users, TransactionRepository transactions, FynAvailabilityGuard guard,
                         FynCostGovernanceService costs, FynCategorizationFallbackService fallback) {
        this.users = users;
        this.transactions = transactions;
        this.guard = guard;
        this.costs = costs;
        this.fallback = fallback;
    }

    /**
     * Whether to show the card for {@code statementImportIds}, the statement imports one confirm just
     * made. Ids that are not the caller's simply find no rows. A customer never asked about AI (null)
     * never gets it: the question step asks them first.
     */
    @Transactional(readOnly = true)
    public AiSortDto.Offer offer(UUID userId, Collection<UUID> statementImportIds) {
        AiSortDto.Mode mode = modeFor(userId);
        if (mode == null || !guard.categorizationOffered()) return AiSortDto.Offer.none();
        List<SortablePayee> payees = sortablePayees(userId, statementImportIds);
        if (payees.isEmpty()) return AiSortDto.Offer.none();
        int payments = payees.stream().mapToInt(p -> p.importRows().size()).sum();
        BigDecimal amount = payees.stream().map(SortablePayee::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AiSortDto.Offer(true, mode, payments, amount, payees.size());
    }

    /** SORT for a yes not yet at today's cap, ASK for a no not asked again yet, else null (no card). */
    AiSortDto.Mode modeFor(UUID userId) {
        User user = users.findById(userId).orElse(null);
        if (user == null) return null;
        Boolean consent = user.getAiProcessingConsent();
        if (Boolean.TRUE.equals(consent)) {
            return costs.userDailyCapReached(userId) ? null : AiSortDto.Mode.SORT;
        }
        if (Boolean.FALSE.equals(consent) && user.getAiImportQuestionAt() == null) return AiSortDto.Mode.ASK;
        return null;
    }

    /** The payees a tap would sort, the most money first (then by key, so the order is stable). */
    @Transactional(readOnly = true)
    public List<SortablePayee> sortablePayees(UUID userId, Collection<UUID> statementImportIds) {
        if (statementImportIds == null || statementImportIds.isEmpty()) return List.of();
        List<Transaction> waiting = transactions.findWaitingExpensesInImports(userId, statementImportIds);
        if (waiting.isEmpty()) return List.of();
        Map<String, List<Transaction>> byPayee = new LinkedHashMap<>();
        for (Transaction t : waiting) {
            String key = t.getCounterpartyKey();
            if (!CounterpartyIdentity.identifiesOnePayee(key)) continue;
            // Payments to people, and anything else the model may not see, are not counted: the AI
            // would never be asked about them.
            if (!CategorizationService.mayGoToModel(t.getDescription())) continue;
            byPayee.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        if (byPayee.isEmpty()) return List.of();
        ResolutionIndex saved = fallback.indexFor(userId);
        List<SortablePayee> out = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> e : byPayee.entrySet()) {
            if (fallback.suggestReadOnly(userId, e.getKey(), Transaction.Type.EXPENSE, saved).isPresent()) continue;
            BigDecimal amount = e.getValue().stream().map(t -> t.getAmount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new SortablePayee(e.getKey(), List.copyOf(e.getValue()), amount));
        }
        out.sort(Comparator.comparing(SortablePayee::amount).reversed().thenComparing(SortablePayee::counterpartyKey));
        return out;
    }
}
