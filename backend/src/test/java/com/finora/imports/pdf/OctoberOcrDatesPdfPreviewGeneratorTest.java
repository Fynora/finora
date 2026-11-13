package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * October on a scanned HSBC savings statement, value by value. OCR reads the O of OCT as a zero
 * ("010CT2026", "130ct2026"); before the parser read that back, no October date parsed. On the older
 * layout every October transaction silently took the last date that did parse (30 September) with
 * every amount check passing; on the newer layout no row anchored and the statement read nothing.
 */
class OctoberOcrDatesPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(suggestion);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService,
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()),
                com.finora.imports.TestRuleEngines.empty());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(),
                        new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(),
                        new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(),
                        new com.finora.imports.DescriptionCorruptionValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private StagingResponse generate() throws Exception {
        return realGenerator().generate(UUID.randomUUID(), "statement.pdf",
                PdfFixtureBuilder.buildOctoberDatesOcrReadWithAZeroSample());
    }

    @Test
    void everyOctoberTransactionKeepsItsOwnPrintedDate() throws Exception {
        List<StagedRow> rows = generate().rows();

        assertThat(rows).extracting(StagedRow::date).containsExactly(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 13));
        assertThat(rows).noneMatch(r -> r.date().equals(LocalDate.of(2026, 9, 30)));
    }

    @Test
    void eachTransactionKeepsItsOwnAmountAndBalance() throws Exception {
        List<StagedRow> rows = generate().rows();

        assertThat(rows).extracting(StagedRow::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("50000.00"), new BigDecimal("1000.00"),
                        new BigDecimal("500.00"), new BigDecimal("2000.00"));
        assertThat(rows).extracting(StagedRow::balanceAfter).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("58000.00"), new BigDecimal("57000.00"),
                        new BigDecimal("56500.00"), new BigDecimal("54500.00"));
    }

    @Test
    void theBalanceChainVerifies() throws Exception {
        StagingResponse response = generate();

        assertThat(response.verification().findings())
                .filteredOn(f -> f.rule().equals("BALANCE_CHAIN"))
                .extracting(f -> f.outcome()).containsExactly("VERIFIED");
    }
}
