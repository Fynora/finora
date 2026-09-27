package com.finora.imports.refresh;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementImportExcludedRow;
import com.finora.entity.Transaction;
import com.finora.imports.RowKind;
import com.finora.imports.refresh.StatementRefreshDiff.FreshRow;
import com.finora.imports.refresh.StatementRefreshDiff.KnownKind;
import com.finora.imports.refresh.StatementRefreshDiff.KnownRow;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportExcludedRowRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What both the dry run and the refresh itself compare, and the checks that stop either from
 * trusting a re-read: one place, so the dry run's verdict is always the verdict a refresh reaches.
 */
@Component
public class StatementRefreshInputs {

    private final TransactionRepository transactionRepository;
    private final StatementImportExcludedRowRepository excludedRowRepository;
    private final AccountRepository accountRepository;

    public StatementRefreshInputs(TransactionRepository transactionRepository,
                                  StatementImportExcludedRowRepository excludedRowRepository,
                                  AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.excludedRowRepository = excludedRowRepository;
        this.accountRepository = accountRepository;
    }

    /** The parsed rows, each with the staged row it came from (a refresh inserts from the latter). */
    public record Fresh(List<FreshRow> rows, Map<FreshRow, StagedRow> staged) {}

    /** Everything the statement has now: its transactions, the ones the user deleted included, and
     *  the rows the user left out at import. */
    public List<KnownRow> knownRows(StatementImport statement) {
        List<KnownRow> known = new ArrayList<>();
        for (TransactionRepository.StatementRowView row :
                transactionRepository.findStatementRowsIncludingDeleted(statement.getUserId(), statement.getId())) {
            known.add(new KnownRow(row.getId(), row.getDeletedAt() == null ? KnownKind.LIVE : KnownKind.DELETED,
                    row.getSourceRowPosition(), row.getTxnDate(), row.getDescription(), row.getAmount(),
                    row.getTxnType(), row.getBalanceAfter(), row.getReferenceNumber(),
                    editedFields(row.getUserEditedFields())));
        }
        for (StatementImportExcludedRow row : excludedRowRepository.findByStatementImportIdOrderByRowPositionAsc(statement.getId())) {
            known.add(new KnownRow(null, KnownKind.EXCLUDED, row.getRowPosition(), row.getTxnDate(),
                    row.getDescription(), row.getAmount(), row.getTxnType(), null, null, Set.of()));
        }
        return known;
    }

    /** The transaction rows of a re-read. Identity-keyed: two staged rows can compare equal. */
    public static Fresh freshRows(StagingResponse staging) {
        List<FreshRow> rows = new ArrayList<>();
        Map<FreshRow, StagedRow> staged = new IdentityHashMap<>();
        for (StagedRow row : staging.rows()) {
            if (row.kind() != null && row.kind() != RowKind.TRANSACTION) continue;
            FreshRow fresh = new FreshRow(row.rowPosition(), row.date(), row.description(), row.amount(), row.type(),
                    row.balanceAfter(), row.referenceNumber());
            rows.add(fresh);
            staged.put(fresh, row);
        }
        return new Fresh(rows, staged);
    }

    /**
     * Why this re-read must not be trusted, or null when it may be. Checked before any diff is acted
     * on, by the dry run and the refresh alike.
     */
    public String refusal(StatementImport statement, DetectedAccountInfo detected, List<KnownRow> known,
                          List<FreshRow> fresh) {
        if (namesAnotherAccount(statement, detected)) {
            // A multi-account PDF re-read by a parser that orders its account sections differently
            // hands back a different account's section at the stored index. Compared anyway, that
            // account's rows would be offered as this statement's.
            return "ACCOUNT_MISMATCH";
        }
        if (fresh.isEmpty() && liveRows(known) > 0) {
            // A statement that now parses to nothing is a parser or file problem, never "every
            // transaction was wrong": taken at face value it would delete them all.
            return "PARSED_NO_ROWS";
        }
        return null;
    }

    public static long liveRows(List<KnownRow> known) {
        return known.stream().filter(k -> k.kind() == KnownKind.LIVE).count();
    }

    /**
     * Whether a diff takes away too much to be a correction. Rows the user edited that no longer
     * pair (conflicts) are removed by a refresh too, so they count.
     */
    public static boolean removesTooMuch(StatementRefreshDiff.Result diff, long liveRows) {
        return removesTooMuch(diff.removed().size() + diff.conflicts().size(), liveRows);
    }

    /**
     * More than a quarter of a statement's live rows (and more than two) gone at once looks like a
     * parser regression -- a layout it stopped recognising -- not a correction. A real fix removes a
     * page-furniture row or two. Conservative on purpose, and a starting point: the dry run's own
     * results on the corpus are what should tune it. Such a statement is never refreshed.
     */
    static boolean removesTooMuch(int removed, long liveRows) {
        return removed > 2 && removed * 4L > liveRows;
    }

    /** Both sides carry a card or account number and their last 4 digits differ. Either missing is not a mismatch. */
    private boolean namesAnotherAccount(StatementImport statement, DetectedAccountInfo now) {
        if (now == null) return false;
        String reread = last4(now.accountNumberMasked());
        if (reread == null) return false;
        String stored = accountRepository.findById(statement.getAccountId())
                .map(a -> last4(a.getAccountNumberMasked())).orElse(null);
        return stored != null && !stored.equals(reread);
    }

    private static String last4(String masked) {
        if (masked == null) return null;
        String digits = masked.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : null;
    }

    /** Tolerant: a name this build doesn't know is skipped, as Transaction.getUserEditedFields does. */
    static Set<Transaction.EditableField> editedFields(String[] names) {
        EnumSet<Transaction.EditableField> fields = EnumSet.noneOf(Transaction.EditableField.class);
        if (names == null) return fields;
        for (String name : names) {
            for (Transaction.EditableField f : Transaction.EditableField.values()) {
                if (f.name().equals(name)) fields.add(f);
            }
        }
        return fields;
    }

    /** A statement-level fact as recorded and as read now. */
    public record FactChange(String field, Object before, Object after) {}

    /**
     * The statement's own facts, as recorded against as read now. A fact the parser does not read
     * now (null) is not reported: a parse that finds less is not evidence the stored value is wrong.
     */
    public static List<FactChange> factChanges(StatementImport s, DetectedAccountInfo now) {
        List<FactChange> changes = new ArrayList<>();
        if (now == null) return changes;
        addFact(changes, "STATEMENT_PERIOD_START", s.getStatementPeriodStart(), now.statementPeriodStart());
        addFact(changes, "STATEMENT_PERIOD_END", s.getStatementPeriodEnd(), now.statementPeriodEnd());
        addFact(changes, "OPENING_BALANCE", s.getOpeningBalance(), now.openingBalance());
        addFact(changes, "CLOSING_BALANCE", s.getClosingBalance(), now.closingBalance());
        addFact(changes, "TOTAL_AMOUNT_DUE", s.getTotalAmountDue(), now.totalAmountDue());
        addFact(changes, "PAYMENT_DUE_DATE", s.getPaymentDueDate(), now.paymentDueDate());
        return changes;
    }

    private static void addFact(List<FactChange> changes, String field, Object before, Object after) {
        if (after == null) return;
        boolean same = before instanceof BigDecimal b && after instanceof BigDecimal a
                ? b.compareTo(a) == 0 : Objects.equals(before, after);
        if (!same) changes.add(new FactChange(field, before, after));
    }

    static Map<String, Object> factJson(FactChange f) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", f.field());
        change.put("before", f.before() == null ? null : f.before().toString());
        change.put("after", f.after().toString());
        return change;
    }
}
