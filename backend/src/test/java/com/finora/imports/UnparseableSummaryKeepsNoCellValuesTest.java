package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.acquisition.NativePdfAcquirer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The unparseable-row summary persisted to {@code unparseable_summary_json} holds no cell value, on
 * either parser, while the review screen keeps quoting the cell it could not read.
 *
 * <p>Both halves matter. Until this was fixed the summary was keyed by the review-screen reason
 * verbatim, so a date the engine could not read was stored, as written, in an admin-read table. The
 * fix must not get there by blanding the reason the uploader sees: on their own screen, the cell is
 * the most useful thing the reason can show them.
 */
class UnparseableSummaryKeepsNoCellValuesTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aCsvSummaryKeysUnreadableCellsByShapeNotByValue() throws Exception {
        String csv = String.join("\n",
                "Date,Description,Debit,Credit,Balance",
                "01/07/2026,OPENING DEPOSIT,,5000.00,5000.00",
                "31/13/2026,SAMPLE PAYEE ONE,100.00,,4900.00",
                "03/07/2026,SAMPLE PAYEE TWO,2OO.OO,,",
                "04/07/2026,SAMPLE PAYEE THREE,300.00,,4600.00");

        var result = csvGenerator().generateWithContext(USER, "statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        List<UnparseableRow> unparseable = result.response().unparseableRows();
        String persisted = objectMapper.writeValueAsString(result.documentContext().unparseable());

        // The review screen still names the cell.
        assertThat(unparseable).extracting(UnparseableRow::reason).containsExactlyInAnyOrder(
                "Date value \"31/13/2026\" didn't match any known date format",
                "Amount value \"2OO.OO\" didn't match any known numeric format");
        // The persisted summary does not.
        assertThat(persisted).doesNotContain("31/13/2026").doesNotContain("2OO.OO");
        assertThat(result.documentContext().unparseable().reasons()).containsOnly(
                entry("Date value \"99/99/9999\" didn't match any known date format", 1),
                entry("Amount value \"9XX.XX\" didn't match any known numeric format", 1));
    }

    @Test
    void aPdfSummaryKeysUnreadableCellsByShapeNotByValue() throws Exception {
        var result = pdfGenerator().generateSectionsWithContext(USER, "statement.pdf",
                PdfFixtureBuilder.buildUnreadableAmountCellSample(), null);
        List<UnparseableRow> unparseable = new ArrayList<>();
        for (StagedAccountSection section : result.sections()) unparseable.addAll(section.unparseableRows());
        String persisted = objectMapper.writeValueAsString(result.documentContext().unparseable());

        assertThat(unparseable).extracting(UnparseableRow::reason).containsExactly(
                "Amount value \"2OO.OO\" didn't match any known numeric format");
        assertThat(persisted).doesNotContain("2OO.OO");
        assertThat(result.documentContext().unparseable().reasons()).containsOnly(
                entry("Amount value \"9XX.XX\" didn't match any known numeric format", 1));
    }
    private static TransactionNormalizer normalizer() {
        CategorizationService categorization = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorization.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(suggestion);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorization,
                new DuplicateDetector(transactions, TestAccountRepositories.anyLive()), TestRuleEngines.empty());
    }

    private static ImportVerifier verifier() {
        return new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(), new SummaryTotalsValidator(),
                new ColumnAmbiguityValidator(), new RowAccountingValidator(), new CreditCardStatementTotalsValidator(),
                new CreditCardFlowReconciliationValidator(), new DescriptionCorruptionValidator());
    }

    private static PreviewGenerator csvGenerator() {
        return new PreviewGenerator(new CsvParser(), normalizer(), new StatementValidator(ProductDiscovery.standard()),
                verifier(), TestRuleEngines.empty());
    }

    private static PdfPreviewGenerator pdfGenerator() {
        return new PdfPreviewGenerator(new NativePdfAcquirer(new PdfTextExtractor()), new PdfTableLocator(),
                new PdfMetadataExtractor(), normalizer(), ProductDiscovery.standard(), new ProductAttributeExtractor(),
                verifier(), TestRuleEngines.empty());
    }
}
