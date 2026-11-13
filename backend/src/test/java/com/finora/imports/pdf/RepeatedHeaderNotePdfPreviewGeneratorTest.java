package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
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
 * The newer HSBC savings layout, which repeats its header and the "(DR=Debit)" note under it on
 * every page. Before the note was suppressed under a repeated header too, page 2's note took the
 * Balance cell and its brought-forward balance, the next transaction's narration and that
 * transaction's balance all piled into one row whose Balance read as nothing: the transaction was
 * lost. Value by value, on synthetic values.
 */
class RepeatedHeaderNotePdfPreviewGeneratorTest {

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
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(),
                        new com.finora.imports.DescriptionCorruptionValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private StagingResponse generate() throws Exception {
        return realGenerator().generate(UUID.randomUUID(), "statement.pdf", PdfFixtureBuilder.buildRepeatedHeaderNoteSample());
    }

    private static String flat(String s) {
        return s == null ? null : s.replaceAll("\\\\s+", " ").trim();
    }

    @Test
    void theTransactionUnderTheRepeatedHeaderIsReadWithItsOwnAmountAndBalance() throws Exception {
        List<StagedRow> rows = generate().rows();

        assertThat(rows).extracting(StagedRow::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("50000.00"), new BigDecimal("1000.00"), new BigDecimal("500.00"));
        assertThat(rows).extracting(StagedRow::balanceAfter).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("58000.00"), new BigDecimal("57000.00"), new BigDecimal("56500.00"));
        assertThat(flat(rows.get(2).description())).isEqualTo("UPI100000000005 100000000006 SAMPLE PAYEE TWO");
    }

    @Test
    void everyTransactionKeepsTheDateItWasPrintedUnder() throws Exception {
        assertThat(generate().rows()).extracting(StagedRow::date).containsOnly(LocalDate.of(2026, 10, 30));
    }

    @Test
    void theHeaderNotesAreNotReadAsLines() throws Exception {
        StagingResponse response = generate();

        assertThat(response.unparseableRows()).extracting(UnparseableRow::raw)
                .noneMatch(raw -> raw.values().stream().anyMatch(v -> v.contains("DR=Debit") || v.trim().equals("INR")));
        assertThat(response.rows()).noneMatch(r -> r.description() != null && r.description().contains("DR=Debit"));
    }

    @Test
    void theBalanceChainAndTheStatementsTotalsVerify() throws Exception {
        StagingResponse response = generate();

        assertThat(response.verification().findings())
                .filteredOn(f -> f.rule().equals("BALANCE_CHAIN") || f.rule().equals("STATEMENT_TOTALS"))
                .extracting(f -> f.rule() + "=" + f.outcome())
                .containsExactlyInAnyOrder("BALANCE_CHAIN=VERIFIED", "STATEMENT_TOTALS=VERIFIED");
    }
}
