package com.finora.imports;

import com.finora.dto.ImportDto.DetectedAccountInfo;

import java.time.LocalDate;
import java.util.Set;

/**
 * Which printed statement period the import pipeline may judge a statement by.
 *
 * <p>Two decisions read the printed period: the trust check ({@code TrustPredicate}'s
 * period-integrity rule, which holds a statement whose period "is in the future") and the Free
 * plan's one-month limit ({@link FreeStatementPeriod}: the notice shown right after upload and the
 * refusal at confirm, which share one code path). Both take the period through
 * this one rule, so a bank whose printed period is not a real statement period is ignored by both
 * in the same way.
 *
 * <p><b>slice small finance bank</b> is the one such bank so far (owner's decision, 2026-10-09). A
 * real slice statement held in production printed the whole financial year, 1 April to 31 March,
 * while its transactions ran only up to its upload day. The printed end date had not arrived yet,
 * so the trust check held a statement whose every row was readable and whose running balance
 * reconciled, and the Free limit judged it by twelve printed months instead of by the dates its
 * transactions actually cover. For slice the printed period is therefore treated as absent:
 * the trust check skips the period rule (a missing period never holds), and the Free limit judges
 * the earliest to the latest transaction date only, which {@link FreeStatementPeriod} already does
 * for every statement. A slice statement whose transactions span more than a month is still
 * refused on Free.
 *
 * <p>The period stored and shown with the import is not affected: this only decides what the two
 * checks above read.
 */
public final class StatementPeriodPolicy {

    /** {@code BankRegistry} ids whose printed period is not judged. */
    static final Set<String> BANKS_WITH_UNRELIABLE_PRINTED_PERIOD = Set.of("SLICE");

    private StatementPeriodPolicy() {}

    /**
     * {@code {start, end}} for the checks to judge, possibly holding nulls. Both are null when
     * nothing was detected, and when the statement's bank prints a period that is not judged (see
     * the class doc). A missing period is never on its own a reason to hold or refuse.
     */
    public static LocalDate[] judgedPeriod(DetectedAccountInfo detected) {
        if (detected == null || printedPeriodIsNotJudged(detected)) {
            return new LocalDate[]{null, null};
        }
        return new LocalDate[]{detected.statementPeriodStart(), detected.statementPeriodEnd()};
    }

    private static boolean printedPeriodIsNotJudged(DetectedAccountInfo detected) {
        return detected.bank() != null && detected.bank().id() != null
                && BANKS_WITH_UNRELIABLE_PRINTED_PERIOD.contains(detected.bank().id());
    }
}
