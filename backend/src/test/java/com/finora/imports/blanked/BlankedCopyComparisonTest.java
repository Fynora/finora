package com.finora.imports.blanked;

import com.finora.dto.ImportDto.*;
import com.finora.imports.RowKind;
import com.finora.imports.blanked.BlankedCopyComparison.Field;
import com.finora.imports.pdf.trace.PdfTraceRedactor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BlankedCopyComparisonTest {

    // ---- Builders: only date, description, amount, type, balanceAfter, suggestedCategory, the
    // account's name, number, period and opening/closing balance, and the findings are set. Every
    // other component is null, zero or empty.

    private static StagedRow row(String date, String description, String amount, String type, String balanceAfter) {
        return rowWithCategory(date, description, amount, type, balanceAfter, null, null, false);
    }

    private static StagedRow rowWithCategory(String date, String description, String amount, String type,
                                             String balanceAfter, String category, String merchant,
                                             boolean likelyDuplicate) {
        return new StagedRow(date == null ? null : LocalDate.parse(date), description,
                amount == null ? null : new BigDecimal(amount), type, category, null, (UUID) null,
                likelyDuplicate, null, balanceAfter == null ? null : new BigDecimal(balanceAfter),
                null, RowKind.TRANSACTION, null, merchant, null, null, null);
    }

    private static DetectedAccountInfo account(String name, String number, String start, String end,
                                               String opening, String closing) {
        return new DetectedAccountInfo(null, null,
                opening == null ? null : new BigDecimal(opening),
                closing == null ? null : new BigDecimal(closing),
                start == null ? null : LocalDate.parse(start),
                end == null ? null : LocalDate.parse(end),
                number, null, null, null, name, null, null, null,
                null, 0.0, false, List.of(), null,
                null, null, null, null, null, null, null);
    }

    private static StagedAccountSection assemble(DetectedAccountInfo account, List<StagedRow> rows,
                                                 VerificationReport verification) {
        return new StagedAccountSection(account, rows, rows.size(), 0, List.of(), verification);
    }

    private static StagedAccountSection section(List<StagedRow> rows) {
        return assemble(account(null, null, null, null, null, null), rows, null);
    }

    private static StagedAccountSection sectionWith(String start, String end, String opening, String closing,
                                                    String rule, String outcome) {
        VerificationReport report = new VerificationReport(List.of(
                new VerificationFinding(rule, outcome, Map.of())));
        return assemble(account(null, null, start, end, opening, closing), List.of(), report);
    }

    private static StagedAccountSection sectionNamed(String name, String number, String category) {
        // The category, merchant and duplicate flag differ between the two readings as well, to
        // prove none of them is compared.
        boolean duplicate = !"Uncategorized".equals(category);
        String merchant = duplicate ? "Some Merchant" : null;
        return assemble(account(name, number, null, null, null, null),
                List.of(rowWithCategory("2026-07-01", "UPI Xxxx Xxxxx", "100.00", "DEBIT", "900.00",
                        category, merchant, duplicate)), null);
    }

    @Test
    void identicalReadingsMatch() {
        // Guard: the copy-side string is exactly what the redactor writes for the real one.
        assertThat(PdfTraceRedactor.redactLine("UPI Asha Verma")).isEqualTo("UPI Xxxx Xxxxx");
        var real = List.of(section(List.of(row("2026-07-01", "UPI Asha Verma", "100.00", "DEBIT", "900.00"))));
        var copy = List.of(section(List.of(row("2026-07-01", "UPI Xxxx Xxxxx", "100.00", "DEBIT", "900.00"))));
        assertThat(BlankedCopyComparison.compare(real, copy).matches()).isTrue();
    }

    @Test
    void eachRowFieldIsReported() {
        var real = List.of(section(List.of(row("2026-07-01", "UPI Asha", "100.00", "DEBIT", "900.00"))));
        var copy = List.of(section(List.of(row("2026-07-02", "UPI Xxxx Xxxx", "101.00", "CREDIT", "901.00"))));
        assertThat(BlankedCopyComparison.compare(real, copy).differences())
                .extracting(BlankedCopyComparison.Difference::field)
                .containsExactlyInAnyOrder(Field.DATE, Field.DESCRIPTION, Field.AMOUNT, Field.DIRECTION, Field.BALANCE);
    }

    @Test
    void rowAndSectionCountsAreReportedWithoutComparingRowsPastTheShorterList() {
        var real = List.of(section(List.of(row("2026-07-01", "A", "1.00", "DEBIT", "9.00"),
                row("2026-07-02", "B", "1.00", "DEBIT", "8.00"))));
        var copy = List.of(section(List.of(row("2026-07-01", "X", "1.00", "DEBIT", "9.00"))), section(List.of()));
        var differences = BlankedCopyComparison.compare(real, copy).differences();
        assertThat(differences).extracting(BlankedCopyComparison.Difference::field)
                .contains(Field.SECTION_COUNT);
        assertThat(differences).anySatisfy(d -> {
            assertThat(d.field()).isEqualTo(Field.ROW_COUNT);
            assertThat(d.section()).isZero();
            assertThat(d.row()).isNull();
        });
        // Row 1 exists only in the real reading; it must not be compared against anything.
        assertThat(differences).noneMatch(d -> d.row() != null && d.row() == 1);
    }

    @Test
    void periodBalancesAndFindingsAreReported() {
        var real = List.of(sectionWith("2026-07-01", "2026-07-31", "1000.00", "900.00", "BALANCE_CHAIN", "VERIFIED"));
        var copy = List.of(sectionWith("2026-07-02", "2026-07-30", "1001.00", "901.00", "BALANCE_CHAIN", "WARNING"));
        assertThat(BlankedCopyComparison.compare(real, copy).differences())
                .extracting(BlankedCopyComparison.Difference::field)
                .containsExactlyInAnyOrder(Field.PERIOD_START, Field.PERIOD_END, Field.OPENING_BALANCE,
                        Field.CLOSING_BALANCE, Field.FINDINGS);
    }

    /** Account details found on one side only: named as such, not as a start-date difference. */
    @Test
    void accountDetailsFoundOnOneSideOnlyAreReportedAsTheirOwnField() {
        var withAccount = assemble(account(null, null, "2026-07-01", "2026-07-31", null, null), List.of(), null);
        var withoutAccount = assemble(null, List.of(), null);
        for (var pair : List.of(List.of(withAccount, withoutAccount), List.of(withoutAccount, withAccount))) {
            assertThat(BlankedCopyComparison.compare(List.of(pair.get(0)), List.of(pair.get(1))).differences())
                    .singleElement()
                    .satisfies(d -> {
                        assertThat(d.field()).isEqualTo(Field.DETECTED_ACCOUNT);
                        assertThat(d.section()).isZero();
                        assertThat(d.row()).isNull();
                        assertThat(d.maskedTokens()).isEmpty();
                    });
        }
        // Neither side found account details: nothing to report.
        assertThat(BlankedCopyComparison.compare(List.of(withoutAccount), List.of(withoutAccount)).matches()).isTrue();
    }

    @Test
    void aDifferentNumberOfUnreadableRowsIsReported() {
        var real = new StagedAccountSection(account(null, null, null, null, null, null), List.of(), 0, 0,
                List.of(new UnparseableRow(Map.of("Date", "01/07/2026"), "no amount")), null);
        var copy = new StagedAccountSection(account(null, null, null, null, null, null), List.of(), 0, 0,
                List.of(), null);
        assertThat(BlankedCopyComparison.compare(List.of(real), List.of(copy)).differences())
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.field()).isEqualTo(Field.UNPARSEABLE_COUNT);
                    assertThat(d.section()).isZero();
                    assertThat(d.row()).isNull();
                    assertThat(d.maskedTokens()).isEmpty();
                });
        // The same count on both sides is not a difference, whatever the rows hold.
        var copyWithOne = new StagedAccountSection(account(null, null, null, null, null, null), List.of(), 0, 0,
                List.of(new UnparseableRow(Map.of("Date", "99/99/9999"), "no amount")), null);
        assertThat(BlankedCopyComparison.compare(List.of(real), List.of(copyWithOne)).matches()).isTrue();
        // A null list counts as none.
        var copyWithNull = new StagedAccountSection(account(null, null, null, null, null, null), List.of(), 0, 0,
                null, null);
        assertThat(BlankedCopyComparison.compare(List.of(copy), List.of(copyWithNull)).matches()).isTrue();
    }

    @Test
    void accountIdentityCategoryAndDuplicatesAreNotCompared() {
        var real = List.of(sectionNamed("Asha Verma", "XXXX1234", "Groceries"));
        var copy = List.of(sectionNamed("Xxxx Xxxxx", "XXXX9999", "Uncategorized"));
        assertThat(BlankedCopyComparison.compare(real, copy).matches()).isTrue();
    }

    @Test
    void differencesCarryNoRealValues() {
        // The real reading took a phone number as an amount; the copy masked it.
        var real = List.of(section(List.of(row("2026-07-01", "CALL 9876543210", "9876543210.00", "DEBIT", "1.00")))); // synthetic-ok
        var copy = List.of(section(List.of(row("2026-07-01", "XXXX 9999999999", null, "DEBIT", "1.00"))));
        var result = BlankedCopyComparison.compare(real, copy);
        assertThat(result.matches()).isFalse();
        assertThat(result.toString()).doesNotContain("9876543210", "CALL"); // synthetic-ok
        // The descriptions agree character by character (the copy's is the real one masked), so the
        // one difference is the amount the real reading took from the phone number: a field and a
        // position, with no tokens at all.
        assertThat(result.differences()).containsExactly(
                new BlankedCopyComparison.Difference(0, 0, Field.AMOUNT, List.of()));
    }

    // ---- Descriptions. The copy masks each run on its own; the real description is one joined
    // line. A date or a digit run that only forms across a line break masks differently in the
    // two, which is not a difference in what was read.

    private static Field descriptionOutcome(String realDescription, String copyDescription) {
        var result = BlankedCopyComparison.compare(
                List.of(section(List.of(row("2026-07-01", realDescription, "5.00", "DEBIT", "1.00")))),
                List.of(section(List.of(row("2026-07-01", copyDescription, "5.00", "DEBIT", "1.00")))));
        return result.differences().stream().map(BlankedCopyComparison.Difference::field)
                .filter(f -> f == Field.DESCRIPTION).findFirst().orElse(null);
    }

    /** Joined, "05 Jul" is a date and kept; masked run by run, its halves are not. */
    @Test
    void aDateThatFormsOnlyAcrossALineBreakMatches() {
        String copy = PdfTraceRedactor.redactLine("Asha 05") + " " + PdfTraceRedactor.redactLine("Jul");
        // Guard: the joined line really does mask differently, so this is the artifact.
        assertThat(PdfTraceRedactor.redactLine("Asha 05 Jul")).isNotEqualTo(copy);
        assertThat(descriptionOutcome("Asha 05 Jul", copy)).isNull();
    }

    /** Joined, two amounts printed with no space between them form an 8-digit run and the whole
     *  token is masked; run by run, each amount is kept, so the copy holds the real text. */
    @Test
    void aLongDigitRunThatFormsOnlyAcrossRunsMatches() {
        String copy = PdfTraceRedactor.redactLine("12,345.00") + PdfTraceRedactor.redactLine("123456.78");
        assertThat(copy).isEqualTo("12,345.00123456.78");
        assertThat(PdfTraceRedactor.redactLine("12,345.00123456.78")).isNotEqualTo(copy);
        assertThat(descriptionOutcome("12,345.00123456.78", copy)).isNull();
    }

    /** The redactor masks an IFSC's branch code to X whatever its characters; the copy's run does
     *  the same, so a description holding one still matches. */
    @Test
    void aDescriptionHoldingAnIfscMatches() {
        String real = "NEFT HDFC0001234 Asha"; // synthetic-ok: invented branch code
        assertThat(descriptionOutcome(real, PdfTraceRedactor.redactLine(real))).isNull();
    }

    /** The redactor keeps the punctuation at an IFSC's edges, so the copy's run holds it, and a
     *  copy that lost it is not what the redactor writes: no special case for an IFSC is needed. */
    @Test
    void anIfscNextToPunctuationMatches() {
        for (String real : List.of("NEFT HDFC0001234, Asha", "NEFT HDFC0001234. Asha")) { // synthetic-ok: invented branch code
            assertThat(descriptionOutcome(real, PdfTraceRedactor.redactLine(real))).as(real).isNull();
            assertThat(descriptionOutcome(real, "NEFT HDFC0XXXXXX Xxxx")).as(real).isEqualTo(Field.DESCRIPTION);
        }
    }

    /** A copy token that is neither the joined line's masking, nor the real text where that masked
     *  it, nor a full mask of a token holding a digit or naming a month, is a difference: no mixing
     *  of kept and masked characters within a token, and no masking of a word the joined line keeps. */
    @Test
    void aTokenTheRedactorWouldNotHaveWrittenDiffers() {
        // A kept word masked in the copy ("UPI", "PAID", "on" are kept words).
        assertThat(descriptionOutcome("UPI Asha", "XXX Xxxx")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("PAID 1,234.00", "XXXX 1,999.00")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("on 05/07/2026", "xx 99/99/9999")).isEqualTo(Field.DESCRIPTION);
        // A word the joined line masked, held in the copy as a different word.
        assertThat(descriptionOutcome("TAB 5", "TAX 5")).isEqualTo(Field.DESCRIPTION);
        // A token partly kept and partly masked.
        assertThat(descriptionOutcome("PAID 1,234.00", "PAID 1,999.00")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("Asha Verma", "Xxxx Vxxxx")).isEqualTo(Field.DESCRIPTION);
    }

    /** Rule (c) on its own: a date's tokens fully masked in the copy match, and so does a month name. */
    @Test
    void aFullyMaskedDateOrMonthTokenMatches() {
        assertThat(descriptionOutcome("on 05/07/2026", "on 99/99/9999")).isNull();
        assertThat(descriptionOutcome("Asha 05 Jul", "Xxxx 99 Xxx")).isNull();
        assertThat(descriptionOutcome("Asha 5 July", "Xxxx 9 Xxxx")).isNull();
        // A word that is not a month and holds no digit is not covered.
        assertThat(descriptionOutcome("Asha Juli", "Xxxx Xxxx")).isNull();   // the joined line masks it anyway
        assertThat(descriptionOutcome("UPI Juli", "XXX Xxxx")).isEqualTo(Field.DESCRIPTION);
    }

    /**
     * Rule (d). A digit run split across two runs ("UPI/100000" then "000001/ASHA", glued) reaches 8
     * digits only when joined, so the joined line masks the whole compound, the kept "UPI"
     * included; the copy masked each run and kept "UPI". The copy token is the real token masked
     * piece by piece between '/' and '-', which is still the redactor's own masking of the real
     * text, so it matches.
     */
    @Test
    void aCompoundMaskedPieceByPieceMatches() {
        String copy = PdfTraceRedactor.redactLine("UPI/100000") + PdfTraceRedactor.redactLine("000001/ASHA");
        assertThat(copy).isEqualTo("UPI/999999999999/XXXX");
        assertThat(PdfTraceRedactor.redactLine("UPI/100000000001/ASHA")).isEqualTo("XXX/999999999999/XXXX");
        assertThat(descriptionOutcome("UPI/100000000001/ASHA", copy)).isNull();
        assertThat(descriptionOutcome("NEFT-12345678-ASHA", "NEFT-99999999-XXXX")).isNull();
        // With no 8-digit run when joined, the joined line already masks piece by piece.
        assertThat(descriptionOutcome("UPI/123456/789012/ASHA", "UPI/999999/999999/XXXX")).isNull();
    }

    /**
     * A long number split across runs into short pieces glued to a slash is no longer kept piece by
     * piece: an integer glued to '/' is an identifier's digits and is masked, so the copy holds the
     * redactor's own piece-by-piece masking of the real token and matches. (Up to the previous
     * round each short piece was kept, which both showed the number and read as a difference.)
     */
    @Test
    void aLongNumberSplitIntoShortPiecesIsMaskedAndMatches() {
        String copy = PdfTraceRedactor.redactLine("UPI/1234") + PdfTraceRedactor.redactLine("5678/ASHA");
        assertThat(copy).isEqualTo("UPI/99999999/XXXX");
        assertThat(descriptionOutcome("UPI/12345678/ASHA", copy)).isNull();
    }

    /** Rule (d) is one exact masking, not a tolerance: any other shape or a kept name differs. */
    @Test
    void aCompoundThatIsNotThePieceByPieceMaskingDiffers() {
        assertThat(descriptionOutcome("UPI/12345678/ASHA", "UPI/99999999/XXXXX")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("UPI/12345678/ASHA", "UPI/99999999/Xxxx")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("UPI/12345678/ASHA", "UPI/99999999/ASHA")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("UPI/12345678/ASHA", "UPI-99999999/XXXX")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("UPI/12345678/ASHA", "XXX/99999999")).isEqualTo(Field.DESCRIPTION);
    }

    /** The real text in clear counts only for a token of digits and punctuation: a compound
     *  holding letters, held verbatim in the copy, is not the redactor's masking of it. */
    @Test
    void aCompoundWithLettersHeldInClearDiffers() {
        assertThat(PdfTraceRedactor.redactLine("UPI/12345678/ASHA")).isNotEqualTo("UPI/12345678/ASHA");
        assertThat(descriptionOutcome("UPI/12345678/ASHA", "UPI/12345678/ASHA")).isEqualTo(Field.DESCRIPTION);
    }

    /**
     * KNOWN LIMIT, pinned so it cannot change unnoticed: a token of digits and punctuation only that
     * the copy masked whole matches whatever same-shape token the real description has there. Here
     * the copy hides an amount the real description shows; the check cannot tell whether the copy
     * read the same amount or a different one of the same shape.
     */
    @Test
    void knownLimitAFullyMaskedDigitTokenMatchesAnySameShapeRealToken() {
        assertThat(descriptionOutcome("PAID 1,234.00", "PAID 9,999.99")).isNull();
        assertThat(descriptionOutcome("PAID 5,678.00", "PAID 9,999.99")).isNull();
    }

    /** The whole-token mask applies only to a token of digits and punctuation, or a month name: a
     *  token that also holds letters, glued by punctuation to its digits, is not masked whole. */
    @Test
    void aTokenMixingLettersAndDigitsIsNotMaskedWhole() {
        assertThat(PdfTraceRedactor.redactLine("UPI/1234/PAID")).isNotEqualTo("XXX/9999/XXXX");
        assertThat(PdfTraceRedactor.redactLine("PAID-1,234.00")).isNotEqualTo("XXXX-9,999.99");
        assertThat(descriptionOutcome("UPI/1234/PAID", "XXX/9999/XXXX")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("PAID-1,234.00", "XXXX-9,999.99")).isEqualTo(Field.DESCRIPTION);
    }

    /** The real text kept verbatim in the copy counts only for a token holding a digit (the
     *  measured artifact is digit runs). A word the joined line masked, held in clear in the copy,
     *  is not the same description. */
    @Test
    void aMaskedWordHeldInClearInTheCopyDiffers() {
        assertThat(PdfTraceRedactor.redactLine("UPI Asha")).isEqualTo("UPI Xxxx");
        assertThat(descriptionOutcome("UPI Asha", "UPI Asha")).isEqualTo(Field.DESCRIPTION);
    }

    /**
     * KNOWN LIMIT in the other direction: a token glued from a run the copy kept and a run it
     * masked (here an amount and a reference printed with no space between) is partly kept, which
     * no rule allows, so the row is reported as a difference although it was read the same way.
     */
    @Test
    void knownLimitATokenGluedFromAKeptAndAMaskedRunDiffers() {
        String copy = PdfTraceRedactor.redactLine("12345.00") + PdfTraceRedactor.redactLine("123456");
        assertThat(copy).isEqualTo("12345.00999999");
        assertThat(descriptionOutcome("12345.00123456", copy)).isEqualTo(Field.DESCRIPTION);
    }

    @Test
    void aDifferentGroupingOfLinesStillDiffers() {
        // An extra line in the copy, and a line missing from it.
        assertThat(descriptionOutcome("UPI Asha", "UPI Xxxx Xxxxx")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("UPI Asha Verma", "UPI Xxxx")).isEqualTo(Field.DESCRIPTION);
        // The same words in another order.
        assertThat(descriptionOutcome("UPI Asha Verma", "Xxxxx UPI Xxxx")).isEqualTo(Field.DESCRIPTION);
        // Words of different lengths in the same total length.
        assertThat(descriptionOutcome("Asha Verma", "Xxxxx Xxxx")).isEqualTo(Field.DESCRIPTION);
        // A description on one side only.
        assertThat(descriptionOutcome("UPI Asha", null)).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome(null, "UPI Xxxx")).isEqualTo(Field.DESCRIPTION);
    }

    @Test
    void aCopyCharacterThatIsNotTheMaskOfTheRealOneDiffers() {
        // A letter where the real description has a digit, and a digit where it has a letter.
        assertThat(descriptionOutcome("REF 1234", "REF xxxx")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("UPI Asha", "UPI 9999")).isEqualTo(Field.DESCRIPTION);
        // Upper-case X for a lower-case letter, which the redactor writes as x.
        assertThat(descriptionOutcome("UPI Asha", "UPI XXXX")).isEqualTo(Field.DESCRIPTION);
        // Punctuation and spacing must be equal.
        assertThat(descriptionOutcome("UPI-Asha", "UPI/Xxxx")).isEqualTo(Field.DESCRIPTION);
        assertThat(descriptionOutcome("UPI Asha", "UPI  Xxx")).isEqualTo(Field.DESCRIPTION);
        // Both null, or the same text, is no difference.
        assertThat(descriptionOutcome(null, null)).isNull();
        assertThat(descriptionOutcome("UPI", "UPI")).isNull();
    }

    @Test
    void maskedTokensAreExactlyTheCopysOwnMaskedTokensWhenDescriptionsDiffer() {
        // The copy grouped an extra line into the row, so the descriptions really differ.
        var real = List.of(section(List.of(row("2026-07-01", "CALL 9876543210", "5.00", "DEBIT", "1.00")))); // synthetic-ok
        var copy = List.of(section(List.of(row("2026-07-01", "XXXX 9999999999 PAYMENT Xxxx", "5.00", "DEBIT", "1.00"))));
        var result = BlankedCopyComparison.compare(real, copy);

        var description = result.differences().stream()
                .filter(d -> d.field() == Field.DESCRIPTION).toList();
        assertThat(description).hasSize(1);
        assertThat(description.get(0).section()).isZero();
        assertThat(description.get(0).row()).isZero();
        // Exactly the copy's masked tokens: the unmasked word "PAYMENT" is left out.
        assertThat(description.get(0).maskedTokens()).containsExactly("XXXX", "9999999999", "Xxxx");
        // No token of the real description, and nothing else, leaves the comparison.
        assertThat(description.get(0).maskedTokens()).doesNotContain("CALL", "9876543210"); // synthetic-ok
        assertThat(result.toString()).doesNotContain("9876543210", "CALL"); // synthetic-ok
    }
}
