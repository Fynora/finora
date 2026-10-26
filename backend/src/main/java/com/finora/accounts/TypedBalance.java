package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A balance the user stated: by editing the account, or by answering "How much do you owe on this
 * card today?" when an import had no closing balance. One place, so the two cannot drift.
 *
 * <p>A typed balance is the whole truth as of the moment it was typed, the same as a statement's
 * closing balance:
 * <ul>
 *   <li>It replaces any statement's claim to be the account's live SET anchor -- the same way a
 *       later ABSOLUTE statement would. Automatic balance lineage is intentionally abandoned once
 *       the user states the figure (see the "absolute balance reversal" design spec, Case D).</li>
 *   <li>It holds everything already on the account (see {@link RowBalanceEffect}), and every
 *       transaction dated before today: a statement for an earlier period imported afterwards does
 *       not add those again (see {@link BalanceCoverage}). Today itself is left out -- a
 *       transaction made later today may or may not be in a balance typed this morning, and
 *       counting it is the recoverable mistake. "Today" is the user's own day, in their timezone
 *       ({@link com.finora.util.UserZone}).</li>
 *   <li>The bank's figure already carries every real transaction, whether or not Finora is
 *       currently showing a given row as a duplicate. So a marked row's record ({@link
 *       Transaction#isDuplicateBalanceReversed()}) says its mark took nothing off: un-marking it
 *       later adds nothing, and deleting it moves nothing. This used to record a marked row as
 *       taken off the typed figure, so un-marking it after a balance edit added it on top. It also
 *       releases every mark the outgoing SET held ({@link Transaction#getDuplicateBalanceAnchorId()});
 *       the SET's pre-set snapshot is unreachable once the anchor is cleared, so nothing else
 *       could.</li>
 * </ul>
 *
 * <p>Nothing here saves the account: the caller holds the managed copy. Not a bean: {@link
 * AccountService} is its one owner, and every stated balance (an edit, or the import's
 * {@link AccountService#stateAmountOwedAtImport}) goes through it.
 */
public class TypedBalance {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    public TypedBalance(UserRepository userRepository, TransactionRepository transactionRepository) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
    }

    /** Sets the balance the user stated and records it as stated, now. */
    public void state(Account account, BigDecimal balance) {
        account.setBalance(balance);
        account.setLastAbsoluteSetStatementId(null);
        markTyped(account);
        rebaseDuplicateMarks(account);
    }

    /** Records that the account's current balance was stated by the user, now. */
    public void markTyped(Account account) {
        account.setBalanceTypedAt(java.time.Instant.now());
        java.time.ZoneId zone = com.finora.util.UserZone.forUser(userRepository, account.getUserId());
        account.setBalanceBaselineDate(java.time.LocalDate.now(zone).minusDays(1));
    }

    private void rebaseDuplicateMarks(Account account) {
        List<Transaction> marked = transactionRepository.findByAccountIdAndIsDuplicateOfIsNotNull(account.getId());
        if (marked.isEmpty()) return;
        List<Transaction> dirty = new ArrayList<>();
        for (Transaction t : marked) {
            if (!t.isDuplicateBalanceReversed() && t.getDuplicateBalanceAnchorId() == null) continue;
            t.setDuplicateBalanceReversed(false);
            t.setDuplicateBalanceAnchorId(null);
            dirty.add(t);
        }
        if (!dirty.isEmpty()) transactionRepository.saveAll(dirty);
    }
}
