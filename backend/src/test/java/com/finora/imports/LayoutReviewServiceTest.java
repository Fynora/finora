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
        LayoutReviewService service = new LayoutReviewService(mock(JdbcTemplate.class), transactions, alerts);

        assertThatCode(() -> service.onStaged("FP-1-ABCDEF12", "PDF", List.of(row("")), List.of(), "SA-1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.onStagingFailed("FP-1-ABCDEF12", "PDF", "SA-2")).doesNotThrowAnyException();
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
        LayoutReviewService service = new LayoutReviewService(mock(JdbcTemplate.class), transactions, alerts);
        String headerless = new DocumentContext("PDF", "any").buildFingerprint();

        service.onStagingFailed(headerless, "PDF", "SA-3");
        service.onStaged(headerless, "PDF", List.of(row("")), List.of(), "SA-4");

        verifyNoInteractions(transactions, alerts);
    }
}
