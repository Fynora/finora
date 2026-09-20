package com.finora.imports;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a "nothing was extracted" rejection says about whether the document was a statement at all.
 *
 * <p>Production-adjacent finding, 2026-09-20: {@code recoveredLines} counts every line the parser set
 * aside, so it is non-zero for an invoice, an electricity bill, a salary slip, a terms-and-conditions
 * page and a résumé alike -- and the worker held all of them for an admin, telling the user "we're
 * running additional checks" about a document that was never a statement. The signal that actually
 * separates "a statement in a layout we cannot read yet" from "the wrong file" is recovered rows that
 * carry both a date and a money amount, like a transaction does.
 */
class ExtractionCheckEvidenceTest {

    private static UnparseableRow row(String text) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("line", text);
        return new UnparseableRow(raw, "not parseable");
    }

    private static UnparseableRow cells(String... values) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) raw.put("c" + i, values[i]);
        return new UnparseableRow(raw, "not parseable");
    }

    private static ApiException rejectionFor(UnparseableRow... recovered) {
        StagingResponse empty = new StagingResponse(List.of(), 0, 0, null, List.of(recovered), null);
        return catchThrowable(empty);
    }

    private static ApiException catchThrowable(StagingResponse staged) {
        try {
            ExtractionCheck.rejectIfNothingWasExtracted(staged, new DocumentContext("PDF", "test"));
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected a rejection");
    }

    private static boolean looksLikeAStatement(ApiException e) {
        return Boolean.TRUE.equals(e.getDetails().get("looksLikeAStatement"));
    }

    @Test
    void recoveredRowsWithADateAndAnAmountAreEvidenceOfAStatement() {
        ApiException e = rejectionFor(
                row("01/07/2026 NEFT credit 40,000.00 46,098.10"),
                row("06/07/2026 ACH debit 1,000.00 45,098.10"),
                row("07/07/2026 Mandate 1,000.00 44,098.10"));

        assertThat(e.getDetails().get("transactionShapedLines")).isEqualTo(3);
        assertThat(looksLikeAStatement(e)).isTrue();
        assertThat(e.getDetails().get("recoveredLines")).as("the existing count is unchanged").isEqualTo(3);
    }

    @Test
    void anInvoiceABillAndASalarySlipAreNotStatements() {
        ApiException e = rejectionFor(
                row("TAX INVOICE"),
                row("Invoice Date: 12/03/2026"),
                row("Due Date: 26/03/2026"),
                row("Web hosting 1 4,500.00 4,500.00"),
                row("GST 18% 810.00"),
                row("Total Due 5,310.00"),
                row("Paid on 31/07/2026"));

        assertThat(e.getDetails().get("recoveredLines")).isEqualTo(7);
        assertThat(e.getDetails().get("transactionShapedLines")).isEqualTo(0);
        assertThat(looksLikeAStatement(e)).isFalse();
    }

    @Test
    void proseIsNeverEvidence() {
        ApiException e = rejectionFor(row("Terms and Conditions"), row("Effective from 01/04/2026."),
                row("Sanjay Tiwari, Software Engineer, 2015 - 2026"));

        assertThat(looksLikeAStatement(e)).isFalse();
    }

    /** One row is not a table; two is the smallest thing that can be called one. */
    @Test
    void theThresholdIsTwoTransactionShapedRows() {
        assertThat(looksLikeAStatement(rejectionFor(row("01/07/2026 NEFT credit 40,000.00")))).isFalse();
        assertThat(looksLikeAStatement(rejectionFor(
                row("01/07/2026 NEFT credit 40,000.00"), row("06/07/2026 ACH debit 1,000.00")))).isTrue();
    }

    /** A date alone and an amount alone are each ordinary in a non-statement; only together do they mean a transaction. */
    @Test
    void aDateWithoutAnAmountAndAnAmountWithoutADateDoNotCount() {
        ApiException e = rejectionFor(row("Invoice Date: 12/03/2026"), row("Bill Date 05/08/2026"),
                row("Total payable 1,254.00"), row("Energy charges 1,104.00"));

        assertThat(e.getDetails().get("transactionShapedLines")).isEqualTo(0);
    }

    /** "12.03.2026" is a date; its fragments must not be read as the amount "12.03" or "03.20". */
    @Test
    void aDotSeparatedDateIsNotAlsoAnAmount() {
        ApiException e = rejectionFor(row("Date 12.03.2026"), row("Date 13.03.2026"), row("Date 14.03.2026"));

        assertThat(e.getDetails().get("transactionShapedLines")).isEqualTo(0);
    }

    @Test
    void everyDateStyleABankPrintsIsRecognised() {
        for (String date : List.of("01/07/2026", "01-07-2026", "01.07.26", "2026-07-01", "01 Jul 2026", "1-Jul-2026", "01 July 2026")) {
            assertThat(looksLikeAStatement(rejectionFor(
                    row(date + " UPI/Swiggy 486.00 1,200.50"), row(date + " UPI/Blinkit 1,240.50 900.00"))))
                    .as(date).isTrue();
        }
    }

    /**
     * Some statements print no year at all ("01 Jul", "15 Aug") -- HSBC's do, and the corpus has one.
     * A real statement in an unread layout must not be mistaken for the wrong file just because its
     * dates are yearless.
     */
    @Test
    void aYearlessDayAndMonthNameDateStillCounts() {
        ApiException e = rejectionFor(
                row("01 Jul UPI/Swiggy 486.00 1,200.50"), row("15 Aug Salary credit 40,000.00 41,200.50"));

        assertThat(looksLikeAStatement(e)).isTrue();
    }

    /** ...but a bare month name in prose, with an amount elsewhere on the line, is still not a transaction. */
    @Test
    void aMonthNameWithoutADayIsNotADate() {
        ApiException e = rejectionFor(row("Charges for July 1,254.00"), row("Statement for August 2,310.50"));

        assertThat(e.getDetails().get("transactionShapedLines")).isEqualTo(0);
    }

    /** A CSV recovers each cell separately; the row is judged on all of them together. */
    @Test
    void aCsvRowIsJudgedOnAllOfItsCellsTogether() {
        ApiException e = rejectionFor(
                cells("10/07/2026", "SWIGGY ORDER", "486.00"), cells("11/07/2026", "BLINKIT", "1,240.50"));

        assertThat(looksLikeAStatement(e)).isTrue();
    }

    @Test
    void junkCsvRowsAreNotEvidence() {
        ApiException e = rejectionFor(cells("foo", "bar"), cells("1", "2"), cells("3", "4"));

        assertThat(e.getDetails().get("recoveredLines")).isEqualTo(3);
        assertThat(looksLikeAStatement(e)).isFalse();
    }

    @Test
    void aNullOrEmptyRecoveredListIsNotEvidenceAndDoesNotThrowSomethingElse() {
        StagingResponse none = new StagingResponse(List.of(), 0, 0, null, null, null);
        ApiException e = catchThrowable(none);

        assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_NO_HEADER_DETECTED);
        assertThat(e.getDetails().get("transactionShapedLines")).isEqualTo(0);
        assertThat(looksLikeAStatement(e)).isFalse();
    }
}
