package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives a stored report's balance discrepancies and description outliers the {@code displayIndex}
 * a report staged today carries, so a review reopened after this change marks its rows instead of
 * showing none.
 *
 * <p>A review session keeps its report for two days, and longer while the statement is held, and
 * serves it again whenever the customer comes back. The review screens mark a row only from
 * {@code displayIndex}; a stored report written before that field existed has only {@code
 * rowIndex}, which counted a different list depending on the build that wrote it. Which build that
 * was can be read off the report and its rows, exactly, so each old number converts without a
 * guess:
 *
 * <ul>
 *   <li><b>Balance, with {@code discrepancyShapes}.</b> That key was added by the same change (#2118)
 *       that made a discrepancy's {@code rowIndex} the row's place in the rows shown, and it is
 *       written whenever anything disagreed. So {@code displayIndex} is {@code rowIndex}.</li>
 *   <li><b>Balance, without it.</b> Written before #2118, by a build that numbered each discrepancy
 *       along the validator's walk: the rows in the document's own sequence, reversed when the first
 *       is dated after the last ({@link BalanceChainValidator}'s {@code chronological}, unchanged
 *       since 2026-08-15). The document's sequence is the rows shown ordered by {@code
 *       rowPosition}, which the PDF and CSV generators have stamped on every staged row since
 *       2026-08-28, in the order they read them; every report whose rows carry it was staged after
 *       both of those dates.</li>
 *   <li><b>Description outliers.</b> The check has always been handed the document's sequence (it
 *       was added on 2026-09-14, after the PDF path began checking that sequence on 2026-08-06), so
 *       an outlier's {@code rowIndex} is a place in it.</li>
 * </ul>
 *
 * <p>All of a finding's rows or none: if any one cannot be placed -- a row without a {@code
 * rowPosition}, two rows sharing one, a number outside the list -- the finding is returned as it
 * was stored, and the screens show it without a row number, as they would have before. A finding
 * that already carries {@code displayIndex}, and every other rule's finding, is returned untouched.
 */
public final class StoredRowPositions {

    private StoredRowPositions() {}

    private static final String DISPLAY_INDEX = "displayIndex";
    private static final String ROW_INDEX = "rowIndex";

    /**
     * @param shown the stored rows of the section the finding belongs to, in the order the client
     *              shows them
     */
    public static VerificationFinding withDisplayPositions(VerificationFinding finding, List<StagedRow> shown) {
        if (finding == null || finding.details() == null) return finding;
        if (BalanceChainValidator.RULE.equals(finding.rule())) {
            return convert(finding, "discrepancies", shown,
                    finding.details().containsKey("discrepancyShapes") ? Basis.SHOWN : Basis.WALK);
        }
        if (DescriptionCorruptionValidator.RULE.equals(finding.rule())) {
            return convert(finding, "outliers", shown, Basis.DOCUMENT);
        }
        return finding;
    }

    /** Which list an old {@code rowIndex} counts. */
    private enum Basis { SHOWN, DOCUMENT, WALK }

    private static VerificationFinding convert(VerificationFinding finding, String key, List<StagedRow> shown,
                                               Basis basis) {
        if (!(finding.details().get(key) instanceof List<?> stored) || stored.isEmpty()) return finding;
        int rowCount = shown == null ? 0 : shown.size();

        // The document's sequence as positions in the list shown; only needed when the old number
        // does not already count the list shown.
        int[] documentOrder = basis == Basis.SHOWN ? null : documentOrder(shown);
        if (basis != Basis.SHOWN && documentOrder == null) return finding;
        boolean reversed = basis == Basis.WALK && walkRanBackwards(shown, documentOrder);

        List<Map<String, Object>> converted = new ArrayList<>(stored.size());
        for (Object element : stored) {
            if (!(element instanceof Map<?, ?> entry)) return finding;
            if (entry.containsKey(DISPLAY_INDEX)) return finding;
            if (!(entry.get(ROW_INDEX) instanceof Integer old) || old < 0 || old >= rowCount) return finding;

            int displayIndex = switch (basis) {
                case SHOWN -> old;
                case DOCUMENT -> documentOrder[old];
                case WALK -> documentOrder[reversed ? rowCount - 1 - old : old];
            };

            Map<String, Object> copy = new LinkedHashMap<>();
            entry.forEach((k, v) -> copy.put(String.valueOf(k), v));
            copy.put(DISPLAY_INDEX, displayIndex);
            converted.add(copy);
        }
        // Down the list the reader is looking at, the order a report staged today lists them in.
        converted.sort(Comparator.comparingInt(m -> (Integer) m.get(DISPLAY_INDEX)));

        Map<String, Object> details = new LinkedHashMap<>();
        finding.details().forEach((k, v) -> details.put(k, key.equals(k) ? converted : v));
        return new VerificationFinding(finding.rule(), finding.outcome(), details);
    }

    /**
     * For each place in the document's sequence, the position of that row in the list shown; null
     * when the rows do not carry the positions that sequence is rebuilt from.
     */
    private static int[] documentOrder(List<StagedRow> shown) {
        if (shown == null || shown.isEmpty()) return null;
        Set<Integer> seen = new HashSet<>();
        for (StagedRow row : shown) {
            if (row == null || row.rowPosition() == null || !seen.add(row.rowPosition())) return null;
        }
        Integer[] byPosition = new Integer[shown.size()];
        for (int i = 0; i < byPosition.length; i++) byPosition[i] = i;
        java.util.Arrays.sort(byPosition, Comparator.comparing(i -> shown.get(i).rowPosition()));
        int[] order = new int[byPosition.length];
        for (int i = 0; i < order.length; i++) order[i] = byPosition[i];
        return order;
    }

    /** The same test the balance check makes before it walks: the first row dated after the last. */
    private static boolean walkRanBackwards(List<StagedRow> shown, int[] documentOrder) {
        LocalDate first = shown.get(documentOrder[0]).date();
        LocalDate last = shown.get(documentOrder[documentOrder.length - 1]).date();
        return first != null && last != null && first.isAfter(last);
    }
}
