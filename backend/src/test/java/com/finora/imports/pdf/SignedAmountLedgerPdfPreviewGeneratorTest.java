package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A slice small finance bank savings statement, end to end through the real generator: apostrophe
 * dates, a bank-signed AMOUNT column, the period alone on the first line, "A/C number", the NESF
 * IFSC, and a support footer under the last row. Before this, the real statement staged nothing.
 */
class SignedAmountLedgerPdfPreviewGeneratorTest {

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
        TransactionNormalizer normalizer = new TransactionNormalizer(categorizationService,
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()),
                com.finora.imports.TestRuleEngines.empty());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                normalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(),
                        new com.finora.imports.DescriptionCorruptionValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    @Test
    void aSliceLedgerStagesEveryRowWithTheDirectionItsBalanceProves() throws Exception {
        var result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "statement.pdf", PdfFixtureBuilder.buildSignedAmountLedgerWithSupportFooterSample());

        assertThat(result.sections()).hasSize(1);
        var section = result.sections().get(0);
        List<StagedRow> rows = section.rows();
        assertThat(rows).extracting(StagedRow::date).containsExactly(
                LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 16));
        assertThat(rows).extracting(StagedRow::type).containsExactly("INCOME", "INCOME", "EXPENSE", "EXPENSE");
        assertThat(rows).extracting(r -> r.amount().toPlainString())
                .containsExactly("6000.00", "0.87", "1954.00", "122.30");
        assertThat(rows.get(3).description()).isEqualTo("UPI-Debit-SAMPLE STORE");

        var detected = section.detectedAccount();
        assertThat(detected.bank().id()).isEqualTo("SLICE");
        assertThat(detected.statementPeriodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(detected.statementPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(detected.accountNumberMasked()).endsWith("1234");

        assertThat(section.verification().findings())
                .filteredOn(f -> f.rule().equals("BALANCE_CHAIN")).extracting(f -> f.outcome()).containsExactly("VERIFIED");
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("SIGNED_AMOUNT_COLUMN");
    }

    @Test
    void aCardWithTheSameShapeKeepsItsPurchasesAsExpenses() throws Exception {
        var result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "card.pdf", PdfFixtureBuilder.buildCardWithSignedAmountAndOutstandingBalanceSample());

        var section = result.sections().get(0);
        assertThat(section.detectedAccount().detectedProduct()).isEqualTo("CREDIT_CARD");
        assertThat(section.rows()).filteredOn(r -> r.description().contains("SAMPLE FUEL STATION"))
                .extracting(StagedRow::type).containsExactly("EXPENSE");
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .doesNotContain("SIGNED_AMOUNT_COLUMN");
    }
}
