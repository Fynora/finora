package com.finora.service;

import com.finora.entity.InflowKind;
import com.finora.entity.Transaction;

import java.util.Map;
import java.util.UUID;

/**
 * One user's answers to "what was this credit" (Plan 2): their kinds, and which kind each sender
 * was given. Loaded once per totals call (InflowChoiceService) and read per row -- the row's own
 * choice first, then its sender's.
 */
public record InflowChoices(Map<UUID, InflowKind> kindsById, Map<String, UUID> kindIdBySenderKey) {

    public static final InflowChoices NONE = new InflowChoices(Map.of(), Map.of());

    /** Whether a choice came from this row or from the rule for its sender. */
    public enum Scope { ROW, SENDER }

    public record Chosen(InflowKind kind, Scope scope) {}

    /** The kind that applies to this row, or null. A debit never has one. */
    public Chosen chosenFor(Transaction t) {
        if (t.getTxnType() == Transaction.Type.EXPENSE) return null;
        if (t.getInflowKindId() != null) {
            InflowKind k = kindsById.get(t.getInflowKindId());
            if (k != null) return new Chosen(k, Scope.ROW);
        }
        String key = t.getCounterpartyKey();
        // A sender rule saved on a key that names no one (before such keys were refused, or carried
        // there by the counterparty backfill) would mark every stranger sharing it.
        if (!com.finora.util.CounterpartyIdentity.identifiesOnePayee(key)) return null;
        UUID kindId = kindIdBySenderKey.get(key);
        InflowKind k = kindId == null ? null : kindsById.get(kindId);
        return k == null ? null : new Chosen(k, Scope.SENDER);
    }
}
