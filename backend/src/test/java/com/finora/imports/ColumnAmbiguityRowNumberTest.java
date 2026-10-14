package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The row number the column-ambiguity check prints has to be the row a person counts to.
 *
 * <p>The review screen says "Row 4" beside a table of rows with no numbers on it. The number used to
 * be the row's place among the rows the table locator found, and the screen shows a different list:
 * only the rows that staged, sorted by date. Measured through the real PDF path before the fix, 6 of
 * the first 10 cases below named a row other than the ambiguous one:
 *
 * <ul>
 *   <li>a statement that prints its own opening-balance line, or any row whose amount could not be
 *       read, ahead of the ambiguous row: that line is located and never shown, so every row after
 *       it was numbered one too high;</li>
 *   <li>a newest-first statement: the display sort turns it over, and the number still counted from
 *       the top of the page as printed;</li>
 *   <li>a back-dated entry: the display sort moves it, and it and the rows it passes were numbered
 *       by where they had been.</li>
 * </ul>
 *
 * <p>Every case goes through the real generator rather than the validator alone, because the fault
 * was never in one class: the validator numbered the located rows correctly, and the generator
 * showed a list in which those numbers meant something else. PDF only, because the CSV path hands
 * this rule no located rows and so it never names a row there. Same shape as {@link
 * BalanceCheckRowNumberTest}, which pins the same promise for the balance check.
 */
class ColumnAmbiguityRowNumberTest {

    /** date, narration, withdrawal, deposit, balance -- each exactly as printed. */
    private record Line(String date, String narration, String withdrawal, String deposit, String balance) {}

    private static Line debit(String date, String label, String amount, String balance) {
        return new Line(date, "UPI/DR/ROW " + label, amount, "", balance);
    }

    // Chronological, opening 10000.00. Every row on its own day.
    private static List<Line> distinctDays() {
        return List.of(
                debit("01/07/2026", "A", "100.00", "9900.00"),
                debit("02/07/2026", "B", "200.00", "9700.00"),
                debit("03/07/2026", "C", "300.00", "9400.00"),
                debit("04/07/2026", "D", "400.00", "9000.00"),
                debit("05/07/2026", "E", "500.00", "8500.00"),
                debit("06/07/2026", "F", "600.00", "7900.00"));
    }

    // Chronological, opening 10000.00. C, D and E share a day.
    private static List<Line> sameDayGroup() {
        return List.of(
                debit("01/07/2026", "A", "100.00", "9900.00"),
                debit("02/07/2026", "B", "200.00", "9700.00"),
                debit("03/07/2026", "C", "300.00", "9400.00"),
                debit("03/07/2026", "D", "400.00", "9000.00"),
                debit("03/07/2026", "E", "500.00", "8500.00"),
                debit("04/07/2026", "F", "600.00", "7900.00"));
    }

    // Oldest first, but ROW H is printed third while carrying the first day's date -- a back-dated
    // entry.
    private static List<Line> backDated() {
        return List.of(
                debit("01/07/2026", "A", "100.00", "9900.00"),
                debit("02/07/2026", "B", "200.00", "9700.00"),
                debit("01/07/2026", "H", "300.00", "9400.00"),
                debit("04/07/2026", "D", "400.00", "9000.00"),
                debit("05/07/2026", "E", "500.00", "8500.00"));
    }

    /** The statement's own opening-balance line: a date and a balance, no money moved. Located by
     *  the table locator like any other row, and never staged. */
    private static final Line OPENING_BALANCE_LINE = new Line("01/07/2026", "OPENING BALANCE", "", "", "10000.00");

    /** A row whose amount cannot be read as a number. Located, set aside as unparseable, not shown. */
    private static final Line UNREADABLE_AMOUNT_ROW = new Line("02/07/2026", "UPI/DR/ROW X", "150/-", "", "9700.00");

    /** Both direction columns claim money on the named rows -- the row can be read either way. */
    private static List<Line> bothDirections(List<Line> rows, String... labels) {
        List<Line> out = new ArrayList<>();
        for (Line row : rows) {
            out.add(isOneOf(row, labels)
                    ? new Line(row.date(), row.narration(), row.withdrawal(), "50.00", row.balance()) : row);
        }
        return out;
    }

    /** Two amounts in the named row's deposit cell, beside a withdrawal that still reads -- so the
     *  row stages and the second kind of ambiguity is reported on a row that is shown. */
    private static List<Line> twoAmountsInTheDepositCell(List<Line> rows, String label) {
        List<Line> out = new ArrayList<>();
        for (Line row : rows) {
            out.add(isOneOf(row, label)
                    ? new Line(row.date(), row.narration(), row.withdrawal(), "0.00 25.00", row.balance()) : row);
        }
        return out;
    }

    private static boolean isOneOf(Line row, String... labels) {
        for (String label : labels) {
            if (row.narration().endsWith("ROW " + label)) return true;
        }
        return false;
    }

    private static List<Line> with(Line extra, int at, List<Line> rows) {
        List<Line> out = new ArrayList<>(rows);
        out.add(at, extra);
        return out;
    }

    private static List<Line> newestFirst(List<Line> chronological) {
        List<Line> reversed = new ArrayList<>(chronological);
        Collections.reverse(reversed);
        return reversed;
    }

    private static Stream<Arguments> statements() {
        return Stream.of(
                // The first ten are the cases measured before the fix: 4 right, 6 wrong.
                Arguments.of("oldest first, dates in order, ambiguous C",
                        bothDirections(distinctDays(), "C"), List.of("C")),
                Arguments.of("oldest first, dates in order, ambiguous A (the first row)",
                        bothDirections(distinctDays(), "A"), List.of("A")),
                Arguments.of("the opening-balance line printed first, ambiguous C",
                        with(OPENING_BALANCE_LINE, 0, bothDirections(distinctDays(), "C")), List.of("C")),
                Arguments.of("a row with an unreadable amount printed before the ambiguous C",
                        with(UNREADABLE_AMOUNT_ROW, 2, bothDirections(distinctDays(), "C")), List.of("C")),
                Arguments.of("a row with an unreadable amount printed after the ambiguous C",
                        with(UNREADABLE_AMOUNT_ROW, 4, bothDirections(distinctDays(), "C")), List.of("C")),
                Arguments.of("newest first, ambiguous C",
                        newestFirst(bothDirections(distinctDays(), "C")), List.of("C")),
                Arguments.of("newest first, ambiguous F (printed first, shown last)",
                        newestFirst(bothDirections(distinctDays(), "F")), List.of("F")),
                Arguments.of("one back-dated row H, ambiguous B (H is moved above it)",
                        bothDirections(backDated(), "B"), List.of("B")),
                Arguments.of("one back-dated row H, ambiguous H (the row that moves)",
                        bothDirections(backDated(), "H"), List.of("H")),
                Arguments.of("one back-dated row H, ambiguous D (printed after H)",
                        bothDirections(backDated(), "D"), List.of("D")),
                // Added with the fix.
                Arguments.of("newest first, same-day group C/D/E, ambiguous C",
                        newestFirst(bothDirections(sameDayGroup(), "C")), List.of("C")),
                Arguments.of("newest first, same-day group C/D/E, ambiguous E",
                        newestFirst(bothDirections(sameDayGroup(), "E")), List.of("E")),
                Arguments.of("newest first behind an opening-balance line and an unreadable row, ambiguous B and E",
                        with(OPENING_BALANCE_LINE, 0,
                                with(UNREADABLE_AMOUNT_ROW, 3, newestFirst(bothDirections(distinctDays(), "B", "E")))),
                        List.of("B", "E")),
                Arguments.of("newest first, two amounts in one cell on C",
                        newestFirst(twoAmountsInTheDepositCell(distinctDays(), "C")), List.of("C")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statements")
    @SuppressWarnings("unchecked")
    void theNumberNamesTheRowThatIsActuallyAmbiguous(String name, List<Line> printed, List<String> ambiguousLabels)
            throws Exception {
        StagingResponse response = viaPdf(printed);
        List<StagedRow> shown = response.rows();

        ImportDto.VerificationFinding finding = response.verification().findings().stream()
                .filter(f -> ColumnAmbiguityValidator.RULE.equals(f.rule()))
                .findFirst().orElseThrow();
        assertThat(finding.outcome()).isEqualTo("WARNING");
        // The generator always says where rows are shown, so its findings say so too, and a saved
        // review of this statement is served back with these same numbers, not converted again.
        assertThat(finding.details())
                .containsEntry(ColumnAmbiguityValidator.ROW_INDEX_BASIS, ColumnAmbiguityValidator.ROWS_SHOWN);
        assertThat(ColumnAmbiguityValidator.renumberStored(finding, shown)).isSameAs(finding);

        List<Map<String, Object>> ambiguities = (List<Map<String, Object>>) finding.details().get("ambiguities");
        assertThat(ambiguities).hasSameSizeAs(ambiguousLabels);

        // ambiguousLabels is given oldest first, which is the order the rows are shown in, so the
        // nth finding has to be about the nth label: the list is read down the page beside the table.
        for (int n = 0; n < ambiguities.size(); n++) {
            String label = "ROW " + ambiguousLabels.get(n);
            int ambiguousRow = -1;
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).description().endsWith(label)) ambiguousRow = i;
            }
            assertThat(ambiguousRow).as("the fixture's ambiguous %s has to be among the rows shown", label)
                    .isNotNegative();
            assertThat(ambiguities.get(n).get("rowIndex"))
                    .as("the number printed as \"Row N\" must be %s's place among the rows shown", label)
                    .isEqualTo(ambiguousRow);
        }
    }

    /**
     * A composite statement numbers each account's rows on its own, because each section is its own
     * review list. The second account is printed newest first behind its opening-balance line, so
     * its located rows and its shown rows disagree twice over -- and the first account's rows must
     * not shift its numbers.
     */
    @Test
    @SuppressWarnings("unchecked")
    void eachAccountOfACompositeStatementIsNumberedAlongItsOwnRows() throws Exception {
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] {"Composite Statement"});
        lines.add(new String[] {"SAVINGS ACCOUNT  100-111111-002"});
        lines.add(new String[] {"Date", "Narration", "Withdrawal", "Deposit", "Balance"});
        lines.add(new String[] {"01/07/2026", "UPI/DR/ROW A", "100.00", "", "9900.00"});
        lines.add(new String[] {"02/07/2026", "UPI/DR/ROW B", "200.00", "50.00", "9700.00"});
        lines.add(new String[] {"03/07/2026", "UPI/DR/ROW C", "300.00", "", "9400.00"});
        lines.add(new String[] {""});
        lines.add(new String[] {"CURRENT ACCOUNT  100-111111-003"});
        lines.add(new String[] {"Date", "Narration", "Withdrawal", "Deposit", "Balance"});
        lines.add(new String[] {"01/07/2026", "OPENING BALANCE", "", "", "5000.00"});
        lines.add(new String[] {"06/07/2026", "UPI/DR/ROW F", "60.00", "", "4790.00"});
        lines.add(new String[] {"05/07/2026", "UPI/DR/ROW E", "50.00", "", "4850.00"});
        lines.add(new String[] {"04/07/2026", "UPI/DR/ROW D", "100.00", "25.00", "4900.00"});

        List<ImportDto.StagedAccountSection> sections = PdfGeneratorTestSupport.generator()
                .generateSections(UUID.randomUUID(), "synthetic.pdf", render(lines));

        assertThat(sections).as("two accounts, so two review lists").hasSize(2);
        Map<String, String> ambiguousRowOf = Map.of("SAVINGS", "ROW B", "CURRENT", "ROW D");
        for (ImportDto.StagedAccountSection section : sections) {
            String label = section.rows().stream().anyMatch(r -> r.description().endsWith("ROW A"))
                    ? ambiguousRowOf.get("SAVINGS") : ambiguousRowOf.get("CURRENT");
            int ambiguousRow = -1;
            for (int i = 0; i < section.rows().size(); i++) {
                if (section.rows().get(i).description().endsWith(label)) ambiguousRow = i;
            }
            ImportDto.VerificationFinding finding = section.verification().findings().stream()
                    .filter(f -> ColumnAmbiguityValidator.RULE.equals(f.rule())).findFirst().orElseThrow();
            List<Map<String, Object>> ambiguities = (List<Map<String, Object>>) finding.details().get("ambiguities");
            assertThat(ambiguities).as("one ambiguous row in the section holding %s", label).hasSize(1);
            assertThat(ambiguousRow).isNotNegative();
            assertThat(ambiguities.get(0).get("rowIndex"))
                    .as("%s's place among its own section's rows", label).isEqualTo(ambiguousRow);
        }
    }

    // ---------------------------------------------------------------- the real PDF staging path

    /** Draws free lines and table rows; a one-cell entry is a plain line at the left margin. */
    private static byte[] render(List<String[]> lines) throws Exception {
        float[] column = {50f, 120f, 300f, 380f, 470f};
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9f);
                float y = 770f;
                for (String[] cells : lines) {
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
            return out.toByteArray();
        }
    }

    private static StagingResponse viaPdf(List<Line> printed) throws Exception {
        float[] column = {50f, 120f, 300f, 380f, 470f};
        List<String[]> table = new ArrayList<>();
        table.add(new String[] {"Date", "Narration", "Withdrawal", "Deposit", "Balance"});
        for (Line row : printed) {
            table.add(new String[] {row.date(), row.narration(), row.withdrawal(), row.deposit(), row.balance()});
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
}
