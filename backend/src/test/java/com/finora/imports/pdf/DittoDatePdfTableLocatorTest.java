package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.DuplicateDetector;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.TransactionNormalizer;
import com.finora.service.RuleEngineService;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import com.finora.imports.TestAccountRepositories;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DITTO_DATE_INHERITED: on a layout that prints both a posting Date and a Value Date, a row whose
 * Date cell is blank inherits the Date of the row above it -- the bank printed the date once for a
 * run of same-day transactions, "ditto" style, and printed the value date on every row.
 *
 * <p>Measured on a real Standard Chartered export: 91 of 163 transaction rows print no Date at all,
 * only a Value Date. The row was admitted as an anchor on the strength of its Value Date (correct:
 * it is a transaction) and then, downstream, the normalizer's first-non-blank date lookup took the
 * Value Date as the transaction date. For the 26 rows whose value date fell on a weekend and whose
 * posting date was the following Monday, that stored the wrong date and put the rows out of order.
 *
 * <p>All text is synthetic per the Synthetic Fixture Policy.
 */
class DittoDatePdfTableLocatorTest {

    private static final float HEADER_Y = 100.0f;
    private static final float PITCH = 12.0f;
    private static final float DATE_X = 40.0f;
    private static final float VALUE_DATE_X = 110.0f;
    private static final float NARRATION_X = 180.0f;
    private static final float DEPOSIT_X = 380.0f;
    private static final float WITHDRAWAL_X = 450.0f;
    private static final float BALANCE_X = 520.0f;

    private static PositionedText run(String text, float x, float y) {
        return new PositionedText(text, x, y, 0);
    }

    private static List<PositionedText> header() {
        return List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Value Date", VALUE_DATE_X, HEADER_Y),
                run("Description", NARRATION_X, HEADER_Y),
                run("Deposit", DEPOSIT_X, HEADER_Y),
                run("Withdrawal", WITHDRAWAL_X, HEADER_Y),
                run("Balance", BALANCE_X, HEADER_Y));
    }

    private static List<Map<String, String>> rowsOf(List<PositionedText> runs, DocumentContext ctx) {
        return new PdfTableLocator().locate(runs, ctx).rows();
    }

    private static List<PositionedText> dittoSample() {
        List<PositionedText> runs = new ArrayList<>(header());
        float y = HEADER_Y + PITCH;
        runs.add(run("04 May 2026", DATE_X, y));
        runs.add(run("03 May 2026", VALUE_DATE_X, y));
        runs.add(run("UPI/000000000001/", NARRATION_X, y));
        runs.add(run("100.00", WITHDRAWAL_X, y));
        runs.add(run("900.00", BALANCE_X, y));
        y += PITCH;                                                  // Date printed once: ditto
        runs.add(run("03 May 2026", VALUE_DATE_X, y));
        runs.add(run("UPI/000000000002/", NARRATION_X, y));
        runs.add(run("50.00", WITHDRAWAL_X, y));
        runs.add(run("850.00", BALANCE_X, y));
        y += PITCH;
        runs.add(run("06 May 2026", DATE_X, y));
        runs.add(run("06 May 2026", VALUE_DATE_X, y));
        runs.add(run("UPI/000000000003/", NARRATION_X, y));
        runs.add(run("20.00", DEPOSIT_X, y));
        runs.add(run("870.00", BALANCE_X, y));
        return runs;
    }

    @Test
    void aBlankDateCellBesideAValueDate_inheritsThePreviousRowsDate() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(dittoSample(), ctx);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("Date", "04 May 2026").containsEntry("Value Date", "03 May 2026");
        assertThat(rows.get(1))
                .as("the ditto row: the posting date of the row above, its own value date kept")
                .containsEntry("Date", "04 May 2026").containsEntry("Value Date", "03 May 2026")
                .containsEntry("Withdrawal", "50.00").containsEntry("Balance", "850.00");
        assertThat(rows.get(2)).containsEntry("Date", "06 May 2026");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("DITTO_DATE_INHERITED");
    }

    @Test
    void theNormalizerThenStagesTheInheritedPostingDate_notTheValueDate() {
        List<Map<String, String>> rows = rowsOf(dittoSample(), new DocumentContext("PDF", "test"));
        TransactionNormalizer normalizer = normalizer();

        StagedRow staged = normalizer.normalize(UUID.randomUUID(), rows.get(1));

        assertThat(staged).isNotNull();
        assertThat(staged.date()).isEqualTo(LocalDate.of(2026, 5, 4));
        assertThat(staged.amount()).isEqualByComparingTo("50.00");
    }

    @Test
    void aBlankDateCell_withNoValueDateColumn_isStillNotAnAnchor() {
        // The rule lives inside anchor admission and only a value-date column can admit a row
        // with a blank Date; on an ordinary layout such a row stays a continuation exactly as today.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Description", NARRATION_X, HEADER_Y),
                run("Withdrawal", WITHDRAWAL_X, HEADER_Y),
                run("Balance", BALANCE_X, HEADER_Y)));
        float y = HEADER_Y + PITCH;
        runs.add(run("04 May 2026", DATE_X, y));
        runs.add(run("PAYEE ONE", NARRATION_X, y));
        runs.add(run("100.00", WITHDRAWAL_X, y));
        runs.add(run("900.00", BALANCE_X, y));
        y += PITCH;
        runs.add(run("WRAPPED NARRATION LINE", NARRATION_X, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("Description")).contains("WRAPPED NARRATION LINE");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("DITTO_DATE_INHERITED");
    }

    @Test
    void theFirstRowOfATable_withABlankDate_hasNothingToInherit_andIsLeftAlone() {
        List<PositionedText> runs = new ArrayList<>(header());
        float y = HEADER_Y + PITCH;
        runs.add(run("03 May 2026", VALUE_DATE_X, y));
        runs.add(run("UPI/000000000001/", NARRATION_X, y));
        runs.add(run("100.00", WITHDRAWAL_X, y));
        runs.add(run("900.00", BALANCE_X, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).doesNotContainKey("Date").containsEntry("Value Date", "03 May 2026");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("DITTO_DATE_INHERITED");
    }

    private static TransactionNormalizer normalizer() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        when(ruleEngineService.ruleSet(any())).thenReturn(List.of());
        return new TransactionNormalizer(categorizationService,
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()), ruleEngineService);
    }
}
