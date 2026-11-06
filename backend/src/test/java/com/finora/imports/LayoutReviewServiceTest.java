package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.dto.ImportDto.VerificationReport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LayoutReviewServiceTest {

    private static StagedRow row(String description) {
        return new StagedRow(LocalDate.of(2026, 1, 15), description, new BigDecimal("1.00"), "EXPENSE",
                "Other", "default", null, false, null, null);
    }

    @Test
    void aDatabaseFailureNeverReachesTheStagingCaller_andSendsNoAlert() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenThrow(new TransactionException("database unreachable") {});
        LayoutReviewAlertService alerts = mock(LayoutReviewAlertService.class);
        LayoutReviewService service = new LayoutReviewService(mock(JdbcTemplate.class), transactions, alerts, mock(LayoutProfileAutoLinker.class));

        assertThatCode(() -> service.onStaged("FP-1-ABCDEF12", "PDF", List.of(row("")), List.of(), "SA-1", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.onStagingFailed("FP-1-ABCDEF12", "PDF", "SA-2", new IllegalStateException("parser broke"))).doesNotThrowAnyException();
        verifyNoInteractions(alerts);
    }

    @Test
    void blankDescriptionThreshold_isStrictlyMoreThanHalf() {
        assertThat(LayoutReviewService.mostlyBlankDescriptions(List.of())).isFalse();
        assertThat(LayoutReviewService.mostlyBlankDescriptions(null)).isFalse();
        assertThat(LayoutReviewService.mostlyBlankDescriptions(List.of(row("A"), row("")))).isFalse();
        assertThat(LayoutReviewService.mostlyBlankDescriptions(List.of(row("A"), row(""), row("  ")))).isTrue();
        assertThat(LayoutReviewService.mostlyBlankDescriptions(List.of(row(null)))).isTrue();
    }

    @Test
    void onlyWarningOrFailedCountsAsNotPassed_perRule_andNullReportsAreSkipped() {
        VerificationReport verified = new VerificationReport(
                List.of(new VerificationFinding("R", "VERIFIED", Map.of()),
                        new VerificationFinding("S", "NOT_APPLICABLE", Map.of())), false, null, null);
        VerificationReport warning = new VerificationReport(
                List.of(new VerificationFinding("TOTALS", "WARNING", Map.of()),
                        new VerificationFinding("CHAIN", "FAILED", Map.of())), false, null, null);

        assertThat(LayoutReviewService.rulesNotPassed(null)).isEmpty();
        assertThat(LayoutReviewService.rulesNotPassed(Arrays.asList(null, verified))).isEmpty();
        assertThat(LayoutReviewService.rulesNotPassed(Arrays.asList(verified, null, warning)))
                .containsExactly("CHAIN", "TOTALS");
    }

    /** Sid's decision (2026-10-02): when a card statement's rows agree with its printed purchases
     *  and payments, the summary disagreeing with itself is not something to review -- the import
     *  went through, and the alert called it "needs review" anyway. */
    @Test
    void aCardSummaryWarning_isNotAReviewReason_whenTheRowsAgreeWithTheSummary() {
        VerificationReport rowsAgree = new VerificationReport(
                List.of(new VerificationFinding("CREDIT_CARD_STATEMENT_TOTALS", "WARNING", Map.of()),
                        new VerificationFinding("CREDIT_CARD_FLOW_RECONCILIATION", "VERIFIED", Map.of())),
                false, null, null);

        assertThat(LayoutReviewService.rulesNotPassed(List.of(rowsAgree))).isEmpty();
    }

    @Test
    void aCardSummaryWarning_staysAReviewReason_whenTheRowsDisagreeOrCannotBeChecked() {
        for (String flow : List.of("WARNING", "NOT_APPLICABLE")) {
            VerificationReport report = new VerificationReport(
                    List.of(new VerificationFinding("CREDIT_CARD_STATEMENT_TOTALS", "WARNING", Map.of()),
                            new VerificationFinding("CREDIT_CARD_FLOW_RECONCILIATION", flow, Map.of())),
                    false, null, null);

            assertThat(LayoutReviewService.rulesNotPassed(List.of(report))).as(flow)
                    .contains("CREDIT_CARD_STATEMENT_TOTALS");
        }
        VerificationReport noFlowCheck = new VerificationReport(
                List.of(new VerificationFinding("CREDIT_CARD_STATEMENT_TOTALS", "WARNING", Map.of())),
                false, null, null);
        assertThat(LayoutReviewService.rulesNotPassed(List.of(noFlowCheck))).containsExactly("CREDIT_CARD_STATEMENT_TOTALS");
    }

    /** Per section: a composite statement's other section agreeing says nothing about this one. */
    @Test
    void rowsAgreeingInOneSection_doNotExcuseASummaryWarningInAnother() {
        VerificationReport warnsWithoutFlow = new VerificationReport(
                List.of(new VerificationFinding("CREDIT_CARD_STATEMENT_TOTALS", "WARNING", Map.of())),
                false, null, null);
        VerificationReport agrees = new VerificationReport(
                List.of(new VerificationFinding("CREDIT_CARD_FLOW_RECONCILIATION", "VERIFIED", Map.of())),
                false, null, null);

        assertThat(LayoutReviewService.rulesNotPassed(List.of(warnsWithoutFlow, agrees)))
                .containsExactly("CREDIT_CARD_STATEMENT_TOTALS");
    }

    @Test
    void theFingerprintOfADocumentWithNoHeadersIsRecognised_forEitherFormat() {
        String pdfHeaderless = new DocumentContext("PDF", "any").buildFingerprint();
        String csvHeaderless = new DocumentContext("CSV", "any").buildFingerprint();
        DocumentContext withHeaders = new DocumentContext("PDF", "any");
        withHeaders.recordHeaders(List.of("Date", "Description", "Amount"));

        assertThat(LayoutReviewService.isHeaderlessFingerprint(pdfHeaderless, "PDF")).isTrue();
        assertThat(LayoutReviewService.isHeaderlessFingerprint(csvHeaderless, "CSV")).isTrue();
        assertThat(LayoutReviewService.isHeaderlessFingerprint(withHeaders.buildFingerprint(), "PDF")).isFalse();
    }

    @Test
    void aHeaderlessDocumentNeverTouchesTheRegistry() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        LayoutReviewAlertService alerts = mock(LayoutReviewAlertService.class);
        LayoutReviewService service = new LayoutReviewService(mock(JdbcTemplate.class), transactions, alerts, mock(LayoutProfileAutoLinker.class));
        String headerless = new DocumentContext("PDF", "any").buildFingerprint();

        service.onStagingFailed(headerless, "PDF", "SA-3", new IllegalStateException("parser broke"));
        service.onStaged(headerless, "PDF", List.of(row("")), List.of(), "SA-4", null);

        verifyNoInteractions(transactions, alerts);
    }

    @Test
    void onlyAParserSideFailureCountsAsStagingFailed() {
        assertThat(LayoutReviewService.isParserSideFailure(new IllegalStateException("index out of bounds"))).isTrue();
        assertThat(LayoutReviewService.isParserSideFailure(
                new com.finora.exception.ApiException(com.finora.exception.ErrorCode.IMPORT_NO_HEADER_DETECTED))).isTrue();
        assertThat(LayoutReviewService.isParserSideFailure(
                new com.finora.exception.ApiException(com.finora.exception.ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND))).isTrue();
        assertThat(LayoutReviewService.isParserSideFailure(
                new com.finora.exception.ApiException(com.finora.exception.ErrorCode.IMPORT_NO_ACTIVITY_IN_PERIOD))).isFalse();
        assertThat(LayoutReviewService.isParserSideFailure(
                new com.finora.exception.ApiException(com.finora.exception.ErrorCode.IMPORT_PAYMENT_APP_HISTORY))).isFalse();
        assertThat(LayoutReviewService.isParserSideFailure(
                new com.finora.exception.ApiException(com.finora.exception.ErrorCode.IMPORT_PDF_PASSWORD_INVALID))).isFalse();
        assertThat(LayoutReviewService.isParserSideFailure(null)).isFalse();
    }

    private static VerificationFinding chain(String outcome, int rowsChecked, int disagreeing, Boolean gapsCancel) {
        java.util.List<com.finora.dto.ImportDto.RowDiscrepancy> rows = new java.util.ArrayList<>();
        for (int i = 0; i < disagreeing; i++) {
            rows.add(new com.finora.dto.ImportDto.RowDiscrepancy(257 + i, 257 + i,
                    new BigDecimal("91234.56"), new BigDecimal("87654.32"), new BigDecimal("3580.24")));
        }
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("rowsChecked", rowsChecked);
        details.put("discrepancies", rows);
        if (gapsCancel != null) details.put("gapsCancel", gapsCancel);
        return new VerificationFinding("BALANCE_CHAIN", outcome, details);
    }

    @Test
    void findingSummaries_saysHowManyRowsDisagreedAndWhetherTheGapsCancel_withoutAnyCustomerNumber() {
        List<String> lines = LayoutReviewService.findingSummaries(
                List.of(new VerificationReport(List.of(chain("WARNING", 1691, 3, true)))));

        assertThat(lines).containsExactly(
                "BALANCE_CHAIN — WARNING: 3 of 1691 rows disagree with the printed balance; the gaps cancel out");
        assertThat(String.join(" ", lines)).doesNotContain("91234").doesNotContain("87654").doesNotContain("3580");
    }

    @Test
    void findingSummaries_omitsTheCancelClauseWhenTheyDoNot_andNamesTheSectionOnlyWhenThereAreSeveral() {
        assertThat(LayoutReviewService.findingSummaries(
                List.of(new VerificationReport(List.of(chain("FAILED", 40, 30, false))))))
                .containsExactly("BALANCE_CHAIN — FAILED: 30 of 40 rows disagree with the printed balance");

        VerificationReport clean = new VerificationReport(List.of(new VerificationFinding("BALANCE_CHAIN", "VERIFIED", Map.of())));
        VerificationReport bad = new VerificationReport(List.of(chain("WARNING", 10, 1, null)));
        assertThat(LayoutReviewService.findingSummaries(List.of(clean, bad)))
                .containsExactly("BALANCE_CHAIN (section 1) — WARNING: 1 of 10 rows disagree with the printed balance");
    }

    @Test
    void findingSummaries_handlesRulesWithNoRowDetail_nullsAndEmptyInput() {
        assertThat(LayoutReviewService.findingSummaries(null)).isEmpty();
        assertThat(LayoutReviewService.findingSummaries(List.of())).isEmpty();
        assertThat(LayoutReviewService.findingSummaries(Arrays.asList(null, new VerificationReport(null)))).isEmpty();
        assertThat(LayoutReviewService.findingSummaries(List.of(new VerificationReport(List.of(
                new VerificationFinding("STATEMENT_TOTALS", "WARNING", null),
                new VerificationFinding("SUMMARY_TOTALS", "FAILED", Map.of("rowsChecked", 5)),
                new VerificationFinding(null, "WARNING", Map.of()))))))
                .containsExactly("STATEMENT_TOTALS — WARNING", "SUMMARY_TOTALS — FAILED", "UNKNOWN_RULE — WARNING");
    }

    @Test
    void findingSummaries_listsExactlyTheFindingsRulesNotPassedNames() {
        VerificationReport report = new VerificationReport(List.of(
                new VerificationFinding("CREDIT_CARD_FLOW_RECONCILIATION", "VERIFIED", Map.of()),
                new VerificationFinding("CREDIT_CARD_STATEMENT_TOTALS", "WARNING", Map.of()),
                chain("WARNING", 5, 1, null)));

        assertThat(LayoutReviewService.rulesNotPassed(List.of(report))).containsExactly("BALANCE_CHAIN");
        assertThat(LayoutReviewService.findingSummaries(List.of(report))).hasSize(1)
                .allMatch(line -> line.startsWith("BALANCE_CHAIN"));
    }
}
