package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A CSV's numeric dates are read in one order for the whole file (Sid's decision, 2026-10-24):
 * proof decides, a contradiction refuses, and an unproven order whose two readings differ holds the
 * file for review. Every value here is invented.
 */
class CsvDateOrderTest {

    private static final String HEADER = "Date,Description,Debit,Credit,Balance\n";

    // ------------------------------------------------------------------ inference

    @Test
    void aFirstNumberOverTwelveProvesDayFirst() {
        assertThat(CsvDateOrder.infer(List.of("05/07/2026", "13/07/2026")))
                .isEqualTo(new CsvDateOrder.Decision(CsvDateOrder.Order.DAY_FIRST, true));
    }

    @Test
    void aSecondNumberOverTwelveProvesMonthFirst() {
        assertThat(CsvDateOrder.infer(List.of("07/05/2026", "07/13/2026")))
                .isEqualTo(new CsvDateOrder.Decision(CsvDateOrder.Order.MONTH_FIRST, true));
    }

    @Test
    void bothProofsInOneFileAreRefused() {
        assertThatThrownBy(() -> CsvDateOrder.infer(List.of("13/01/2026", "01/13/2026")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.IMPORT_DATE_ORDER_CONFLICT));
    }

    @Test
    void noProofReadsDayFirstAndSaysItIsUnproven() {
        assertThat(CsvDateOrder.infer(List.of("01/07/2026", "02/07/2026")))
                .isEqualTo(CsvDateOrder.Decision.UNPROVEN_DAY_FIRST);
        // Nothing numeric at all is the same: nothing contradicts day-first.
        assertThat(CsvDateOrder.infer(Arrays.asList("01 Jul 2026", null, "", "2026-07-01")))
                .isEqualTo(CsvDateOrder.Decision.UNPROVEN_DAY_FIRST);
    }

    @Test
    void onlyANumberThatCanBeADayAndNotAMonthIsProof() {
        // 13/13 and 32/01 are neither reading: they fail to parse later, and prove nothing here.
        assertThat(CsvDateOrder.infer(List.of("13/13/2026", "32/01/2026", "01/32/2026", "00/13/2026")))
                .isEqualTo(CsvDateOrder.Decision.UNPROVEN_DAY_FIRST);
    }

    @Test
    void proofIsReadThroughAnyTimeSeparatorAndYearWidth() {
        assertThat(CsvDateOrder.infer(List.of("30-06-26 14:18", "01-07-26")).order())
                .isEqualTo(CsvDateOrder.Order.DAY_FIRST);
        assertThat(CsvDateOrder.infer(List.of("06.30.2026 14:18:05.123")).order())
                .isEqualTo(CsvDateOrder.Order.MONTH_FIRST);
        // Unpadded, as a spreadsheet prints it.
        assertThat(CsvDateOrder.infer(List.of("1/7/2026", "15/7/2026")).order())
                .isEqualTo(CsvDateOrder.Order.DAY_FIRST);
    }

    @Test
    void aDateThatReadsTheSameEitherWayIsNotUnsettled() {
        CsvDateOrder.Decision unproven = CsvDateOrder.Decision.UNPROVEN_DAY_FIRST;
        assertThat(unproven.leavesUnsettled("05/05/2026")).isFalse();
        assertThat(unproven.leavesUnsettled("07/01/2026")).isTrue();
        assertThat(unproven.leavesUnsettled("01 Jul 2026")).isFalse();
        assertThat(new CsvDateOrder.Decision(CsvDateOrder.Order.DAY_FIRST, true).leavesUnsettled("07/01/2026")).isFalse();
    }

    // ------------------------------------------------------------------ parsing in the decided order

    @Test
    void numericDatesAreReadInTheGivenOrderOnly() {
        assertThat(CsvParser.parseDate("07/01/2026", CsvDateOrder.Order.DAY_FIRST)).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(CsvParser.parseDate("07/01/2026", CsvDateOrder.Order.MONTH_FIRST)).isEqualTo(LocalDate.of(2026, 7, 1));
        // The other order is never tried as a fallback: per-cell fallback is what mixed files up.
        assertThat(CsvParser.parseDate("07/13/2026", CsvDateOrder.Order.DAY_FIRST)).isNull();
        assertThat(CsvParser.parseDate("13/07/2026", CsvDateOrder.Order.MONTH_FIRST)).isNull();
        // Impossible dates are refused, never clamped.
        assertThat(CsvParser.parseDate("31/02/2026", CsvDateOrder.Order.DAY_FIRST)).isNull();
        assertThat(CsvParser.parseDate("02/30/2026", CsvDateOrder.Order.MONTH_FIRST)).isNull();
        assertThat(CsvParser.parseDate("29/02/2028", CsvDateOrder.Order.DAY_FIRST)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(CsvParser.parseDate("00/07/2026", CsvDateOrder.Order.DAY_FIRST)).isNull();
    }

    @Test
    void theShapesASpreadsheetExportPrintsAreRead() {
        CsvDateOrder.Order dayFirst = CsvDateOrder.Order.DAY_FIRST;
        assertThat(CsvParser.parseDate("1/7/2026", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("15/7/2026", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(CsvParser.parseDate("3/8/2026", dayFirst)).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(CsvParser.parseDate("01 July 2026", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("15 September 2026", dayFirst)).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(CsvParser.parseDate("1 july 2026", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("2026-07-01T10:00:00", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("2026-07-01T10:00:00Z", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("2026-07-01T10:00:00.250+05:30", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("2026/07/01", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("20260701", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("30/06/2026 14:18:05.123", dayFirst)).isEqualTo(LocalDate.of(2026, 6, 30));
        // What already read keeps reading, the same way.
        assertThat(CsvParser.parseDate("30/06/2026| 14:18", dayFirst)).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(CsvParser.parseDate("01 Jul 2026", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("July29, 2026", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(CsvParser.parseDate("15 Sep '26", dayFirst)).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(CsvParser.parseDate("28.07.2026", dayFirst)).isEqualTo(LocalDate.of(2026, 7, 28));
    }

    @Test
    void numbersThatAreNotDatesStayUnread() {
        CsvDateOrder.Order dayFirst = CsvDateOrder.Order.DAY_FIRST;
        assertThat(CsvParser.parseDate("20261301", dayFirst)).isNull();   // month 13
        assertThat(CsvParser.parseDate("12345678", dayFirst)).isNull();   // year 1234
        assertThat(CsvParser.parseDate("2026/07/01/02", dayFirst)).isNull();
        assertThat(CsvParser.parseDate("01/07-2026", dayFirst)).isNull(); // two different separators
        assertThat(CsvParser.parseDate("1500.00", dayFirst)).isNull();
        assertThat(CsvParser.parseDate("", dayFirst)).isNull();
        assertThat(CsvParser.parseDate("Opening Balance", dayFirst)).isNull();
    }

    @Test
    void theSharedPerCellParserIsUnchangedForTheseShapes() {
        // The PDF table locator uses parseDate(String) to decide where a row starts; an eight-digit
        // number read as a date there would start rows that are not there.
        assertThat(CsvParser.parseDate("20260701")).isNull();
        assertThat(CsvParser.parseDate("1/7/2026")).isNull();
        assertThat(CsvParser.parseDate("01 July 2026")).isNull();
        assertThat(CsvParser.parseDate("07/13/2026")).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(CsvParser.parseDate("07/01/2026", null)).isEqualTo(CsvParser.parseDate("07/01/2026"));
    }

    // ------------------------------------------------------------------ through CSV staging

    @Test
    void aMonthFirstFileWithProofImportsWithItsRealDates() throws Exception {
        String csv = HEADER
                + "01/05/2026,SYNTHETIC RENT,1000.00,,9000.00\n"
                + "01/13/2026,SYNTHETIC GROCERY,500.00,,8500.00\n"
                + "02/03/2026,SYNTHETIC FUEL,200.00,,8300.00\n"
                + "02/20/2026,SYNTHETIC SALARY,,5000.00,13300.00\n";

        StagingResponse r = stage(csv);

        assertThat(r.rows()).extracting(StagedRow::date).containsExactly(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 13), LocalDate.of(2026, 2, 3), LocalDate.of(2026, 2, 20));
        assertThat(finding(r, BalanceChainValidator.RULE).outcome()).isEqualTo("VERIFIED");
        assertThat(finding(r, ColumnAmbiguityValidator.RULE).outcome()).isEqualTo("VERIFIED");
        assertThat(hold(r).hold()).isFalse();
    }

    @Test
    void aMonthFirstFileWithNoProofIsHeldForReviewNotImportedAsAGuess() throws Exception {
        String csv = HEADER
                + "07/01/2026,SYNTHETIC RENT,1000.00,,9000.00\n"
                + "07/02/2026,SYNTHETIC GROCERY,500.00,,8500.00\n"
                + "07/05/2026,SYNTHETIC FUEL,200.00,,8300.00\n"
                + "07/09/2026,SYNTHETIC SALARY,,5000.00,13300.00\n";

        StagingResponse r = stage(csv);

        // Read day-first, as nothing in the corpus is month-first ...
        assertThat(r.rows()).extracting(StagedRow::date).containsExactly(
                LocalDate.of(2026, 1, 7), LocalDate.of(2026, 2, 7), LocalDate.of(2026, 5, 7), LocalDate.of(2026, 9, 7));
        // ... but every row whose date the other reading would change is reported, by where it is
        // shown, and the file is held.
        ImportDto.VerificationFinding ambiguity = finding(r, ColumnAmbiguityValidator.RULE);
        assertThat(ambiguity.outcome()).isEqualTo("WARNING");
        assertThat(ambiguity.details().get("ambiguousRows")).isEqualTo(4);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ambiguity.details().get("ambiguities");
        assertThat(rows).extracting(m -> m.get("kind")).containsOnly(ColumnAmbiguityValidator.DATE_ORDER_NOT_SETTLED);
        assertThat(rows).extracting(m -> m.get("rowIndex")).containsExactly(0, 1, 2, 3);
        assertThat(rows).extracting(m -> m.get("column")).containsOnly("Date");
        assertThat(rows).extracting(m -> m.get("value"))
                .containsExactly("07/01/2026", "07/02/2026", "07/05/2026", "07/09/2026");
        HoldDecision decision = hold(r);
        assertThat(decision.hold()).isTrue();
        assertThat(decision.categories()).containsExactly(TrustPredicate.Category.COLUMN_AMBIGUITY);
    }

    @Test
    void anUnprovenFileIsNotHeldWhenEveryDateReadsTheSameEitherWay() throws Exception {
        String csv = HEADER
                + "01/01/2026,SYNTHETIC RENT,1000.00,,9000.00\n"
                + "02/02/2026,SYNTHETIC GROCERY,500.00,,8500.00\n";

        StagingResponse r = stage(csv);

        assertThat(r.rows()).extracting(StagedRow::date).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 2));
        assertThat(finding(r, ColumnAmbiguityValidator.RULE).outcome()).isEqualTo("VERIFIED");
        assertThat(hold(r).hold()).isFalse();
    }

    @Test
    void aDayFirstFileWithProofIsNotHeld() throws Exception {
        String csv = HEADER
                + "07/01/2026,SYNTHETIC RENT,1000.00,,9000.00\n"
                + "15/01/2026,SYNTHETIC GROCERY,500.00,,8500.00\n";

        StagingResponse r = stage(csv);

        assertThat(r.rows()).extracting(StagedRow::date).containsExactly(LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 15));
        assertThat(finding(r, ColumnAmbiguityValidator.RULE).outcome()).isEqualTo("VERIFIED");
        assertThat(hold(r).hold()).isFalse();
    }

    @Test
    void aFileWhoseDatesProveBothOrdersIsRefused() {
        String csv = HEADER
                + "13/01/2026,SYNTHETIC RENT,1000.00,,9000.00\n"
                + "01/14/2026,SYNTHETIC GROCERY,500.00,,8500.00\n";

        assertThatThrownBy(() -> stage(csv))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.IMPORT_DATE_ORDER_CONFLICT));
    }

    @Test
    void anUnpaddedSpreadsheetFileImportsInsteadOfFailingWhole() throws Exception {
        String csv = HEADER
                + "1/7/2026,SYNTHETIC RENT,1000.00,,9000.00\n"
                + "15/7/2026,SYNTHETIC GROCERY,500.00,,8500.00\n"
                + "3/8/2026,SYNTHETIC SALARY,,5000.00,13500.00\n";

        StagingResponse r = stage(csv);

        assertThat(r.rows()).extracting(StagedRow::date).containsExactly(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 3));
        assertThat(r.unparseableRows()).isEmpty();
        // 15/7 proves day-first, so nothing is unsettled.
        assertThat(hold(r).hold()).isFalse();
    }

    @Test
    void anUnreadableDateIsReportedWithTheReasonForTheOrderItWasReadIn() throws Exception {
        // 07/13 cannot be day-first, and this file is day-first by proof (15/07), so it is a date the
        // file cannot have: refused as a contradiction rather than read month-first on its own.
        String contradictory = HEADER
                + "15/07/2026,SYNTHETIC RENT,1000.00,,9000.00\n"
                + "07/13/2026,SYNTHETIC GROCERY,500.00,,8500.00\n";
        assertThatThrownBy(() -> stage(contradictory)).isInstanceOf(ApiException.class);

        // 31/02 is no date in either order: that row alone is unreadable, and says so.
        String impossible = HEADER
                + "15/07/2026,SYNTHETIC RENT,1000.00,,9000.00\n"
                + "31/02/2026,SYNTHETIC GROCERY,500.00,,8500.00\n";
        StagingResponse r = stage(impossible);
        assertThat(r.rows()).extracting(StagedRow::date).containsExactly(LocalDate.of(2026, 7, 15));
        assertThat(r.unparseableRows()).singleElement()
                .satisfies(u -> assertThat(u.reason()).contains("31/02/2026"));
    }

    @Test
    void aTwoDigitNinetyNineIsNineteenNinetyNineNotTwentyNinetyNine() throws Exception {
        String csv = HEADER
                + "01/07/26,SYNTHETIC ONE,100.00,,9900.00\n"
                + "02/07/99,SYNTHETIC TWO,100.00,,9800.00\n";

        assertThat(stage(csv).rows()).extracting(StagedRow::date)
                .containsExactlyInAnyOrder(LocalDate.of(2026, 7, 1), LocalDate.of(1999, 7, 2));
    }

    @Test
    void aMonthFirstStatementPeriodAndDueDateAreReadInTheFileOrder() throws Exception {
        String csv = "Date,Description,Debit,Credit,Balance,Statement Period,Due Date\n"
                + "07/01/2026,SYNTHETIC RENT,1000.00,,9000.00,07/01/2026 to 07/31/2026,08/05/2026\n"
                + "07/13/2026,SYNTHETIC GROCERY,500.00,,8500.00,07/01/2026 to 07/31/2026,08/05/2026\n";

        var detected = stage(csv).detectedAccount();

        assertThat(detected.statementPeriodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(detected.statementPeriodEnd()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void aPeriodPrintedAboveTheTableSettlesAnOrderTheRowsLeaveOpen() throws Exception {
        // A real Kotak export prints its period above the table; a statement of 1-4 January has no
        // transaction day over 12, and the period's "31/01/2026" says day-first.
        String csv = "\"\",\"Account Statement\"\n"
                + "\"\",\"Period\",\"01/01/2026 to 31/01/2026\"\n"
                + "\n"
                + HEADER
                + "02/01/2026,SYNTHETIC RENT,1000.00,,9000.00\n"
                + "04/01/2026,SYNTHETIC GROCERY,500.00,,8500.00\n";

        StagingResponse r = stage(csv);

        assertThat(r.rows()).extracting(StagedRow::date).containsExactly(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 4));
        assertThat(finding(r, ColumnAmbiguityValidator.RULE).outcome()).isEqualTo("VERIFIED");
        assertThat(hold(r).hold()).isFalse();
    }

    @Test
    void anotherDateColumnSettlesItToo() throws Exception {
        String csv = "Txn Date,Value Date,Description,Debit,Credit,Balance\n"
                + "02/01/2026,13/01/2026,SYNTHETIC RENT,1000.00,,9000.00\n";

        StagingResponse r = stage(csv);

        assertThat(r.rows()).extracting(StagedRow::date).containsExactly(LocalDate.of(2026, 1, 2));
        assertThat(hold(r).hold()).isFalse();
    }

    @Test
    void textAboveTheTableThatContradictsItselfSettlesNothingAndRefusesNothing() {
        // A portal's own stamp may come from another system than the rows: no refusal, still unproven.
        assertThat(CsvDateOrder.infer(List.of("02/01/2026"), List.of("31/01/2026", "01/31/2026")))
                .isEqualTo(CsvDateOrder.Decision.UNPROVEN_DAY_FIRST);
        // And the rows' own proof wins over anything elsewhere.
        assertThat(CsvDateOrder.infer(List.of("01/13/2026"), List.of("31/01/2026")).order())
                .isEqualTo(CsvDateOrder.Order.MONTH_FIRST);
    }

    @Test
    void onlyFourDigitYearDatesAreTakenFromFreeText() {
        assertThat(CsvDateOrder.datesIn("Period 01/01/2026 to 31/01/2026")).containsExactly("01/01/2026", "31/01/2026");
        assertThat(CsvDateOrder.datesIn("Version 1.2.26, ref 13/01/26")).isEmpty();
        assertThat(CsvDateOrder.datesIn("A/c 100000000001")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static ImportDto.VerificationFinding finding(StagingResponse r, String rule) {
        return r.verification().findings().stream().filter(f -> rule.equals(f.rule())).findFirst().orElseThrow();
    }

    private static HoldDecision hold(StagingResponse r) {
        return TrustPredicate.evaluate(List.of(r.verification()), List.of(), LocalDate.of(2026, 10, 24));
    }

    private static StagingResponse stage(String csv) throws Exception {
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
        return generator.generate(UUID.randomUUID(), "statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }
}
