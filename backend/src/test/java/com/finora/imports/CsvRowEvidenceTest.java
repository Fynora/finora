package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.imports.trust.TrustPredicate;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a CSV says about the rows it did not stage: the parse-diagnostics histogram and the
 * ROW_ACCOUNTING finding.
 *
 * <p>Both used to report nothing for every CSV. The histogram was written only by the PDF locator,
 * and ROW_ACCOUNTING was handed empty lists, so a real card export with 91 data rows and 79 staged
 * showed "0 unmatched lines" in Layout Studio and "0 located, 0 unparseable" in its evidence. Every
 * case runs through the real CSV generator. Every value below is invented.
 */
class CsvRowEvidenceTest {

    private static final String CARD_HEADER =
            "Date,Sr.No.,Transaction Details,Reward Point Header,Intl.Amount,Amount(in Rs),BillingAmountSign\n";

    private PreviewGenerator.CsvGenerationResult generate(String csv) throws Exception {
        CategorizationService categorizationService = mock(CategorizationService.class);
        CategorizationService.Suggestion none =
                new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any())).thenReturn(none);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(none);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(none);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        DuplicateDetector duplicateDetector =
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        TransactionNormalizer normalizer = new TransactionNormalizer(categorizationService, duplicateDetector,
                TestRuleEngines.empty());
        ImportVerifier verifier = new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(),
                new CreditCardStatementTotalsValidator(), new CreditCardFlowReconciliationValidator(),
                new DescriptionCorruptionValidator());
        PreviewGenerator generator = new PreviewGenerator(new CsvParser(), normalizer,
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), verifier,
                TestRuleEngines.empty());
        return generator.generateWithContext(UUID.randomUUID(), "synthetic.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private static ImportDto.VerificationFinding finding(ImportDto.StagingResponse response, String rule) {
        return response.verification().findings().stream()
                .filter(f -> rule.equals(f.rule())).findFirst().orElseThrow();
    }

    /** Every histogram key is an engine-authored reason, optionally followed by a value's shape. */
    private static void assertNoCellContent(Map<String, Integer> histogram) {
        assertThat(histogram.keySet()).allSatisfy(key ->
                assertThat(key).matches("UNANCHORED_[A-Z_]+(:[9X ,./~()+-]*)?"));
    }

    @Test
    void aCardExportCountsEveryRowThatDidNotStage_byReason() throws Exception {
        String csv = CARD_HEADER
                + "01/09/2026,1,SYNTHETIC SHOP ONE,2,,100.00,100.00\n"
                + "02/09/2026,2,SYNTHETIC SHOP TWO,4,,250.50,250.50\n"
                + "03/09/2026,3,SYNTHETIC PAYMENT,0,,-500.00,-500.00\n"
                + "04-Sept-2026,4,SYNTHETIC SHOP THREE,1,,75.00,75.00\n"
                + "05/09/2026,5,SYNTHETIC SHOP FOUR,1,,Rs 75 only,\n"
                + ",6,SYNTHETIC CONTINUATION,,,,\n"
                + "\n"
                + "Total,,,,,,\n";

        PreviewGenerator.CsvGenerationResult result = generate(csv);
        ImportDto.StagingResponse response = result.response();
        Map<String, Integer> histogram = result.documentContext().unanchoredReasons();

        assertThat(response.rows()).hasSize(3);
        assertThat(response.unparseableRows()).hasSize(4);
        assertThat(histogram).containsExactlyInAnyOrderEntriesOf(Map.of(
                "UNANCHORED_DATE_UNPARSEABLE:99-XXXX-9999", 1,
                "UNANCHORED_AMOUNT_UNPARSEABLE:XX 99 XXXX", 1,
                TransactionNormalizer.REASON_DATE_COLUMN_EMPTY, 1,
                "UNANCHORED_DATE_UNPARSEABLE:XXXXX", 1));
        assertNoCellContent(histogram);

        ImportDto.VerificationFinding rowAccounting = finding(response, RowAccountingValidator.RULE);
        assertThat(rowAccounting.outcome()).isEqualTo("VERIFIED");
        assertThat(rowAccounting.details())
                .containsEntry("stagedTransactionCount", 3)
                // The blank line is formatting, not a row: located counts the 7 non-blank lines.
                .containsEntry("locatedRowCount", 7)
                .containsEntry("unparseableRowCount", 4)
                .containsEntry("droppedTransactionCandidateCount", 0);
        // The same located rows the column-ambiguity rule already checked.
        assertThat(finding(response, ColumnAmbiguityValidator.RULE).details()).containsEntry("rowsChecked", 7);
    }

    @Test
    void theNewCountsMoveNoStatusAndNoHold() throws Exception {
        String csv = CARD_HEADER
                + "01/09/2026,1,SYNTHETIC SHOP ONE,2,,100.00,100.00\n"
                + "Total,,,,,,\n";

        ImportDto.StagingResponse response = generate(csv).response();

        assertThat(finding(response, RowAccountingValidator.RULE).details())
                .containsEntry("unparseableRowCount", 1);
        assertThat(response.verification().reliabilityStatus()).isEqualTo(ImportReliabilityStatus.CLEAN);
        assertThat(TrustPredicate.evaluate(List.of(response.verification()), null, LocalDate.of(2026, 10, 22))
                .hold()).isFalse();
    }

    @Test
    void aBalanceLineWithAValueInAnUnknownColumnIsCounted() throws Exception {
        // A balance alone would make it the statement's own balance line, excluded uncounted; the
        // value in a column the parser does not know is why it cannot be excluded with confidence.
        String csv = "Date,Description,Debit,Credit,Balance,Branch Note\n"
                + "01/07/2026,REAL DEBIT,100.00,,9900.00,\n"
                + "02/07/2026,NOTE LINE,,,9900.00,SYNTHETIC NOTE\n";

        PreviewGenerator.CsvGenerationResult result = generate(csv);

        assertThat(result.response().unparseableRows()).hasSize(1);
        assertThat(result.documentContext().unanchoredReasons())
                .containsExactly(Map.entry(PreviewGenerator.REASON_UNRECOGNIZED_COLUMN_VALUE, 1));
    }

    @Test
    void aDatedLineWithEveryAmountAndBalanceBlankIsCounted() throws Exception {
        String csv = "Date,Description,Debit,Credit,Balance\n"
                + "01/07/2026,REAL DEBIT,100.00,,9900.00\n"
                + "02/07/2026,NOTE LINE,,,\n";

        PreviewGenerator.CsvGenerationResult result = generate(csv);

        assertThat(result.response().unparseableRows()).hasSize(1);
        assertThat(result.documentContext().unanchoredReasons())
                .containsExactly(Map.entry(TransactionNormalizer.REASON_AMOUNT_COLUMN_EMPTY, 1));
    }

    @Test
    void aRecognisedAmountColumnHoldingAnUnreadableValueIsCountedByShape() throws Exception {
        String csv = "Date,Description,Debit,Credit,Balance\n"
                + "02/07/2026,REAL RENT PAYMENT,1500/-,,48500.00\n";

        PreviewGenerator.CsvGenerationResult result = generate(csv);

        assertThat(result.response().unparseableRows()).hasSize(1);
        assertThat(result.documentContext().unanchoredReasons())
                .containsExactly(Map.entry("UNANCHORED_AMOUNT_UNPARSEABLE:9999/-", 1));
    }

    @Test
    void theStatementsOwnBalanceLineIsNeitherStagedNorCounted() throws Exception {
        // Excluded as a balance marker on purpose, so it is located without being unparseable --
        // located minus staged minus unparseable is what the evidence leaves for such lines.
        String csv = "Date,Description,Debit,Credit,Balance\n"
                + "01/07/2026,OPENING BALANCE,,,10000.00\n"
                + "01/07/2026,REAL DEBIT,100.00,,9900.00\n";

        PreviewGenerator.CsvGenerationResult result = generate(csv);

        assertThat(result.response().rows()).hasSize(1);
        assertThat(result.response().unparseableRows()).isEmpty();
        assertThat(result.documentContext().unanchoredReasons()).isEmpty();
        assertThat(finding(result.response(), RowAccountingValidator.RULE).details())
                .containsEntry("locatedRowCount", 2)
                .containsEntry("unparseableRowCount", 0);
    }

    @Test
    void aFileWithNoHeaderCountsEveryNonBlankLine() throws Exception {
        PreviewGenerator.CsvGenerationResult result = generate("alpha,beta\n\none two,3\nfive,6\n");

        assertThat(result.response().unparseableRows()).hasSize(3);
        assertThat(result.documentContext().unanchoredReasons())
                .containsExactly(Map.entry(PreviewGenerator.REASON_NO_HEADER_ROW, 3));
    }

    @Test
    void aFileWhoseEveryRowFailedAccountsForThemAll() throws Exception {
        // NOT_APPLICABLE ("no table was located") until the CSV path passed its rows; a table was
        // located, and every row of it is accounted for as unparseable. The same outcome a PDF
        // section in this state already gets, and neither outcome warns.
        String csv = CARD_HEADER + "Total,,,,,,\nPage 1 of 1,,,,,,\n";

        ImportDto.StagingResponse response = generate(csv).response();

        assertThat(response.rows()).isEmpty();
        ImportDto.VerificationFinding rowAccounting = finding(response, RowAccountingValidator.RULE);
        assertThat(rowAccounting.outcome()).isEqualTo("VERIFIED");
        assertThat(rowAccounting.details())
                .containsEntry("locatedRowCount", 2)
                .containsEntry("unparseableRowCount", 2);
        assertThat(response.verification().reliabilityStatus()).isEqualTo(ImportReliabilityStatus.CLEAN);
    }

    @Test
    void aFullyStagedFileStillReportsNothingUnmatched() throws Exception {
        String csv = CARD_HEADER + "01/09/2026,1,SYNTHETIC SHOP ONE,2,,100.00,100.00\n";

        PreviewGenerator.CsvGenerationResult result = generate(csv);

        assertThat(result.documentContext().unanchoredReasons()).isEmpty();
        assertThat(finding(result.response(), RowAccountingValidator.RULE).details())
                .containsEntry("locatedRowCount", 1)
                .containsEntry("unparseableRowCount", 0);
    }
}
