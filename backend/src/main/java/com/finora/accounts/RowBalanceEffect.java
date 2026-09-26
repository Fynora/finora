package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.repository.StatementImportRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Where one transaction's own effect sits right now, so editing or deleting it moves the right
 * figure -- and only when the effect is really there.
 *
 * <p>The rule: a stated figure holds everything before it. A statement's closing balance that SET
 * the account, a balance the user typed in, and the opening balance an account was created with
 * are each the whole truth as of when they were stated. A row already inside one of them is not
 * separately in {@code Account.balance}, so deleting it (a misread line, a row the user does not
 * want) or correcting its amount must not change the balance: the stated figure did not change.
 * This used to move the balance anyway, for every row the mark had not taken off.
 *
 * <p>Three places a row's effect can be:
 * <ul>
 *   <li>{@link Where#BALANCE}: added to {@code Account.balance} when it arrived and still there --
 *       a manual entry or an ADDITIVE import row that came after every stated figure.</li>
 *   <li>{@link Where#SNAPSHOT}: added to the balance before a statement's closing balance SET it,
 *       so it is inside that statement's pre-SET snapshot ({@code balanceBeforeAbsoluteSet}). The
 *       current balance does not hold it separately, but reversing that SET restores the snapshot,
 *       so a change to the row is made to the snapshot -- otherwise deleting the statement later
 *       would bring a deleted row's effect back.</li>
 *   <li>{@link Where#NOWHERE}: inside a stated figure and nowhere else (a SET statement's own rows,
 *       rows covered at import, rows from before the balance was typed), taken off by a duplicate
 *       mark, or from a source that never moves the balance.</li>
 * </ul>
 *
 * <p>Statements from before balance-mode tracking ({@code UNKNOWN_LEGACY}) keep moving the balance,
 * as they always did: whether their rows ever moved it was never recorded, and it is not guessed.
 */
public class RowBalanceEffect {

    public enum Where { BALANCE, SNAPSHOT, NOWHERE }

    public record Location(Where where, UUID holderStatementId) {
        static final Location BALANCE = new Location(Where.BALANCE, null);
        static final Location NOWHERE = new Location(Where.NOWHERE, null);
    }

    private final StatementImportRepository statementImportRepository;

    public RowBalanceEffect(StatementImportRepository statementImportRepository) {
        this.statementImportRepository = statementImportRepository;
    }

    /**
     * @param statement the row's statement import, or null when it has none (a manual entry)
     */
    public Location locate(Account account, Transaction row, StatementImport statement) {
        if (row.getIsDuplicateOf() != null && row.isDuplicateBalanceReversed()) return Location.NOWHERE;
        if (row.getSource() == Transaction.Source.ACCOUNT_AGGREGATOR) return Location.NOWHERE;
        if (statement != null) {
            StatementImport.BalanceApplicationMode mode = AccountBalanceConvention.effectiveMode(statement, row);
            if (mode == StatementImport.BalanceApplicationMode.UNKNOWN_LEGACY) return Location.BALANCE;
            if (mode != StatementImport.BalanceApplicationMode.ADDITIVE) return Location.NOWHERE;
        }
        Instant createdAt = row.getCreatedAt();
        if (createdAt == null) return Location.BALANCE;
        if (account.getBalanceTypedAt() != null && createdAt.isBefore(account.getBalanceTypedAt())) {
            return Location.NOWHERE;
        }
        // Walk the SET chain back from the live anchor: the row is held by the earliest SET that
        // happened after it arrived. One that arrived after the live SET is in the balance itself.
        StatementImportRepository.AnchorSnapshot holder = null;
        UUID id = account.getLastAbsoluteSetStatementId();
        while (id != null) {
            StatementImportRepository.AnchorSnapshot link = statementImportRepository
                    .findAnchorSnapshotIncludingDeleted(account.getUserId(), account.getId(), id).orElse(null);
            if (link == null || link.getImportedAt() == null || !createdAt.isBefore(link.getImportedAt())) break;
            holder = link;
            id = link.getPreviousAbsoluteSetStatementId();
        }
        if (holder == null) return Location.BALANCE;
        // A SET from before its snapshot was recorded cannot carry the change; the row then moves
        // the balance, as it always did.
        if (holder.getBalanceBeforeAbsoluteSet() == null) return Location.BALANCE;
        return new Location(Where.SNAPSHOT, holder.getId());
    }

    /**
     * Applies {@code delta} (a change in the row's own effect: its negation to remove it) where the
     * row's effect sits. Mutates {@code account}'s balance in place for {@link Where#BALANCE} and
     * does not save it -- the caller holds the managed copy. A snapshot is written here.
     */
    public void apply(Account account, Location location, BigDecimal delta) {
        if (delta == null || delta.signum() == 0) return;
        switch (location.where()) {
            case BALANCE -> account.setBalance(account.getBalance().add(delta));
            case SNAPSHOT -> {
                // A live statement goes through the entity, so a managed copy of it in this
                // transaction cannot later flush a stale snapshot over this change; a deleted one
                // cannot be loaded as an entity at all, so it is written directly.
                StatementImport live = statementImportRepository.findById(location.holderStatementId()).orElse(null);
                if (live != null) {
                    if (live.getBalanceBeforeAbsoluteSet() != null) {
                        live.setBalanceBeforeAbsoluteSet(live.getBalanceBeforeAbsoluteSet().add(delta));
                        statementImportRepository.save(live);
                    }
                } else {
                    statementImportRepository.adjustBalanceBeforeAbsoluteSetIncludingDeleted(
                            account.getUserId(), account.getId(), location.holderStatementId(), delta);
                }
            }
            case NOWHERE -> { }
        }
    }
}
