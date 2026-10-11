package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A finding that points at a transaction has to say where that transaction is in the list the
 * customer is shown, under a name ({@code displayIndex}) only a report numbered that way carries.
 *
 * <p>The review screens mark the row and print its number from that field. Before it existed the
 * only position on a finding was {@code rowIndex}, and on the PDF path that counted a list the
 * customer never sees: the document's own sequence is checked, a date-sorted copy is shown.
 * Measured through the real PDF generator on the first statement below, printed newest first:
 *
 * <ul>
 *   <li>the balance check said position 2 for a row shown at position 4 (mirrored inside its
 *       same-day group);</li>
 *   <li>the description check said position 5 for a row shown at position 1.</li>
 * </ul>
 *
 * <p>Every case goes through the real generator for its path. The fault was never in one class: a
 * validator numbered rows correctly in an order the screen did not use.
 */
class FlaggedRowDisplayPositionTest {

    /** date, amount as printed, balance as printed, narration. All debits. */
    private record Line(String date, String amount, String balance, String narration) {}

    private static final String FOOTER = " This sentence was never part of the narration. It stands in for a page"
            + " footer that merged into the row above it. Nothing in it is any bank's wording.";

    // Chronological truth, opening 10000.00. C, D and E share a day. ROW C's amount is 10.00 too
    // large against its untouched balance, so exactly that row breaks the chain. ROW B's narration
    // carries a footer, so exactly that row is a description outlier.
    private static List<Line> sameDayStatement() {
        return List.of(
                new Line("01/07/2026", "100.00", "9900.00", "UPI/DR/ROW A"),
                new Line("02/07/2026", "200.00", "9700.00", "UPI/DR/ROW B" + FOOTER),
                new Line("03/07/2026", "310.00", "9400.00", "UPI/DR/ROW C"),
                new Line("03/07/2026", "400.00", "9000.00", "UPI/DR/ROW D"),
                new Line("03/07/2026", "500.00", "8500.00", "UPI/DR/ROW E"),
                new Line("04/07/2026", "600.00", "7900.00", "UPI/DR/ROW F"),
                new Line("05/07/2026", "700.00", "7200.00", "UPI/DR/ROW G"));
    }

    // Printed oldest first, but ROW H sits third while carrying the first day's date: a back-dated
    // entry, which the display sort moves up. It is both the row that breaks the chain and the one
    // with the footer. ROW F has a footer too, so there are two outliers to put in order.
    private static List<Line> backDatedStatement() {
        return List.of(
                new Line("01/07/2026", "100.00", "9900.00", "UPI/DR/ROW A"),
                new Line("02/07/2026", "200.00", "9700.00", "UPI/DR/ROW B"),
                new Line("01/07/2026", "310.00", "9400.00", "UPI/DR/ROW H" + FOOTER),
                new Line("04/07/2026", "400.00", "9000.00", "UPI/DR/ROW F" + FOOTER),
                new Line("05/07/2026", "500.00", "8500.00", "UPI/DR/ROW G"),
                new Line("06/07/2026", "600.00", "7900.00", "UPI/DR/ROW I"),
                new Line("07/07/2026", "700.00", "7200.00", "UPI/DR/ROW J"));
    }

    private static List<Line> newestFirst(List<Line> chronological) {
        List<Line> reversed = new ArrayList<>(chronological);
        Collections.reverse(reversed);
        return reversed;
    }

    @Test
    void aNewestFirstPdfSaysWhereEachFlaggedRowIsShown() throws Exception {
        StagingResponse response = viaPdf(newestFirst(sameDayStatement()));

        // The display sort keeps the same-day group in the order the document printed it.
        assertThat(labels(response.rows())).containsExactly("A", "B", "E", "D", "C", "F", "G");

        assertThat(balanceDisplayIndexes(response)).containsExactly(shownAt(response, "ROW C"));
        assertThat(outlierDisplayIndexes(response)).containsExactly(shownAt(response, "ROW B"));
    }

    @Test
    void aPdfRowMovedByTheDisplaySortIsNamedWhereItLands() throws Exception {
        StagingResponse response = viaPdf(backDatedStatement());

        assertThat(labels(response.rows())).containsExactly("A", "H", "B", "F", "G", "I", "J");

        assertThat(balanceDisplayIndexes(response)).containsExactly(shownAt(response, "ROW H"));
        assertThat(outlierDisplayIndexes(response))
                .as("both outliers, top to bottom of the list shown")
                .containsExactly(shownAt(response, "ROW H"), shownAt(response, "ROW F"));
    }

    @Test
    void anOldestFirstPdfIsUnaffected() throws Exception {
        StagingResponse response = viaPdf(sameDayStatement());

        assertThat(labels(response.rows())).containsExactly("A", "B", "C", "D", "E", "F", "G");
        assertThat(balanceDisplayIndexes(response)).containsExactly(2);
        assertThat(outlierDisplayIndexes(response)).containsExactly(1);
    }

    /** The CSV path shows its rows as the file listed them, so there the list checked is the list
     *  shown -- including for a file listed newest first, which the balance walk reverses. */
    @Test
    void aNewestFirstCsvSaysWhereEachFlaggedRowIsShown() throws Exception {
        StagingResponse response = viaCsv(newestFirst(sameDayStatement()));

        assertThat(labels(response.rows())).containsExactly("G", "F", "E", "D", "C", "B", "A");

        assertThat(balanceDisplayIndexes(response)).containsExactly(shownAt(response, "ROW C"));
        assertThat(outlierDisplayIndexes(response)).containsExactly(shownAt(response, "ROW B"));
    }

    /** {@code rowIndex} on an outlier is still its place in the document's own sequence: this
     *  change adds a position, it does not move one. */
    @Test
    void anOutliersRowIndexStillCountsTheDocumentsOwnSequence() throws Exception {
        StagingResponse response = viaPdf(newestFirst(sameDayStatement()));

        // Printed G F E D C B A, so ROW B is the sixth line of the document.
        assertThat(outliers(response)).singleElement()
                .satisfies(outlier -> assertThat(outlier.get("rowIndex")).isEqualTo(5));
    }

    /**
     * A review opened later reads the report back out of the session's stored JSON
     * (ImportSessionService), where a finding's details are plain maps. The positions have to
     * arrive there as whole numbers under the same names.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePositionsSurviveBeingStoredWithTheSession() throws Exception {
        StagingResponse response = viaPdf(newestFirst(sameDayStatement()));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        ImportDto.VerificationReport stored = objectMapper.readValue(
                objectMapper.writeValueAsString(response.verification()), ImportDto.VerificationReport.class);

        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>)
                finding(stored, BalanceChainValidator.RULE).details().get("discrepancies");
        List<Map<String, Object>> outliers = (List<Map<String, Object>>)
                finding(stored, DescriptionCorruptionValidator.RULE).details().get("outliers");

        assertThat(discrepancies).singleElement()
                .satisfies(d -> assertThat(d.get("displayIndex")).isEqualTo(shownAt(response, "ROW C")));
        assertThat(outliers).singleElement()
                .satisfies(o -> assertThat(o.get("displayIndex")).isEqualTo(shownAt(response, "ROW B")));
    }

    // ------------------------------------------------------------------------------ reading a report

    private static List<String> labels(List<StagedRow> shown) {
        // "UPI/DR/ROW X ..." -> "X"
        return shown.stream().map(row -> row.description().substring("UPI/DR/ROW ".length()).substring(0, 1)).toList();
    }

    private static int shownAt(StagingResponse response, String label) {
        List<StagedRow> shown = response.rows();
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).description().startsWith("UPI/DR/" + label)) return i;
        }
        throw new AssertionError(label + " is not among the rows shown");
    }

    private static ImportDto.VerificationFinding finding(ImportDto.VerificationReport report, String rule) {
        return report.findings().stream().filter(f -> rule.equals(f.rule())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> balanceDisplayIndexes(StagingResponse response) {
        ImportDto.VerificationFinding chain = finding(response.verification(), BalanceChainValidator.RULE);
        assertThat(chain.outcome()).isEqualTo("WARNING");
        List<ImportDto.RowDiscrepancy> discrepancies =
                (List<ImportDto.RowDiscrepancy>) chain.details().get("discrepancies");
        for (ImportDto.RowDiscrepancy discrepancy : discrepancies) {
            assertThat(discrepancy.rowIndex())
                    .as("the balance check's two names for a row's position agree")
                    .isEqualTo(discrepancy.displayIndex());
            assertThat(discrepancy.actualBalance())
                    .as("and the balance quoted beside the number is that row's own")
                    .isEqualByComparingTo(response.rows().get(discrepancy.displayIndex()).balanceAfter());
        }
        return discrepancies.stream().map(ImportDto.RowDiscrepancy::displayIndex).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> outliers(StagingResponse response) {
        ImportDto.VerificationFinding descriptions =
                finding(response.verification(), DescriptionCorruptionValidator.RULE);
        assertThat(descriptions.outcome()).isEqualTo("WARNING");
        return (List<Map<String, Object>>) descriptions.details().get("outliers");
    }

    private static List<Integer> outlierDisplayIndexes(StagingResponse response) {
        List<Integer> positions = new ArrayList<>();
        for (Map<String, Object> outlier : outliers(response)) {
            int position = (Integer) outlier.get("displayIndex");
            assertThat(response.rows().get(position).description().trim().length())
                    .as("the row it points at is the one whose length it reports")
                    .isEqualTo(outlier.get("length"));
            positions.add(position);
        }
        return positions;
    }

    // ---------------------------------------------------------------- the two real staging paths

    private static StagingResponse viaPdf(List<Line> printed) throws Exception {
        float[] column = {50f, 130f, 230f, 320f, 400f, 480f};
        List<String[]> table = new ArrayList<>();
        table.add(new String[] {"Date", "Instrument ID", "Amount(INR)", "Type (DR/CR)", "Balance", "Remarks"});
        for (Line row : printed) {
            table.add(new String[] {row.date(), "", row.amount(), "DR", row.balance(), row.narration()});
        }

        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            // Wide enough that a narration with a footer in it stays on the page as one line.
            PDPage page = new PDPage(new PDRectangle(1600f, 842f));
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
            csv.append(row.date()).append(',').append(row.narration()).append(',').append(row.amount())
                    .append(",,").append(row.balance()).append('\n');
        }
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
        return generator.generate(UUID.randomUUID(), "synthetic.csv",
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
