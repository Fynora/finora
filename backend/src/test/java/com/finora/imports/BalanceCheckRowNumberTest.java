package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The row number the balance check prints has to be the row a person counts to.
 *
 * <p>The review screen says "Row 13" beside a table of rows with no numbers on it, and the admin
 * trace repeats that number so a support conversation and an operator mean the same line. It used
 * to be a position along the validator's own walk, which is the displayed position only when the
 * statement is printed oldest first with its dates in order. Measured through both real staging
 * paths before the fix, 8 of the 20 cases below named a different row from the one at fault:
 *
 * <ul>
 *   <li>a newest-first CSV named the mirror-image row -- row 2 of 6 for a fault on row 5;</li>
 *   <li>a newest-first PDF did the same inside a same-day group, because the display sort keeps
 *       the group in document order while the walk runs it backwards;</li>
 *   <li>a PDF row whose date is out of sequence is moved by the display sort and was numbered by
 *       where it had been.</li>
 * </ul>
 *
 * <p>Every case goes through the real generator for its path rather than through the validator
 * alone, because the fault was never in one class: it was two components each numbering rows
 * correctly in an order the other did not use.
 */
class BalanceCheckRowNumberTest {

    /** date, amount as printed, balance as printed, label. All debits. */
    private record Line(String date, String amount, String balance, String label) {}

    // Chronological truth, opening 10000.00. C, D and E share a day.
    private static List<Line> sameDayStatement(String badLabel) {
        return misprint(List.of(
                new Line("01/07/2026", "100.00", "9900.00", "ROW A"),
                new Line("02/07/2026", "200.00", "9700.00", "ROW B"),
                new Line("03/07/2026", "300.00", "9400.00", "ROW C"),
                new Line("03/07/2026", "400.00", "9000.00", "ROW D"),
                new Line("03/07/2026", "500.00", "8500.00", "ROW E"),
                new Line("04/07/2026", "600.00", "7900.00", "ROW F"),
                new Line("05/07/2026", "700.00", "7200.00", "ROW G")), badLabel);
    }

    // Chronological truth, opening 10000.00. Every row on its own day.
    private static List<Line> distinctDayStatement(String badLabel) {
        return misprint(List.of(
                new Line("01/07/2026", "100.00", "9900.00", "ROW A"),
                new Line("02/07/2026", "200.00", "9700.00", "ROW B"),
                new Line("03/07/2026", "300.00", "9400.00", "ROW C"),
                new Line("04/07/2026", "400.00", "9000.00", "ROW D"),
                new Line("05/07/2026", "500.00", "8500.00", "ROW E"),
                new Line("06/07/2026", "600.00", "7900.00", "ROW F")), badLabel);
    }

    // Oldest first, but ROW H is printed third while carrying the first day's date -- a back-dated
    // entry. Balances chain along the printed order.
    private static List<Line> backDatedStatement(String badLabel) {
        return misprint(List.of(
                new Line("01/07/2026", "100.00", "9900.00", "ROW A"),
                new Line("02/07/2026", "200.00", "9700.00", "ROW B"),
                new Line("01/07/2026", "300.00", "9400.00", "ROW H"),
                new Line("04/07/2026", "400.00", "9000.00", "ROW F"),
                new Line("05/07/2026", "500.00", "8500.00", "ROW G")), badLabel);
    }

    /** The one wrong row: its amount is 10.00 too large, its printed balance untouched. So exactly
     *  one row disagrees, and it is known which. */
    private static List<Line> misprint(List<Line> rows, String badLabel) {
        List<Line> out = new ArrayList<>();
        for (Line row : rows) {
            out.add(row.label().equals(badLabel)
                    ? new Line(row.date(), new BigDecimal(row.amount()).add(new BigDecimal("10.00")).toPlainString(),
                            row.balance(), row.label())
                    : row);
        }
        return out;
    }

    private static List<Line> newestFirst(List<Line> chronological) {
        List<Line> reversed = new ArrayList<>(chronological);
        Collections.reverse(reversed);
        return reversed;
    }

    private static Stream<Arguments> statements() {
        return Stream.of(
                Arguments.of("oldest first, distinct days, fault on B", distinctDayStatement("ROW B"), "ROW B"),
                Arguments.of("oldest first, same-day group C/D/E, fault on C", sameDayStatement("ROW C"), "ROW C"),
                Arguments.of("oldest first, same-day group C/D/E, fault on E", sameDayStatement("ROW E"), "ROW E"),
                Arguments.of("newest first, distinct days, fault on B",
                        newestFirst(distinctDayStatement("ROW B")), "ROW B"),
                Arguments.of("newest first, same-day group C/D/E, fault on C",
                        newestFirst(sameDayStatement("ROW C")), "ROW C"),
                Arguments.of("newest first, same-day group C/D/E, fault on D (middle)",
                        newestFirst(sameDayStatement("ROW D")), "ROW D"),
                Arguments.of("newest first, same-day group C/D/E, fault on E",
                        newestFirst(sameDayStatement("ROW E")), "ROW E"),
                Arguments.of("newest first, same-day group C/D/E, fault on B (outside the group)",
                        newestFirst(sameDayStatement("ROW B")), "ROW B"),
                Arguments.of("oldest first, one back-dated row H, fault on B", backDatedStatement("ROW B"), "ROW B"),
                Arguments.of("oldest first, one back-dated row H, fault on H", backDatedStatement("ROW H"), "ROW H"));
    }

    @ParameterizedTest(name = "PDF: {0}")
    @MethodSource("statements")
    void aPdfStatementNamesTheRowThatIsActuallyAtFault(String name, List<Line> printed, String badLabel)
            throws Exception {
        assertTheNumberPointsAtTheFaultyRow(viaPdf(printed), badLabel);
    }

    @ParameterizedTest(name = "CSV: {0}")
    @MethodSource("statements")
    void aCsvStatementNamesTheRowThatIsActuallyAtFault(String name, List<Line> printed, String badLabel)
            throws Exception {
        assertTheNumberPointsAtTheFaultyRow(viaCsv(printed), badLabel);
    }

    @SuppressWarnings("unchecked")
    private static void assertTheNumberPointsAtTheFaultyRow(StagingResponse response, String badLabel) {
        List<StagedRow> shown = response.rows();
        int faultyRow = -1;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).description().endsWith(badLabel)) faultyRow = i;
        }
        assertThat(faultyRow).as("the fixture's faulty row has to be among the rows shown").isNotNegative();

        ImportDto.VerificationFinding chain = response.verification().findings().stream()
                .filter(finding -> BalanceChainValidator.RULE.equals(finding.rule()))
                .findFirst().orElseThrow();
        assertThat(chain.outcome()).isEqualTo("WARNING");

        List<ImportDto.RowDiscrepancy> discrepancies =
                (List<ImportDto.RowDiscrepancy>) chain.details().get("discrepancies");
        assertThat(discrepancies).hasSize(1);
        assertThat(discrepancies.get(0).rowIndex())
                .as("the number printed as \"Row N\" must be the faulty row's place among the rows shown")
                .isEqualTo(faultyRow);
        assertThat(discrepancies.get(0).actualBalance())
                .as("and the balance quoted beside it must be that row's own")
                .isEqualByComparingTo(shown.get(faultyRow).balanceAfter());

        // The admin trace prints the same number from the description that is kept after the
        // discrepancy's balances are dropped, so the two have to agree row for row -- and "the rows
        // before and after" has to mean the ones shown above and below it.
        List<Map<String, Object>> shapes = (List<Map<String, Object>>) chain.details().get("discrepancyShapes");
        assertThat(shapes).hasSize(1);
        assertThat(shapes.get(0).get("rowIndex")).isEqualTo(faultyRow);
        assertThat(shapes.get(0).get("sameDayAsPrevious")).isEqualTo(
                faultyRow > 0 && shown.get(faultyRow - 1).date().equals(shown.get(faultyRow).date()));
        assertThat(shapes.get(0).get("sameDayAsNext")).isEqualTo(
                faultyRow < shown.size() - 1 && shown.get(faultyRow + 1).date().equals(shown.get(faultyRow).date()));
    }

    // ---------------------------------------------------------------- the two real staging paths

    private static TransactionNormalizer normalizer() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(suggestion);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        return new TransactionNormalizer(categorizationService,
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()), TestRuleEngines.empty());
    }

    private static StagingResponse viaPdf(List<Line> printed) throws Exception {
        float[] column = {50f, 130f, 230f, 320f, 400f, 480f};
        List<String[]> table = new ArrayList<>();
        table.add(new String[] {"Date", "Instrument ID", "Amount(INR)", "Type (DR/CR)", "Balance", "Remarks"});
        for (Line row : printed) {
            table.add(new String[] {row.date(), "", row.amount(), "DR", row.balance(), "UPI/DR/" + row.label()});
        }

        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9f);
                float y = 770f;
                for (String[] cells : table) {
                    for (int i = 0; i < cells.length; i++) {
                        if (cells[i].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(column[i], y);
                        content.showText(cells[i]);
                        content.endText();
                    }
                    y -= 10f;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            bytes = out.toByteArray();
        }
        return PdfGeneratorTestSupport.generator().generate(UUID.randomUUID(), "synthetic.pdf", bytes);
    }

    private static StagingResponse viaCsv(List<Line> printed) throws Exception {
        StringBuilder csv = new StringBuilder("Date,Description,Debit,Credit,Balance\n");
        for (Line row : printed) {
            csv.append(row.date()).append(",UPI/DR/").append(row.label()).append(',').append(row.amount())
                    .append(",,").append(row.balance()).append('\n');
        }
        ImportVerifier verifier = new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(),
                new CreditCardStatementTotalsValidator(), new CreditCardFlowReconciliationValidator(),
                new DescriptionCorruptionValidator());
        PreviewGenerator generator = new PreviewGenerator(new CsvParser(), normalizer(),
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), verifier,
                TestRuleEngines.empty());
        return generator.generate(UUID.randomUUID(), "synthetic.csv",
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
