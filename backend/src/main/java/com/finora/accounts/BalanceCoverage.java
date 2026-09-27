package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How far back {@code Account.balance} already holds every transaction -- so a statement imported
 * out of month order does not add rows the balance already contains.
 *
 * <p>The balance is known as of the later of two dates:
 * <ul>
 *   <li>the statement whose closing balance last SET it ({@code Account.lastAbsoluteSetStatementId}):
 *       its period end, or its last row's date when it printed no period. A closing balance is the
 *       bank's own statement of everything up to then.</li>
 *   <li>the account's {@code balanceBaselineDate}: the day before the period of the statement that
 *       created the account with a stated opening balance. That opening is everything before it.</li>
 * </ul>
 *
 * <p>Measured before this existed, on one savings account with five chained monthly statements:
 * uploaded March, July, April, June, May, the balance ended 76,485 too high, because each older
 * month was added on top of July's closing balance, which already held it. Uploaded July first with
 * no closing balances stated, the same happened through the account's opening balance.
 *
 * <p>Deliberately says nothing about an account whose balance was typed in by hand
 * ({@code AccountService.update}) or created by hand: when that figure was "as of" was never
 * recorded, so it is not guessed, and imports on such accounts move the balance as they always did.
 *
 * <p>Stateless; each service that needs it builds one from repositories it already holds, rather
 * than every hand-built instance of those services in tests growing a constructor argument.
 */
public class BalanceCoverage {

    private final StatementImportRepository statementImportRepository;
    private final TransactionRepository transactionRepository;

    public BalanceCoverage(StatementImportRepository statementImportRepository,
                           TransactionRepository transactionRepository) {
        this.statementImportRepository = statementImportRepository;
        this.transactionRepository = transactionRepository;
    }

    /** The last day whose transactions {@code account}'s balance already holds, or null when that
     *  is not known (see the class comment). Reads the account's current anchor pointer, so call it
     *  before a new SET moves that pointer, and after a reversal has moved it back. */
    public LocalDate knownThrough(Account account) {
        LocalDate anchorDate = null;
        if (account.getLastAbsoluteSetStatementId() != null) {
            anchorDate = statementImportRepository.findById(account.getLastAbsoluteSetStatementId())
                    .map(this::asOf).orElse(null);
        }
        return later(anchorDate, account.getBalanceBaselineDate());
    }

    /**
     * The other half: the statement that covered some rows is being reversed (deleted or
     * superseded), and the balance has already been moved back to what stood before it. Rows it
     * covered that the balance now no longer holds are added here, and each statement's record is
     * lowered to match, so a later delete of that statement reverses what it now contributes.
     *
     * <p>Mutates {@code account}'s balance in place and does not save it: the caller holds the one
     * managed copy and saves it once (see BaseEntity's comment on why a second save of the same
     * instance fails). Saves the statements and rows it changes.
     *
     * <p>A row marked DUPLICATE stays out -- the mark says it is a copy -- and is recorded as having
     * its effect taken off, so un-marking it puts the effect back and deleting it moves nothing,
     * the same state a mark written with nothing covering the row would have left.
     */
    public void release(Account account) {
        List<StatementImport> covered = statementImportRepository
                .findByAccountIdAndBalanceCoveredThroughIsNotNull(account.getId());
        if (covered.isEmpty()) return;
        LocalDate known = knownThrough(account);
        BigDecimal added = BigDecimal.ZERO;
        for (StatementImport statement : covered) {
            if (statement.getSupersededBy() != null) continue;
            LocalDate coveredThrough = statement.getBalanceCoveredThrough();
            if (known != null && !coveredThrough.isAfter(known)) continue;
            List<Transaction> nowCounted = new ArrayList<>();
            List<Transaction> markedCopies = new ArrayList<>();
            for (Transaction t : transactionRepository.findByStatementImportId(statement.getId())) {
                LocalDate date = t.getTxnDate();
                if (date == null || date.isAfter(coveredThrough)) continue;
                if (known != null && !date.isAfter(known)) continue;
                if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.SUPERSEDED) continue;
                if (t.getIsDuplicateOf() != null) {
                    t.setDuplicateBalanceReversed(true);
                    t.setDuplicateBalanceAnchorId(null);
                    markedCopies.add(t);
                } else {
                    nowCounted.add(t);
                }
            }
            added = added.add(AccountBalanceConvention.netDelta(account.getAccountType(), nowCounted));
            if (!markedCopies.isEmpty()) transactionRepository.saveAll(markedCopies);
            if (!nowCounted.isEmpty() || !markedCopies.isEmpty()) {
                statement.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ADDITIVE);
            }
            statement.setBalanceCoveredThrough(known);
            statementImportRepository.save(statement);
        }
        if (added.signum() != 0) account.setBalance(account.getBalance().add(added));
    }

    private LocalDate asOf(StatementImport anchor) {
        if (anchor.getStatementPeriodEnd() != null) return anchor.getStatementPeriodEnd();
        return transactionRepository.findByStatementImportId(anchor.getId()).stream()
                .map(Transaction::getTxnDate).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
