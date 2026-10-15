package com.finora.imports;

import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.PdfStagingSessionResponse;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.entity.ImportSession;
import com.finora.imports.analysis.ImportVerificationRecorder;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.acquisition.DocumentTextAcquirer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A document that stages no transaction ANYWHERE is refused, however many sections were located.
 *
 * <p>P-002 Fix 1. {@code rejectIfNothingWasExtracted} existed and worked, but it was called only
 * inside {@code parseAndStagePdfWithSession}'s {@code sections.size() <= 1} branch. The
 * multi-section branch had no zero-extraction guard at all, and
 * {@link StagedAccountSectionFilter} deliberately returns every section unfiltered when none of
 * them staged a row -- its doc comment defers that verdict to "the caller's zero-transaction
 * guard", which on this path did not run. So a one-section document yielding nothing was cleanly
 * rejected, while the SAME failure spread across eight located sections was presented to the user
 * as eight accounts to confirm, all empty, seven of them prefilled SAVINGS.
 *
 * <p>The tests that read committed statement traces moved to {@code
 * MultiSectionZeroExtractionTracesTest} in the private Fynora/finora-statement-traces repository,
 * with the traces themselves (see docs/engineering/private-statement-traces.md). The tests left
 * here build their input by hand.
 */
class MultiSectionZeroExtractionTest {

    private final UUID userId = UUID.randomUUID();

    /**
     * A multi-section document that DOES stage transactions still stages, as a multi-account
     * session, with both sections intact. The corpus has no such document post-filter (every
     * multi-section trace in it is an all-empty one), so this is asserted on the synthetic
     * composite fixture -- otherwise the "at least one section has rows" half of the rule would be
     * untested and could be tightened by accident.
     */
    @Test
    void aMultiSectionDocumentWithTransactions_stillStagesBothSections() throws Exception {
        PdfStagingSessionResponse response = serviceFor(new NativeAcquirerHolder())
                .parseAndStagePdfWithSession(userId, "composite.pdf",
                        PdfFixtureBuilder.buildMultiSectionCompositeStatementSample(), null);

        assertThat(response.multiAccount()).isTrue();
        assertThat(response.sections()).hasSize(2);
        assertThat(response.sections().stream().mapToInt(s -> s.rows().size()).sum()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- harness

    /** Marker for "use the ordinary native text extractor", for the one fixture that is real PDF
     *  bytes rather than a trace. */
    private record NativeAcquirerHolder() {}

    private String describe(PdfStagingSessionResponse response) {
        return describe(new StagedAccountSection(response.staging().detectedAccount(),
                response.staging().rows(), response.staging().totalParsed(),
                response.staging().flaggedDuplicates(), response.staging().unparseableRows(),
                response.staging().verification()));
    }

    /** Every field of a staged section, flattened, so "unaffected" is a whole-object claim. */
    private String describe(StagedAccountSection section) {
        return "rows=" + section.rows()
                + "\ntotalParsed=" + section.totalParsed()
                + "\nflaggedDuplicates=" + section.flaggedDuplicates()
                + "\nunparseable=" + section.unparseableRows()
                + "\ndetected=" + section.detectedAccount()
                + "\nverification=" + section.verification();
    }

    private ImportSession session() {
        ImportSession session = new ImportSession();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        session.setUserId(userId);
        session.setFileName("statement.pdf");
        session.setFileContent(new byte[]{1});
        session.setExpiresAt(Instant.now().plusSeconds(600));
        session.setStatus(ImportSession.STATUS_STAGED);
        return session;
    }

    private TransactionNormalizer normalizer(CategorizationService categorizationService) {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorizationService, new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()),
                TestRuleEngines.empty());
    }

    private CategorizationService categorization() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        return categorizationService;
    }

    private ImportVerifier verifier() {
        return new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator(), new com.finora.imports.DescriptionCorruptionValidator());
    }

    private PdfPreviewGenerator generatorFor(Object acquirer) {
        CategorizationService categorizationService = categorization();
        TransactionNormalizer transactionNormalizer = normalizer(categorizationService);
        if (acquirer instanceof DocumentTextAcquirer textAcquirer) {
            return new PdfPreviewGenerator(textAcquirer, new PdfTableLocator(), new PdfMetadataExtractor(),
                    transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                    new com.finora.imports.product.ProductAttributeExtractor(), verifier(),
                    TestRuleEngines.empty());
        }
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(), verifier(),
                TestRuleEngines.empty());
    }

    /**
     * The real {@link ImportService} with its persistence collaborators mocked: what is under test
     * is which documents it refuses, and every parsing collaborator on the path from bytes to that
     * decision is the production one.
     */
    private ImportService serviceFor(Object acquirer) {
        CategorizationService categorizationService = categorization();
        TransactionNormalizer transactionNormalizer = normalizer(categorizationService);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        AccountRepository accountRepository = mock(AccountRepository.class);
        ImportSessionService importSessionService = mock(ImportSessionService.class);
        when(importSessionService.createSession(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(session());
        when(importSessionService.createMultiSection(any(), any(), any(), any(), any(), any())).thenReturn(session());

        PreviewGenerator previewGenerator = new PreviewGenerator(new CsvParser(), transactionNormalizer,
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), verifier(),
                TestRuleEngines.empty());

        com.finora.service.EntitlementService entitlementService = mock(com.finora.service.EntitlementService.class);
        when(entitlementService.hasEntitlement(any(), any())).thenReturn(true);
        return new ImportService(accountRepository, mock(AccountService.class), transactionRepository,
                mock(MerchantRepository.class), mock(StatementImportRepository.class), categorizationService,
                mock(ReconciliationService.class), mock(RecurringService.class), previewGenerator, duplicateDetector,
                new ImportRuleLearningService(mock(com.finora.service.SharedCorpusService.class),
                        mock(com.finora.repository.SharedMerchantCategoryAiSuggestionRepository.class)), importSessionService, generatorFor(acquirer),
                new com.finora.imports.product.ProductIdentityResolver(accountRepository, mock(com.finora.service.AuditService.class)),
                mock(com.finora.imports.ownership.OwnershipMatchService.class),
                new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), mock(com.finora.security.crypto.EncryptionService.class), "", ""),
                mock(StatementAnalysisRecorder.class), mock(ImportVerificationRecorder.class),
                mock(com.finora.service.MerchantLearningEventPublisher.class), mock(LayoutRegistryService.class),
                mock(com.finora.imports.evidence.ClosingBalanceEvidenceShadowObserver.class),
                entitlementService,
                mock(AccountAggregatorGuard.class), mock(com.finora.service.SharedCorpusService.class),
                mock(com.finora.service.UserMerchantCategoryResolutionService.class), org.mockito.Mockito.mock(com.finora.imports.StatementProvenanceRecorder.class), null, null);
    }
}
