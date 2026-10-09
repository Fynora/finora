package com.finora.imports;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audit F-08, through the real PDF generator. A payment app's history is refused because of what
 * the document IS, which its own headings state -- not because nothing could be read from it. A
 * longer history than the real one could reach a table reader and stage rows, and those rows span
 * several bank accounts, so it must be refused whether or not any row was staged.
 */
class PaymentAppHistoryDocumentTest {

    @Test
    void aPaymentHistoryIsRefusedEvenWhenItsTableWasRead() throws Exception {
        var generated = generator().generateSectionsWithContext(
                UUID.randomUUID(), "history.pdf", PdfFixtureBuilder.buildPaymentAppHistoryWithAReadableTable(), null);

        assertThat(generated.sections().stream().mapToInt(s -> s.rows().size()).sum())
                .as("the fixture's table is readable, so this is the rows-were-staged case")
                .isPositive();
        assertThat(generated.documentContext().paymentAppHistory()).isTrue();

        ApiException e = catchThrowableOfType(ApiException.class, () ->
                ExtractionCheck.rejectIfNothingWasExtracted(generated.sections(), generated.documentContext()));
        assertThat(e).isNotNull();
        assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_PAYMENT_APP_HISTORY);
    }

    @Test
    void aUpiAppHistoryIsRefusedThroughTheRealGenerator() throws Exception {
        // Through the real text extractor, so the runs the detector sees are the ones a real PDF
        // yields -- the rule depends on where each run begins.
        var generated = generator().generateSectionsWithContext(
                UUID.randomUUID(), "history.pdf", PdfFixtureBuilder.buildUpiAppTransactionHistory(), null);

        assertThat(generated.documentContext().paymentAppHistory()).isTrue();

        ApiException e = catchThrowableOfType(ApiException.class, () ->
                ExtractionCheck.rejectIfNothingWasExtracted(generated.sections(), generated.documentContext()));
        assertThat(e).isNotNull();
        assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_PAYMENT_APP_HISTORY);
        assertThat(e.getMessage()).containsIgnoringCase("payment app history")
                .containsIgnoringCase("not a bank statement")
                .containsIgnoringCase("import those bank statements instead");
    }

    @Test
    void anOrdinaryStatementIsNotFlagged() throws Exception {
        var generated = generator().generateSectionsWithContext(
                UUID.randomUUID(), "statement.pdf", PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample(), null);

        assertThat(generated.documentContext().paymentAppHistory()).isFalse();
        assertThatCode(() -> ExtractionCheck.rejectIfNothingWasExtracted(
                generated.sections(), generated.documentContext())).doesNotThrowAnyException();
    }

    private static PdfPreviewGenerator generator() {
        CategorizationService categorization = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorization.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(suggestion);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        TransactionNormalizer normalizer = new TransactionNormalizer(categorization,
                new DuplicateDetector(transactions, TestAccountRepositories.anyLive()), TestRuleEngines.empty());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                normalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(),
                        new CreditCardStatementTotalsValidator(), new CreditCardFlowReconciliationValidator(),
                        new DescriptionCorruptionValidator()),
                TestRuleEngines.empty());
    }
}
