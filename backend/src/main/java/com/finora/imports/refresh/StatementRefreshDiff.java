package com.finora.imports.refresh;

import com.finora.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * What re-reading one stored statement with today's parser would change: the rows as they are
 * now, compared with the rows the parser produces now. Pure -- no database, no parsing -- so the
 * dry run and the refresh itself (step 3) decide the same way, and every rule here is testable.
 *
 * <h2>Matching</h2>
 *
 * <p>A parser fix can insert a row it used to miss, which shifts every later position, so position
 * alone is not a safe key. Rows are paired in four passes, each allowing one of the statement's own
 * fields to differ, and each pairing in document order:
 * <ol>
 *   <li>date, description, amount and type all equal -- the row is the same;</li>
 *   <li>date, amount and type -- the description was read differently;</li>
 *   <li>description, amount and type -- the date was read differently;</li>
 *   <li>date, description and type -- the amount was read differently.</li>
 * </ol>
 * Two fields changing at once are not paired: the old row counts as removed and the new one as
 * added. That is deliberately conservative -- pairing on a single field would marry unrelated rows.
 *
 * <p>The known side includes rows the user deleted and rows they left out at import, so neither
 * comes back as "added": a row that pairs with one of them stays deleted or excluded.
 *
 * <h2>What counts as a change</h2>
 *
 * <p>For a live row: date, description, amount, type, running balance and reference number -- the
 * fields the statement itself decides -- except any field the user edited by hand, which a refresh
 * keeps. A row whose only differences are in fields the user edited is unchanged.
 *
 * <p>A live row with a user edit that no new row pairs with is reported as a conflict, not a
 * removal: the likeliest explanation is that the parser now reads a second field differently too,
 * and deleting it would throw the user's correction away with it.
 */
public final class StatementRefreshDiff {

    private StatementRefreshDiff() {}

    public enum KnownKind { LIVE, DELETED, EXCLUDED }

    /** A row the statement has now: a transaction (live or deleted by the user) or an excluded row. */
    public record KnownRow(UUID transactionId, KnownKind kind, Integer position, LocalDate date, String description,
                           BigDecimal amount, String type, BigDecimal balanceAfter, String referenceNumber,
                           Set<Transaction.EditableField> userEdited) {}

    /** A row the parser produces now. */
    public record FreshRow(Integer position, LocalDate date, String description, BigDecimal amount, String type,
                           BigDecimal balanceAfter, String referenceNumber) {}

    public enum Field { DATE, DESCRIPTION, AMOUNT, TYPE, BALANCE_AFTER, REFERENCE_NUMBER }

    public record FieldChange(Field field, String before, String after) {}

    public record Changed(UUID transactionId, FreshRow fresh, List<FieldChange> changes) {}

    public record Result(List<Changed> changed, List<FreshRow> added, List<KnownRow> removed,
                         List<KnownRow> conflicts, int unchanged, int staysDeleted, int staysExcluded) {
        /** Whether a refresh would change anything at all for this statement. */
        public boolean hasChanges() {
            return !changed.isEmpty() || !added.isEmpty() || !removed.isEmpty() || !conflicts.isEmpty();
        }
    }

    /** Stands for the field a matching pass lets differ. Declared before PASSES, which uses it. */
    private static final Object ANY = new Object();

    private record Pass(String name, Function<KnownRow, String> knownKey, Function<FreshRow, String> freshKey) {}

    private static final List<Pass> PASSES = List.of(
            new Pass("same",
                    k -> key(k.date(), k.description(), k.amount(), k.type()),
                    f -> key(f.date(), f.description(), f.amount(), f.type())),
            new Pass("description differs",
                    k -> key(k.date(), ANY, k.amount(), k.type()),
                    f -> key(f.date(), ANY, f.amount(), f.type())),
            new Pass("date differs",
                    k -> key(ANY, k.description(), k.amount(), k.type()),
                    f -> key(ANY, f.description(), f.amount(), f.type())),
            new Pass("amount differs",
                    k -> key(k.date(), k.description(), ANY, k.type()),
                    f -> key(f.date(), f.description(), ANY, f.type())));

    public static Result compute(List<KnownRow> known, List<FreshRow> fresh) {
        List<KnownRow> knownLeft = new ArrayList<>(known);
        knownLeft.sort(Comparator.comparing(KnownRow::position, Comparator.nullsLast(Comparator.naturalOrder())));
        List<FreshRow> freshLeft = new ArrayList<>(fresh);
        freshLeft.sort(Comparator.comparing(FreshRow::position, Comparator.nullsLast(Comparator.naturalOrder())));

        List<Changed> changed = new ArrayList<>();
        int unchanged = 0, staysDeleted = 0, staysExcluded = 0;

        for (Pass pass : PASSES) {
            Map<String, List<KnownRow>> byKey = new HashMap<>();
            for (KnownRow k : knownLeft) byKey.computeIfAbsent(pass.knownKey().apply(k), x -> new ArrayList<>()).add(k);
            List<FreshRow> stillFresh = new ArrayList<>();
            for (FreshRow f : freshLeft) {
                List<KnownRow> candidates = byKey.get(pass.freshKey().apply(f));
                if (candidates == null || candidates.isEmpty()) {
                    stillFresh.add(f);
                    continue;
                }
                KnownRow k = candidates.remove(0);
                knownLeft.remove(k);
                switch (k.kind()) {
                    case DELETED -> staysDeleted++;
                    case EXCLUDED -> staysExcluded++;
                    case LIVE -> {
                        List<FieldChange> changes = changesKeepingUserEdits(k, f);
                        if (changes.isEmpty()) unchanged++;
                        else changed.add(new Changed(k.transactionId(), f, changes));
                    }
                }
            }
            freshLeft = stillFresh;
        }

        List<KnownRow> removed = new ArrayList<>();
        List<KnownRow> conflicts = new ArrayList<>();
        for (KnownRow k : knownLeft) {
            if (k.kind() != KnownKind.LIVE) continue; // already gone, or never imported
            if (k.userEdited() != null && !k.userEdited().isEmpty()) conflicts.add(k);
            else removed.add(k);
        }
        return new Result(changed, freshLeft, removed, conflicts, unchanged, staysDeleted, staysExcluded);
    }

    private static List<FieldChange> changesKeepingUserEdits(KnownRow k, FreshRow f) {
        Set<Transaction.EditableField> edited = k.userEdited() == null ? Set.of() : k.userEdited();
        List<FieldChange> changes = new ArrayList<>();
        if (!edited.contains(Transaction.EditableField.DATE) && !Objects.equals(k.date(), f.date())) {
            changes.add(new FieldChange(Field.DATE, str(k.date()), str(f.date())));
        }
        if (!edited.contains(Transaction.EditableField.DESCRIPTION) && !Objects.equals(k.description(), f.description())) {
            changes.add(new FieldChange(Field.DESCRIPTION, k.description(), f.description()));
        }
        if (!edited.contains(Transaction.EditableField.AMOUNT) && !sameMoney(k.amount(), f.amount())) {
            changes.add(new FieldChange(Field.AMOUNT, money(k.amount()), money(f.amount())));
        }
        if (!edited.contains(Transaction.EditableField.TYPE) && !Objects.equals(k.type(), f.type())) {
            changes.add(new FieldChange(Field.TYPE, k.type(), f.type()));
        }
        if (!sameMoney(k.balanceAfter(), f.balanceAfter())) {
            changes.add(new FieldChange(Field.BALANCE_AFTER, money(k.balanceAfter()), money(f.balanceAfter())));
        }
        if (!Objects.equals(blankAsNull(k.referenceNumber()), blankAsNull(f.referenceNumber()))) {
            changes.add(new FieldChange(Field.REFERENCE_NUMBER, k.referenceNumber(), f.referenceNumber()));
        }
        return changes;
    }

    /** The pass's key. {@link #ANY} for the field a pass lets differ; a real null stays distinct from
     *  it, and amount compares by value (100 and 100.00 are the same money, read by different parsers). */
    private static String key(Object date, Object description, Object amount, String type) {
        return part(date) + "|" + part(description) + "|" + part(amount) + "|" + part(type);
    }

    private static String part(Object value) {
        if (value == ANY) return "*";
        if (value == null) return "n";
        if (value instanceof BigDecimal b) return "v:" + money(b);
        return "v:" + value;
    }

    private static boolean sameMoney(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String blankAsNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
