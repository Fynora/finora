package com.finora.imports.blanked;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.trace.PdfTraceRedactor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Whether a blanked copy reads the same as the real statement it came from.
 *
 * <p>Compares only what reading produces. Not compared: the account holder's name and number
 * (masked in the copy by design), and the category, merchant and duplicate fields (they come from
 * description words and the customer's own data). A difference names a field and a row, never a
 * value: a real value can be text the copy hid, such as a phone number the real reading took for
 * an amount. The only text a difference carries is the copy's own masked tokens, which the copy
 * already contains.
 *
 * <p>A description is compared by masking the real one with the same rules as the copy, so the
 * two match exactly when the same words were grouped into that row in the same order.
 */
public final class BlankedCopyComparison {

    public enum Field { SECTION_COUNT, ROW_COUNT, UNPARSEABLE_COUNT, DATE, AMOUNT, DIRECTION, BALANCE,
        DESCRIPTION, PERIOD_START, PERIOD_END, OPENING_BALANCE, CLOSING_BALANCE, FINDINGS }

    public record Difference(int section, Integer row, Field field, List<String> maskedTokens) {}

    public record Result(List<Difference> differences) {
        public boolean matches() { return differences.isEmpty(); }
    }

    private BlankedCopyComparison() {}

    public static Result compare(List<StagedAccountSection> real, List<StagedAccountSection> copy) {
        List<Difference> out = new ArrayList<>();
        if (real.size() != copy.size()) out.add(new Difference(0, null, Field.SECTION_COUNT, List.of()));
        for (int s = 0; s < Math.min(real.size(), copy.size()); s++) {
            compareSection(s, real.get(s), copy.get(s), out);
        }
        return new Result(List.copyOf(out));
    }

    private static void compareSection(int s, StagedAccountSection real, StagedAccountSection copy, List<Difference> out) {
        DetectedAccountInfo a = real.detectedAccount();
        DetectedAccountInfo b = copy.detectedAccount();
        if (a != null || b != null) {
            if (a == null || b == null) {
                out.add(new Difference(s, null, Field.PERIOD_START, List.of()));
            } else {
                section(s, Field.PERIOD_START, a.statementPeriodStart(), b.statementPeriodStart(), out);
                section(s, Field.PERIOD_END, a.statementPeriodEnd(), b.statementPeriodEnd(), out);
                money(s, null, Field.OPENING_BALANCE, a.openingBalance(), b.openingBalance(), out);
                money(s, null, Field.CLOSING_BALANCE, a.closingBalance(), b.closingBalance(), out);
            }
        }
        if (size(real.unparseableRows()) != size(copy.unparseableRows())) {
            out.add(new Difference(s, null, Field.UNPARSEABLE_COUNT, List.of()));
        }
        if (!findingOutcomes(real).equals(findingOutcomes(copy))) {
            out.add(new Difference(s, null, Field.FINDINGS, List.of()));
        }
        List<StagedRow> ra = real.rows() == null ? List.of() : real.rows();
        List<StagedRow> rb = copy.rows() == null ? List.of() : copy.rows();
        if (ra.size() != rb.size()) out.add(new Difference(s, null, Field.ROW_COUNT, List.of()));
        for (int r = 0; r < Math.min(ra.size(), rb.size()); r++) {
            compareRow(s, r, ra.get(r), rb.get(r), out);
        }
    }

    private static void compareRow(int s, int r, StagedRow a, StagedRow b, List<Difference> out) {
        if (!Objects.equals(a.date(), b.date())) out.add(new Difference(s, r, Field.DATE, List.of()));
        money(s, r, Field.AMOUNT, a.amount(), b.amount(), out);
        if (!Objects.equals(a.type(), b.type())) out.add(new Difference(s, r, Field.DIRECTION, List.of()));
        money(s, r, Field.BALANCE, a.balanceAfter(), b.balanceAfter(), out);
        String maskedReal = a.description() == null ? null : PdfTraceRedactor.redactLine(a.description());
        if (!Objects.equals(maskedReal, b.description())) {
            out.add(new Difference(s, r, Field.DESCRIPTION, maskedTokens(b.description())));
        }
    }

    /** The copy's own tokens that were masked: they hold an X, x or 9 run the redactor wrote. */
    private static List<String> maskedTokens(String copyDescription) {
        if (copyDescription == null) return List.of();
        return Arrays.stream(copyDescription.split("\\s+"))
                .filter(t -> t.chars().anyMatch(Character::isLetterOrDigit))
                .filter(t -> t.chars().allMatch(c -> c == 'X' || c == 'x' || c == '9' || !Character.isLetterOrDigit(c)))
                .toList();
    }

    private static void section(int s, Field field, Object a, Object b, List<Difference> out) {
        if (!Objects.equals(a, b)) out.add(new Difference(s, null, field, List.of()));
    }

    private static void money(int s, Integer r, Field field, BigDecimal a, BigDecimal b, List<Difference> out) {
        boolean same = a == null ? b == null : b != null && a.compareTo(b) == 0;
        if (!same) out.add(new Difference(s, r, field, List.of()));
    }

    private static List<String> findingOutcomes(StagedAccountSection section) {
        if (section.verification() == null || section.verification().findings() == null) return List.of();
        return section.verification().findings().stream()
                .map(f -> f.rule() + "=" + f.outcome()).sorted().toList();
    }

    private static int size(List<?> list) { return list == null ? 0 : list.size(); }
}
