package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.InflowKind;
import com.finora.entity.SenderInflowRule;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.SenderInflowRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads a user's inflow choices, and is the one place production code builds a FlowTotals.Context. */
@Service
public class InflowChoiceService {

    private final InflowKindRepository kinds;
    private final SenderInflowRuleRepository rules;

    public InflowChoiceService(InflowKindRepository kinds, SenderInflowRuleRepository rules) {
        this.kinds = kinds;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public InflowChoices forUser(UUID userId) {
        List<InflowKind> all = kinds.findByUserId(userId);
        if (all.isEmpty()) return InflowChoices.NONE;
        Map<UUID, InflowKind> byId = new HashMap<>();
        for (InflowKind k : all) byId.put(k.getId(), k);
        Map<String, UUID> bySender = new HashMap<>();
        for (SenderInflowRule r : rules.findByUserId(userId)) bySender.put(r.getCounterpartyKey(), r.getInflowKindId());
        return new InflowChoices(byId, bySender);
    }

    public FlowTotals.Context contextFor(UUID userId, Collection<Account> accounts, Collection<Category> categories) {
        return FlowTotals.context(accounts, categories, forUser(userId));
    }
}
