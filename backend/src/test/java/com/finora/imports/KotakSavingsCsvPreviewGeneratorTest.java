package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
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
 * Kotak Mahindra Bank's savings CSV export: one Amount column followed by a "Dr / Cr" column that
 * says which way the money moved, then a Balance column followed by a second "Dr / Cr" column that
 * says whether the balance is in credit.
 *
 * <p>The header set below is the one a real upload carried: it hashes to that upload's layout
 * fingerprint, which the first test pins. Every value in the rows is synthetic.
 *
 * <p>Before this fix the two same-named columns collided and the balance's marker won, so every
 * row read "CR" and every debit staged as INCOME. The balance chain caught it: each debit's gap
 * was twice its own amount.
 */
class KotakSavingsCsvPreviewGeneratorTest {

    private static final String PREAMBLE = ""
            + "\"\",\"\",\"Account Statement\"\n"
            + "\"SYNTHETIC CUSTOMER\",\"\",\"\",\"Account No.\",\"\",\"100000000001\"\n"
            + "\"\",\"\",\"\",\"Period\",\"\",\"01/01/2026 to 31/01/2026\"\n"
            + "\n";

    private static final String HEADER =
            "\"Sl. No.\",\"Transaction Date\",\"Value Date\",\"Description\",\"Chq /Ref No.\",\"Amount\",\"Dr / Cr\",\"Balance\",\"Dr / Cr\"\n";

    private static String row(int n, String date, String description, String ref, String amount, String marker,
                              String balance, String balanceMarker) {
        return "\"" + n + "\",\"" + date + "\",\"" + date.substring(0, 10) + "\",\"" + description + "\",\"" + ref
                + "\",\"" + amount + "\",\"" + marker + "\",\"" + balance + "\",\"" + balanceMarker + "\"\n";
    }

    private static final String CSV = PREAMBLE + HEADER
            + row(1, "01-01-2026 09:15:00", "UPI/SYNTHETIC SHOP/100000000001/Sent", "UPI-100000000001", "100.00", "DR", "900.00", "CR")
            + row(2, "01-01-2026 10:00:00", "SYNTHETIC CASH DEPOSIT", "", "500.00", "CR", "1,400.00", "CR")
            + row(3, "02-01-2026 11:30:45", "UPI/SYNTHETIC CAFE/100000000002/Sent", "UPI-100000000002", "250.50", "DR", "1,149.50", "CR")
            + row(4, "03-01-2026 18:05:10", "UPI/SYNTHETIC PERSON/100000000003/Received", "UPI-100000000003", "2,000.00", "CR", "3,149.50", "CR")
            + row(5, "04-01-2026 08:00:00", "SYNTHETIC CARD PAYMENT", "", "3,149.50", "DR", "0.00", "CR");

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
        return generator.generateWithContext(UUID.randomUUID(), "statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private static VerificationFinding finding(PreviewGenerator.CsvGenerationResult result, String rule) {
        return result.response().verification().findings().stream()
                .filter(f -> f.rule().equals(rule)).findFirst().orElseThrow();
    }

    @Test
    void headerSetIsTheProductionLayout() throws Exception {
        assertThat(generate(CSV).documentContext().buildFingerprint()).isEqualTo("FP-1-F3A7DCC5");
    }

    @Test
    void theAmountsMarkerDecidesTheDirection_notTheBalances() throws Exception {
        List<StagedRow> rows = generate(CSV).response().rows();

        assertThat(rows).extracting(StagedRow::type)
                .containsExactly("EXPENSE", "INCOME", "EXPENSE", "INCOME", "EXPENSE");
    }

    @Test
    void everyRowIsReadWithItsDateAmountBalanceAndDescription() throws Exception {
        List<StagedRow> rows = generate(CSV).response().rows();

        assertThat(rows).extracting(StagedRow::date).containsExactly(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 4));
        assertThat(rows).extracting(StagedRow::amount).usingElementComparator(BigDecimal::compareTo).containsExactly(
                new BigDecimal("100.00"), new BigDecimal("500.00"), new BigDecimal("250.50"),
                new BigDecimal("2000.00"), new BigDecimal("3149.50"));
        assertThat(rows).extracting(StagedRow::balanceAfter).usingElementComparator(BigDecimal::compareTo).containsExactly(
                new BigDecimal("900.00"), new BigDecimal("1400.00"), new BigDecimal("1149.50"),
                new BigDecimal("3149.50"), new BigDecimal("0.00"));
        assertThat(rows).extracting(StagedRow::description).containsExactly(
                "UPI/SYNTHETIC SHOP/100000000001/Sent", "SYNTHETIC CASH DEPOSIT",
                "UPI/SYNTHETIC CAFE/100000000002/Sent", "UPI/SYNTHETIC PERSON/100000000003/Received",
                "SYNTHETIC CARD PAYMENT");
    }

    @Test
    void theBalanceChainReconciles() throws Exception {
        var result = generate(CSV);

        VerificationFinding chain = finding(result, "BALANCE_CHAIN");
        assertThat(chain.outcome()).isEqualTo("VERIFIED");
        assertThat((List<?>) chain.details().get("discrepancies")).isEmpty();
        assertThat(finding(result, "ROW_ACCOUNTING").outcome()).isEqualTo("VERIFIED");
        assertThat(finding(result, "COLUMN_AMBIGUITY").outcome()).isEqualTo("VERIFIED");
    }

    @Test
    void aBalanceInDebitIsNegative() throws Exception {
        String overdrawn = HEADER
                + row(1, "05-01-2026 09:00:00", "SYNTHETIC CHARGE", "", "300.00", "DR", "200.00", "CR")
                + row(2, "05-01-2026 10:00:00", "SYNTHETIC CHARGE TWO", "", "500.00", "DR", "300.00", "DR")
                + row(3, "06-01-2026 10:00:00", "SYNTHETIC CREDIT", "", "1,000.00", "CR", "700.00", "Cr.");
        var result = generate(overdrawn);

        assertThat(result.response().rows()).extracting(StagedRow::balanceAfter)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(
                        new BigDecimal("200.00"), new BigDecimal("-300.00"), new BigDecimal("700.00"));
        assertThat(result.response().rows()).extracting(StagedRow::type).containsExactly("EXPENSE", "EXPENSE", "INCOME");
        assertThat(finding(result, "BALANCE_CHAIN").outcome()).isEqualTo("VERIFIED");
    }

    @Test
    void theOpeningAndClosingBalanceUseTheBalancesSignToo() throws Exception {
        String overdrawn = HEADER
                + row(1, "05-01-2026 09:00:00", "SYNTHETIC CHARGE", "", "300.00", "DR", "200.00", "CR")
                + row(2, "05-01-2026 10:00:00", "SYNTHETIC CHARGE TWO", "", "500.00", "DR", "300.00", "DR")
                + row(3, "06-01-2026 10:00:00", "SYNTHETIC CREDIT", "", "1,000.00", "CR", "700.00", "CR");
        var result = generate(overdrawn);

        var detected = result.response().detectedAccount();
        assertThat(detected.openingBalance()).isEqualByComparingTo("500.00");
        assertThat(detected.closingBalance()).isEqualByComparingTo("700.00");
        assertThat(finding(result, "STATEMENT_TOTALS").outcome()).isEqualTo("VERIFIED");
    }

    @Test
    void theTransactionDateIsUsed_notTheValueDate_andDatesAreDayFirst() throws Exception {
        String csv = HEADER
                + "\"1\",\"25-01-2026 23:59:58\",\"26-01-2026\",\"SYNTHETIC LATE NIGHT PAYMENT\",\"\",\"100.00\",\"DR\",\"900.00\",\"CR\"\n"
                + "\"2\",\"13-02-2026 08:00:00\",\"13-02-2026\",\"SYNTHETIC SALARY\",\"\",\"5,000.00\",\"CR\",\"5,900.00\",\"CR\"\n";

        assertThat(generate(csv).response().rows()).extracting(StagedRow::date)
                .containsExactly(LocalDate.of(2026, 1, 25), LocalDate.of(2026, 2, 13));
    }

    @Test
    void theOlderSpellingWithoutSpacesIsReadTheSameWay() throws Exception {
        String csv = "Sl. No.,Date,Description,Chq/Ref Number,Amount,Dr/Cr,Balance (INR),Dr/Cr\n"
                + "1,25-01-2026,SYNTHETIC SHOP,,100.00,DR,900.00,CR\n"
                + "2,26-01-2026,SYNTHETIC REFUND,,40.00,CR,940.00,CR\n";
        var result = generate(csv);

        assertThat(result.response().rows()).extracting(StagedRow::type).containsExactly("EXPENSE", "INCOME");
        assertThat(finding(result, "BALANCE_CHAIN").outcome()).isEqualTo("VERIFIED");
    }

    @Test
    void zipRowFilesOnlyTheRepeatedMarkerAfterABalanceUnderItsOwnKey() {
        CsvParser parser = new CsvParser();
        String[] header = {"Amount", "Dr / Cr", "Balance", "Dr / Cr"};

        Map<String, String> row = parser.zipRow(header, new String[]{"10.00", "DR", "90.00", "CR"});

        assertThat(row).containsEntry("Dr / Cr", "DR").containsEntry(CsvParser.BALANCE_DIRECTION_KEY, "CR");
    }

    @Test
    void zipRowHandlesTheBalancesMarkerComingFirst() {
        CsvParser parser = new CsvParser();
        String[] header = {"Balance", "Dr / Cr", "Amount", "Dr / Cr"};

        Map<String, String> row = parser.zipRow(header, new String[]{"90.00", "CR", "10.00", "DR"});

        assertThat(row).containsEntry("Dr / Cr", "DR").containsEntry(CsvParser.BALANCE_DIRECTION_KEY, "CR");
    }

    @Test
    void zipRowLeavesASingleMarkerColumnUnderItsOwnName() {
        CsvParser parser = new CsvParser();
        String[] header = {"Amount", "Balance", "Dr / Cr"};

        Map<String, String> row = parser.zipRow(header, new String[]{"10.00", "90.00", "DR"});

        assertThat(row).containsEntry("Dr / Cr", "DR").doesNotContainKey(CsvParser.BALANCE_DIRECTION_KEY);
    }
}
