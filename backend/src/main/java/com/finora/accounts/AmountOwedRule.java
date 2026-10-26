package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.StatementImport.BalanceApplicationMode;
import com.finora.repository.StatementImportRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Whether Fynora knows what is owed on a credit card, or only a figure summed from zero.
 *
 * <p>A card statement with no closing balance (a CSV export, typically) is imported ADDITIVE: the
 * card starts at zero and adds up the rows. A card almost never starts at zero -- payments at the
 * start of the file clear a bill whose spends are not in it -- so the sum can be far off, even
 * negative. Until the user states the figure, it is not shown and not counted in any total.
 *
 * <p>A card's amount owed is not known when nothing ever stated it: no statement's closing balance
 * set it (no SET anchor), the user never typed it, it has no baseline date (an opening balance
 * read at import, or a typed balance), and its balance came only from statements whose effect is
 * recorded as added rows. A card with an {@code ABSOLUTE} or {@code UNKNOWN_LEGACY} statement is
 * left as known: an old SET that left no anchor is not second-guessed. A card with no statements
 * is the user's own (made by hand) and is never asked about.
 *
 * <p>Derived, never stored: typing a balance, a statement that sets the card, or deleting its only
 * statement each change the answer on their own.
 *
 * <p>Design: docs/superpowers/specs/2026-10-26-card-amount-owed-prompt-design.md.
 */
@Component
public class AmountOwedRule {

    private final StatementImportRepository statementImportRepository;

    public AmountOwedRule(StatementImportRepository statementImportRepository) {
        this.statementImportRepository = statementImportRepository;
    }

    /** The ids of {@code accounts} whose amount owed is not known. One query, and none when no
     *  account could qualify. */
    public Set<UUID> unknownCardIds(UUID userId, Collection<Account> accounts) {
        Set<UUID> candidates = new HashSet<>();
        for (Account a : accounts) {
            if (nothingStated(a)) candidates.add(a.getId());
        }
        // A mutable empty set, not Set.of(): callers ask contains(account.getId()), and Set.of()
        // throws on a null id (an unsaved account, as in hand-built tests).
        if (candidates.isEmpty()) return new HashSet<>();
        Map<UUID, Boolean> onlyAdded = new HashMap<>();
        for (StatementImportRepository.ImportMode m : statementImportRepository.findImportModesForUser(userId)) {
            if (!candidates.contains(m.getAccountId())) continue;
            BalanceApplicationMode mode = m.getBalanceApplicationMode();
            boolean added = mode != BalanceApplicationMode.ABSOLUTE && mode != BalanceApplicationMode.UNKNOWN_LEGACY;
            onlyAdded.merge(m.getAccountId(), added, Boolean::logicalAnd);
        }
        Set<UUID> unknown = new HashSet<>();
        onlyAdded.forEach((id, added) -> { if (added) unknown.add(id); });
        return unknown;
    }

    public boolean isUnknown(Account account) {
        return !unknownCardIds(account.getUserId(), List.of(account)).isEmpty();
    }

    /** Whether anything states this account's figure: a statement's closing balance that set it, a
     *  balance the user typed, or an opening balance read at import (a baseline date). */
    public static boolean balanceStated(Account a) {
        return a.getLastAbsoluteSetStatementId() != null
                || a.getBalanceTypedAt() != null
                || a.getBalanceBaselineDate() != null;
    }

    private static boolean nothingStated(Account a) {
        return a.getAccountType() == Account.Type.CREDIT_CARD && !balanceStated(a);
    }
}
