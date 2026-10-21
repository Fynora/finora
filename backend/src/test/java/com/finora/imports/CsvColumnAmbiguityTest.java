package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A CSV gets the column-ambiguity check too, and its "Row N" is the row a person counts to.
 *
 * <p>The CSV path used to hand that check no rows, so it answered "not applicable" on every CSV:
 * a line with both a withdrawal and a deposit filled in was read one way without a word, while the
 * same line in a PDF was reported for checking. Every case runs through the real CSV generator.
 * A CSV is shown in file order, so the number is the row's place among the rows that staged --
 * which is not its place in the file when a line before it did not stage.
 */
class CsvColumnAmbiguityTest {

    private static final String HEADER = "Date,Narration,Withdrawal,Deposit,Balance\n";

    private static Stream<Arguments> statements() {
        return Stream.of(
                Arguments.of("both columns filled on the third line", HEADER
                        + "01/07/2026,UPI/DR/ROW A,100.00,,9900.00\n"
                        + "02/07/2026,UPI/DR/ROW B,200.00,,9700.00\n"
                        + "03/07/2026,UPI/DR/ROW C,300.00,50.00,9450.00\n"
                        + "04/07/2026,UPI/DR/ROW D,400.00,,9050.00\n", List.of("C")),
                Arguments.of("the statement's own opening-balance line first", HEADER
                        + "01/07/2026,OPENING BALANCE,,,10000.00\n"
                        + "01/07/2026,UPI/DR/ROW A,100.00,,9900.00\n"
                        + "02/07/2026,UPI/DR/ROW B,200.00,50.00,9750.00\n"
                        + "03/07/2026,UPI/DR/ROW C,300.00,,9450.00\n", List.of("B")),
                Arguments.of("a line with an unreadable amount and blank lines before the ambiguous one", HEADER
                        + "01/07/2026,UPI/DR/ROW A,100.00,,9900.00\n"
                        + "\n"
                        + "02/07/2026,UPI/DR/ROW X,150/-,,9750.00\n"
                        + ",,,,\n"
                        + "03/07/2026,UPI/DR/ROW C,300.00,50.00,9500.00\n", List.of("C")),
                Arguments.of("newest first, two ambiguous lines", HEADER
                        + "04/07/2026,UPI/DR/ROW D,400.00,25.00,9000.00\n"
                        + "03/07/2026,UPI/DR/ROW C,300.00,,9375.00\n"
                        + "02/07/2026,UPI/DR/ROW B,200.00,50.00,9675.00\n"
                        + "01/07/2026,UPI/DR/ROW A,100.00,,9825.00\n", List.of("D", "B")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statements")
    @SuppressWarnings("unchecked")
    void aCsvNamesTheRowThatIsActuallyAmbiguous(String name, String csv, List<String> ambiguousInShownOrder)
            throws Exception {
        StagingResponse response = viaCsv(csv);
        List<StagedRow> shown = response.rows();
        ImportDto.VerificationFinding finding = ambiguityOf(response);

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details())
                .containsEntry(ColumnAmbiguityValidator.ROW_INDEX_BASIS, ColumnAmbiguityValidator.ROWS_SHOWN);
        List<Map<String, Object>> ambiguities = (List<Map<String, Object>>) finding.details().get("ambiguities");
        assertThat(ambiguities).hasSameSizeAs(ambiguousInShownOrder);
        for (int n = 0; n < ambiguities.size(); n++) {
            String label = "ROW " + ambiguousInShownOrder.get(n);
            int ambiguousRow = -1;
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).description().endsWith(label)) ambiguousRow = i;
            }
            assertThat(ambiguousRow).as("%s has to be among the rows shown", label).isNotNegative();
            assertThat(ambiguities.get(n).get("rowIndex"))
                    .as("the number printed as \"Row N\" must be %s's place among the rows shown", label)
                    .isEqualTo(ambiguousRow);
        }
    }

    @Test
    void aCsvWhoseColumnsAgreeIsVerifiedAndEveryOtherCheckIsUnchanged() throws Exception {
        String csv = HEADER
                + "01/07/2026,UPI/DR/ROW A,100.00,0.00,9900.00\n"
                + "02/07/2026,UPI/CR/ROW B,0.00,50.00,9950.00\n";
        StagingResponse response = viaCsv(csv);

        assertThat(ambiguityOf(response).outcome()).isEqualTo("VERIFIED");
        // Every other rule gets exactly the inputs the CSV path always gave it, except row
        // accounting, which now gets the same located rows as this one (CsvRowEvidenceTest): its
        // counts change and its outcome does not.
        ImportVerifier verifier = verifier();
        ImportDto.VerificationReport withoutRows = verifier.verify(response.rows(),
                response.detectedAccount() == null ? null : response.detectedAccount().openingBalance(),
                response.detectedAccount() == null ? null : response.detectedAccount().closingBalance());
        assertThat(response.verification().findings()).hasSameSizeAs(withoutRows.findings());
        for (int i = 0; i < withoutRows.findings().size(); i++) {
            ImportDto.VerificationFinding before = withoutRows.findings().get(i);
            if (ColumnAmbiguityValidator.RULE.equals(before.rule())) {
                assertThat(before.outcome()).as("what every CSV used to get").isEqualTo("NOT_APPLICABLE");
                continue;
            }
            if (RowAccountingValidator.RULE.equals(before.rule())) {
                ImportDto.VerificationFinding after = response.verification().findings().get(i);
                assertThat(before.details()).as("what every CSV used to get").containsEntry("locatedRowCount", 0);
                assertThat(after.outcome()).isEqualTo(before.outcome());
                assertThat(after.details()).containsEntry("locatedRowCount", 2);
                continue;
            }
            assertThat(response.verification().findings().get(i)).isEqualTo(before);
        }
        assertThat(response.verification().reliabilityStatus()).isEqualTo(withoutRows.reliabilityStatus());
    }

    @Test
    void anAmbiguousCsvIsMarkedForReviewLikeAnAmbiguousPdf() throws Exception {
        StagingResponse response = viaCsv(HEADER
                + "01/07/2026,UPI/DR/ROW A,100.00,,9900.00\n"
                + "02/07/2026,UPI/DR/ROW B,200.00,50.00,9750.00\n");

        assertThat(ambiguityOf(response).outcome()).isEqualTo("WARNING");
        assertThat(response.verification().reliabilityStatus())
                .isNotEqualTo(com.finora.imports.ImportReliabilityStatus.CLEAN);
        assertThat(com.finora.imports.trust.TrustPredicate.evaluate(List.of(response.verification()),
                List.of(), java.time.LocalDate.of(2026, 8, 1)).hold())
                .as("held for review, the same rule a PDF with this line already meets")
                .isTrue();
    }

    private static ImportDto.VerificationFinding ambiguityOf(StagingResponse response) {
        return response.verification().findings().stream()
                .filter(f -> ColumnAmbiguityValidator.RULE.equals(f.rule())).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- the real CSV staging path

    private static ImportVerifier verifier() {
        return new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(),
                new CreditCardStatementTotalsValidator(), new CreditCardFlowReconciliationValidator(),
                new DescriptionCorruptionValidator());
    }

    private static StagingResponse viaCsv(String csv) throws Exception {
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
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()), TestRuleEngines.empty());
        PreviewGenerator generator = new PreviewGenerator(new CsvParser(), normalizer,
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), verifier(),
                TestRuleEngines.empty());
        return generator.generate(UUID.randomUUID(), "synthetic.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }
}
