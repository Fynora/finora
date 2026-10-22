package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Three CSV shapes seen in real uploads (2026-10-21), each run through the real CSV staging path.
 * Every value here is invented.
 *
 * <ul>
 *   <li>A file that starts with a UTF-8 byte-order mark. Spreadsheet programs write one when they
 *       save "CSV UTF-8". Java's reader keeps it, so the first header cell read as
 *       "&#92;uFEFFDate", matched no date column, and a file whose header is its first line found
 *       no header at all.</li>
 *   <li>ICICI Bank's credit-card transaction export: one signed amount column, where a payment or
 *       refund to the card is negative. The minus sign used to be dropped, so every credit staged
 *       as a spend, and the account was read as a savings account.</li>
 *   <li>A file that lists transactions from several accounts in an Account column. It is not one
 *       account's statement, and an import lands in exactly one account.</li>
 * </ul>
 */
class CsvExportShapesTest {

    private static final String BOM = "\uFEFF";

    private static final String ICICI_CARD_HEADER =
            "Date,Sr.No.,Transaction Details,Reward Point Header,Intl.Amount,Amount(in Rs),BillingAmountSign\n";

    private static final String ICICI_CARD_ROWS =
            "01-Apr-25,1,UPI-100000000001-SHOP ALPHA,12,0.00,\"1,250.00\",\"1,250.00\"\n"
                    + "02-Apr-25,2,UPI-100000000002-CAFE BETA,1,0.00,120.50,120.50\n"
                    + "05-Apr-25,3,BBPS PAYMENT RECEIVED,0,0.00,-1370.50,-1370.50\n"
                    + "07-Apr-25,4,UPI-100000000003-STORE GAMMA,0,0.00,-99.00,-99.00\n";

    // ------------------------------------------------------------------ byte-order mark

    @Test
    void aHeaderOnTheFirstLineIsFoundWhenTheFileStartsWithAByteOrderMark() throws Exception {
        String csv = "Date,Description,Amount,Type\n"
                + "01/04/2025,UPI-100000000001-SHOP ALPHA,250.00,DR\n"
                + "02/04/2025,SALARY CREDIT,1000.00,CR\n";

        StagingResponse withMark = viaCsv("plain.csv", BOM + csv);
        StagingResponse without = viaCsv("plain.csv", csv);

        assertThat(withMark.rows()).hasSize(2);
        assertThat(withMark.rows()).extracting(StagedRow::amount, StagedRow::type)
                .containsExactlyElementsOf(without.rows().stream()
                        .map(r -> org.assertj.core.groups.Tuple.tuple(r.amount(), r.type())).toList());
        assertThat(withMark.unparseableRows()).isEmpty();
    }

    @Test
    void readAllDropsOnlyALeadingByteOrderMark() throws Exception {
        List<String[]> rows = new CsvParser().readAll(new ByteArrayInputStream(
                (BOM + "Date,Amount\n01/04/2025,\uFEFF5\n").getBytes(StandardCharsets.UTF_8)));

        assertThat(rows.get(0)[0]).isEqualTo("Date");
        // A mark anywhere else is content, not an encoding signature, and is left alone.
        assertThat(rows.get(1)[1]).isEqualTo("\uFEFF5");
    }

    @Test
    void anEmptyFileAndAFileThatIsOnlyAByteOrderMarkReadAsNoRows() throws Exception {
        CsvParser parser = new CsvParser();
        assertThat(parser.readAll(new ByteArrayInputStream(new byte[0]))).isEmpty();
        assertThat(parser.readAll(new ByteArrayInputStream(BOM.getBytes(StandardCharsets.UTF_8)))).isEmpty();
    }

    // ------------------------------------------------------------------ ICICI card export

    @Test
    void iciciCardExportStagesSpendsAsExpensesAndNegativeAmountsAsCredits() throws Exception {
        StagingResponse response = viaCsv("ICICI_Credit_Card_April_2025.csv", BOM + ICICI_CARD_HEADER + ICICI_CARD_ROWS);

        List<StagedRow> rows = response.rows();
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(StagedRow::date).containsExactly(
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 2), LocalDate.of(2025, 4, 5), LocalDate.of(2025, 4, 7));
        assertThat(rows).extracting(StagedRow::amount).containsExactly(
                new BigDecimal("1250.00"), new BigDecimal("120.50"), new BigDecimal("1370.50"), new BigDecimal("99.00"));
        assertThat(rows).extracting(StagedRow::type).containsExactly("EXPENSE", "EXPENSE", "INCOME", "INCOME");
        assertThat(rows).extracting(StagedRow::description).containsExactly(
                "UPI-100000000001-SHOP ALPHA", "UPI-100000000002-CAFE BETA", "BBPS PAYMENT RECEIVED",
                "UPI-100000000003-STORE GAMMA");
        assertThat(response.unparseableRows()).isEmpty();
        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
    }

    @Test
    void aNegativeAmountInAnOrdinaryBankExportIsStillReadAsBefore() throws Exception {
        // Only the card export's own columns turn a minus sign into a credit. A bank export with one
        // signed amount column and no other direction signal keeps today's reading.
        String csv = "Date,Description,Amount\n"
                + "01/04/2025,UPI-100000000001-SHOP ALPHA,-250.00\n"
                + "02/04/2025,UPI-100000000002-CAFE BETA,300.00\n";

        StagingResponse response = viaCsv("bank.csv", csv);

        assertThat(response.rows()).extracting(StagedRow::type).containsExactly("EXPENSE", "EXPENSE");
        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("SAVINGS");
    }

    // ------------------------------------------------------------------ several accounts in one file

    @Test
    void aFileListingSeveralAccountsIsRefused() {
        String csv = BOM + "Date,Transaction Type,Amount (INR),Account,Category,Description,Reference,Statement Source\n"
                + "2025-04-01,Expense,250.0,Bank Alpha - 1111,Shopping,UPI-100000000001-SHOP ALPHA,,\n"
                + "2025-04-02,Expense,120.0,Bank Beta,Food & Dining,UPI-100000000002-CAFE BETA,,\n"
                + "2025-04-03,Income,900.0,Bank Alpha - 1111,Bank Credit,NEFT-100000000003-PAYER,,\n";

        assertThatThrownBy(() -> viaCsv("combined.csv", csv))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_MULTI_ACCOUNT_FILE));
    }

    @Test
    void anAccountColumnThatNamesOneAccountImportsAsBefore() throws Exception {
        String csv = "Date,Description,Amount,Type,Account\n"
                + "01/04/2025,UPI-100000000001-SHOP ALPHA,250.00,DR,Bank Alpha - 1111\n"
                + "02/04/2025,SALARY CREDIT,1000.00,CR,Bank Alpha - 1111\n"
                + "03/04/2025,UPI-100000000002-CAFE BETA,80.00,DR,\n";

        assertThat(viaCsv("one-account.csv", csv).rows()).hasSize(3);
    }

    @Test
    void accountValuesThatDifferOnlyInCaseOrSpacingAreOneAccount() throws Exception {
        String csv = "Date,Description,Amount,Type,Account\n"
                + "01/04/2025,UPI-100000000001-SHOP ALPHA,250.00,DR,Bank Alpha 1111\n"
                + "02/04/2025,SALARY CREDIT,1000.00,CR,  BANK ALPHA  1111 \n";

        assertThat(viaCsv("one-account.csv", csv).rows()).hasSize(2);
    }

    @Test
    void severalCardNumbersAreNotSeveralAccounts() throws Exception {
        // A card export can list the primary card and an add-on card row by row; both bill to one
        // card account, so a Card Number column is not read as an account column.
        String csv = "Date,Description,Amount,Card Number\n"
                + "01/04/2025,UPI-100000000001-SHOP ALPHA,250.00,XXXX XXXX XXXX 1111\n"
                + "02/04/2025,UPI-100000000002-CAFE BETA,80.00,XXXX XXXX XXXX 2222\n";

        assertThat(viaCsv("card.csv", csv).rows()).hasSize(2);
    }

    // ------------------------------------------------------------------ the real CSV staging path

    private static StagingResponse viaCsv(String fileName, String csv) throws Exception {
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
        ImportVerifier verifier = new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(),
                new CreditCardStatementTotalsValidator(), new CreditCardFlowReconciliationValidator(),
                new DescriptionCorruptionValidator());
        PreviewGenerator generator = new PreviewGenerator(new CsvParser(), normalizer,
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), verifier,
                TestRuleEngines.empty());
        return generator.generate(UUID.randomUUID(), fileName,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }
}
