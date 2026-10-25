package com.finora.imports;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.trust.TrustPredicate;
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
 * A CSV row with a date and a balance but an amount that could not be read.
 *
 * <p>The normaliser falls back to the balance for such a row's amount, and that figure used to feed
 * the opening/closing derivation as money moving: a first row reading "abc" with a balance of
 * 9,700.00 gave an opening balance of 19,400.00, shown to the reviewer of the held file. Now the
 * row's balance is kept out of the derivation, the opening balance is not established when that row
 * may come first, and the file is held because the row's amount is unknown (Sid, 2026-10-24).
 * Every value here is invented; every date is past the 12th, so the date order is proven.
 */
class CsvUnreadableAmountBalanceTest {

    private static final String HEADER = "Date,Description,Amount,Balance\n";

    @Test
    void anUnreadFirstRowLeavesTheOpeningBalanceUnknownNotDoubled() throws Exception {
        StagingResponse r = stage(HEADER
                + "13/07/2026,SYNTHETIC UNREAD,abc,9700.00\n"
                + "14/07/2026,SYNTHETIC SHOP,1500.00,8200.00\n"
                + "15/07/2026,SYNTHETIC CAFE,200.00,8000.00\n");

        assertThat(r.detectedAccount().openingBalance()).isNull();
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("8000.00");
        assertHeldForTheUnreadAmount(r);
    }

    @Test
    void theSameHoldsForAnUnreadFirstRowInASplitDebitCreditLayout() throws Exception {
        StagingResponse r = stage("Date,Description,Debit,Credit,Balance\n"
                + "13/07/2026,SYNTHETIC UNREAD,abc,,9700.00\n"
                + "14/07/2026,SYNTHETIC SHOP,1500.00,,8200.00\n");

        assertThat(r.detectedAccount().openingBalance()).isNull();
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("8200.00");
        assertHeldForTheUnreadAmount(r);
    }

    @Test
    void anUnreadRowOnTheFirstDayMayBeFirst_soTheOpeningIsUnknown() throws Exception {
        StagingResponse r = stage(HEADER
                + "13/07/2026,SYNTHETIC SHOP,300.00,9700.00\n"
                + "13/07/2026,SYNTHETIC UNREAD,abc,9500.00\n"
                + "14/07/2026,SYNTHETIC CAFE,200.00,9300.00\n");

        assertThat(r.detectedAccount().openingBalance()).isNull();
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("9300.00");
    }

    @Test
    void aPrintedOpeningBalanceLineStillSettlesTheOpening() throws Exception {
        StagingResponse r = stage(HEADER
                + "13/07/2026,OPENING BALANCE,,10000.00\n"
                + "13/07/2026,SYNTHETIC UNREAD,abc,9700.00\n"
                + "14/07/2026,SYNTHETIC SHOP,1500.00,8200.00\n");

        assertThat(r.detectedAccount().openingBalance()).isEqualByComparingTo("10000.00");
        assertHeldForTheUnreadAmount(r);
    }

    @Test
    void anUnreadMiddleRowLeavesBothEndsAsTheyWere() throws Exception {
        StagingResponse r = stage(HEADER
                + "13/07/2026,SYNTHETIC SHOP,300.00,9700.00\n"
                + "14/07/2026,SYNTHETIC UNREAD,abc,8200.00\n"
                + "15/07/2026,SYNTHETIC CAFE,200.00,8000.00\n");

        assertThat(r.detectedAccount().openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("8000.00");
        assertHeldForTheUnreadAmount(r);
    }

    @Test
    void anUnreadLastRowStillGivesTheClosingBalanceByItsOwnBalance() throws Exception {
        StagingResponse r = stage(HEADER
                + "13/07/2026,SYNTHETIC SHOP,300.00,9700.00\n"
                + "14/07/2026,SYNTHETIC CAFE,1500.00,8200.00\n"
                + "15/07/2026,SYNTHETIC UNREAD,abc,8000.00\n");

        assertThat(r.detectedAccount().openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("8000.00");
        assertHeldForTheUnreadAmount(r);
    }

    @Test
    void anUnreadRowThatCannotBePlacedLastLeavesTheClosingUnknown() throws Exception {
        // On the same day as the last readable row: either may be last.
        StagingResponse sameDay = stage(HEADER
                + "13/07/2026,SYNTHETIC SHOP,300.00,9700.00\n"
                + "14/07/2026,SYNTHETIC CAFE,1500.00,8200.00\n"
                + "14/07/2026,SYNTHETIC UNREAD,abc,8000.00\n");
        assertThat(sameDay.detectedAccount().closingBalance()).isNull();
        assertThat(sameDay.detectedAccount().openingBalance()).isEqualByComparingTo("10000.00");

        // Two unread rows on the last day: their order is unknown.
        StagingResponse twoLast = stage(HEADER
                + "13/07/2026,SYNTHETIC SHOP,300.00,9700.00\n"
                + "15/07/2026,SYNTHETIC UNREAD ONE,abc,9000.00\n"
                + "15/07/2026,SYNTHETIC UNREAD TWO,xyz,8800.00\n");
        assertThat(twoLast.detectedAccount().closingBalance()).isNull();

        // No balance printed on the last, unread row.
        StagingResponse noBalance = stage(HEADER
                + "13/07/2026,SYNTHETIC SHOP,300.00,9700.00\n"
                + "15/07/2026,SYNTHETIC UNREAD,abc,\n");
        assertThat(noBalance.detectedAccount().closingBalance()).isNull();
    }

    @Test
    void everyRowUnreadLeavesTheOpeningUnknown_theLastDayStillGivesTheClosing() throws Exception {
        StagingResponse r = stage(HEADER
                + "13/07/2026,SYNTHETIC UNREAD ONE,abc,9700.00\n"
                + "14/07/2026,SYNTHETIC UNREAD TWO,xyz,9500.00\n");

        assertThat(r.rows()).isEmpty();
        assertThat(r.detectedAccount().openingBalance()).isNull();
        // The balance after the last transaction is what that row printed, whatever it moved.
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("9500.00");
    }

    @Test
    void aFileWithEveryAmountReadIsUnchanged() throws Exception {
        StagingResponse r = stage(HEADER
                + "13/07/2026,SYNTHETIC SHOP,300.00,9700.00\n"
                + "14/07/2026,SYNTHETIC CAFE,1500.00,8200.00\n");

        assertThat(r.detectedAccount().openingBalance()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo(new BigDecimal("8200.00"));
        assertThat(TrustPredicate.evaluate(List.of(r.verification()), List.of(), LocalDate.of(2026, 10, 24)).hold())
                .isFalse();
    }

    private static void assertHeldForTheUnreadAmount(StagingResponse r) {
        var decision = TrustPredicate.evaluate(List.of(r.verification()), List.of(), LocalDate.of(2026, 10, 24));
        assertThat(decision.hold()).isTrue();
        assertThat(decision.reasons()).contains("A transaction's amount could not be read");
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
