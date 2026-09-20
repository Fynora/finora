package com.finora.imports;

import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;

import java.util.List;
import java.util.Random;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The real PDF generator with its persistence collaborators mocked, plus a deterministic way to damage a file. */
final class PdfGeneratorTestSupport {

    private PdfGeneratorTestSupport() {}

    static PdfPreviewGenerator generator() {
        CategorizationService cs = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(cs.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(cs.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        when(cs.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(suggestion);
        TransactionRepository tr = mock(TransactionRepository.class);
        when(tr.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        TransactionNormalizer normalizer = new TransactionNormalizer(cs,
                new DuplicateDetector(tr, TestAccountRepositories.anyLive()), TestRuleEngines.empty());
        ImportVerifier verifier = new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(),
                new CreditCardStatementTotalsValidator(), new CreditCardFlowReconciliationValidator(),
                new DescriptionCorruptionValidator());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                normalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(), verifier, TestRuleEngines.empty());
    }

    /** Overwrites 60 bytes with seeded noise at {@code fraction} of the way through -- the kind of damage
     *  a corrupted download leaves inside a content stream. Deterministic, so a test can name a case. */
    static byte[] garbled(byte[] pdf, double fraction) {
        byte[] damaged = pdf.clone();
        Random r = new Random(7);
        int start = (int) (damaged.length * fraction);
        for (int i = 0; i < 60 && start + i < damaged.length; i++) damaged[start + i] = (byte) r.nextInt(256);
        return damaged;
    }
}
