package com.finora.imports.analysis;

import com.finora.imports.BalanceChainValidator;
import com.finora.imports.ColumnAmbiguityValidator;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.ImportVerifier;
import com.finora.imports.StatementTotalsValidator;
import com.finora.imports.SummaryTotalsValidator;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TestRuleEngines;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The staging pipeline the manual corpus probes run, built in one place so a new probe cannot drift
 * onto a different pipeline from the one {@link CorpusProbe} already validated against the corpus.
 */
final class ProbePipelines {

    private ProbePipelines() {}

    /** Constructed exactly as {@link CorpusProbe}'s probe does. */
    static PdfPreviewGenerator standardGenerator() {
        return new PdfPreviewGenerator(
                new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                stubbedNormalizer(), ProductDiscovery.standard(), new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator(), new com.finora.imports.DescriptionCorruptionValidator()),
                TestRuleEngines.empty());
    }

    /** Identical to {@link CorpusProbe}'s -- see its own doc for why both collaborators are stubbed. */
    private static TransactionNormalizer stubbedNormalizer() {
        CategorizationService categorization = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorization.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(suggestion);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorization, new DuplicateDetector(transactions, TestAccountRepositories.anyLive()),
                TestRuleEngines.empty());
    }
}
