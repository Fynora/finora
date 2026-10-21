package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ICICI Bank's credit-card CSV export: one amount column ("Amount(in Rs)") and a separate
 * "BillingAmountSign" column that marks a credit (a payment to the card, a refund) with "CR" and
 * leaves a purchase blank.
 *
 * <p>The header set below is the one a real upload carried: it hashes to that upload's layout
 * fingerprint, which the first test pins. Every value in the rows is synthetic.
 *
 * <p>Before this fix the sign column was not read at all, so a payment to the card staged as an
 * expense, and nothing in the header counted as a card signal, so the account suggestion was
 * SAVINGS -- which made the review screen preselect the user's existing savings account at the
 * same bank.
 */
class IciciCardCsvPreviewGeneratorTest {

    private static final String HEADER =
            "Date,Sr.No.,Transaction Details,Reward Point Header,Intl.Amount,Amount(in Rs),BillingAmountSign\n";

    private static final String CSV = HEADER
            + "02/04/2025,10000000001,SYNTHETIC GROCERY STORE MUMBAI IN,12,,1250.00,\n"
            + "05/04/2025,10000000002,SYNTHETIC ONLINE PAYMENT THANK YOU,0,,8000.00,CR\n"
            + "09/04/2025,10000000003,SYNTHETIC MERCHANT REFUND,0,,499.00,CR\n"
            + "12/04/2025,10000000004,SYNTHETIC FUEL STATION PUNE IN,6,,2100.50,\n";

    private PreviewGenerator.CsvGenerationResult generate(String csv) throws Exception {
        CategorizationService categorizationService = mock(CategorizationService.class);
        CategorizationService.Suggestion none = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any())).thenReturn(none);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(none);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(none);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        TransactionNormalizer normalizer = new TransactionNormalizer(categorizationService, duplicateDetector, TestRuleEngines.empty());
        PreviewGenerator generator = new PreviewGenerator(new CsvParser(), normalizer,
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(), new SummaryTotalsValidator(),
                        new ColumnAmbiguityValidator(), new RowAccountingValidator(), new CreditCardStatementTotalsValidator(),
                        new CreditCardFlowReconciliationValidator(), new DescriptionCorruptionValidator()),
                TestRuleEngines.empty());
        return generator.generateWithContext(UUID.randomUUID(), "ICICI_card_statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void headerSetIsTheProductionLayout() throws Exception {
        assertThat(generate(CSV).documentContext().buildFingerprint()).isEqualTo("FP-1-6D8FF624");
    }

    @Test
    void everyRowIsReadWithItsDateAmountAndDescription() throws Exception {
        List<StagedRow> rows = generate(CSV).response().rows();

        assertThat(rows).extracting(StagedRow::date).containsExactly(
                LocalDate.of(2025, 4, 2), LocalDate.of(2025, 4, 5), LocalDate.of(2025, 4, 9), LocalDate.of(2025, 4, 12));
        assertThat(rows).extracting(StagedRow::amount).usingElementComparator(BigDecimal::compareTo).containsExactly(
                new BigDecimal("1250.00"), new BigDecimal("8000.00"), new BigDecimal("499.00"), new BigDecimal("2100.50"));
        assertThat(rows).extracting(StagedRow::description).containsExactly(
                "SYNTHETIC GROCERY STORE MUMBAI IN", "SYNTHETIC ONLINE PAYMENT THANK YOU",
                "SYNTHETIC MERCHANT REFUND", "SYNTHETIC FUEL STATION PUNE IN");
    }

    @Test
    void crInBillingAmountSignIsACredit_blankIsADebit() throws Exception {
        List<StagedRow> rows = generate(CSV).response().rows();

        assertThat(rows).extracting(StagedRow::type).containsExactly("EXPENSE", "INCOME", "INCOME", "EXPENSE");
    }

    @Test
    void theAccountSuggestionIsACreditCard() throws Exception {
        var detected = generate(CSV).response().detectedAccount();

        assertThat(detected.suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(detected.bank().id()).isEqualTo("ICICI");
    }

    @Test
    void aBlankSignCellOnEveryRowStillStagesAsDebits() throws Exception {
        String allPurchases = HEADER
                + "02/04/2025,10000000001,SYNTHETIC GROCERY STORE MUMBAI IN,12,,1250.00,\n"
                + "12/04/2025,10000000004,SYNTHETIC FUEL STATION PUNE IN,6,,2100.50,\n";

        assertThat(generate(allPurchases).response().rows()).extracting(StagedRow::type)
                .containsExactly("EXPENSE", "EXPENSE");
    }

    @Test
    void aLowerCaseOrDrMarkerIsReadTheSameWay() throws Exception {
        String marked = HEADER
                + "02/04/2025,10000000001,SYNTHETIC GROCERY STORE MUMBAI IN,12,,1250.00,Dr\n"
                + "05/04/2025,10000000002,SYNTHETIC ONLINE PAYMENT THANK YOU,0,,8000.00,cr\n";

        assertThat(generate(marked).response().rows()).extracting(StagedRow::type)
                .containsExactly("EXPENSE", "INCOME");
    }
}
