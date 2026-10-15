package com.finora.imports;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.RowLines;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The row map through the real PDF reader: each shown row's printed lines reach the sink under the
 * row's displayed number, a row that is not shown is left out, and a reading with a sink stages
 * exactly what a reading without one does. See docs/superpowers/specs/2026-10-15-row-map-design.md.
 *
 * <p>All statement text is synthetic.
 */
class RowMapSinkTest {

    private record Line(String date, String amount, String balance, String label) {}

    /** Header, then one printed line per entry, 10pt apart, on one A4 page. */
    private static byte[] pdf(List<Line> printed) throws Exception {
        float[] column = {50f, 130f, 230f, 320f, 400f, 480f};
        List<String[]> table = new ArrayList<>();
        table.add(new String[] {"Date", "Instrument ID", "Amount(INR)", "Type (DR/CR)", "Balance", "Remarks"});
        for (Line row : printed) {
            table.add(new String[] {row.date(), "", row.amount(), row.amount().isEmpty() ? "" : "DR", row.balance(),
                    "UPI/DR/" + row.label()});
        }
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
            return out.toByteArray();
        }
    }

    private static final List<Line> OLDEST_FIRST = List.of(
            new Line("01/07/2026", "100.00", "9900.00", "ROW A"),
            new Line("02/07/2026", "200.00", "9700.00", "ROW B"),
            new Line("03/07/2026", "300.00", "9400.00", "ROW C"));

    private record Reading(List<StagedAccountSection> sections, Map<Integer, Map<Integer, RowLines>> rowMap,
                           List<PositionedText> runs) {}

    private static Reading read(byte[] bytes) throws Exception {
        PdfPreviewGenerator generator = PdfGeneratorTestSupport.generator();
        AcquiredDocument acquired = generator.acquire(bytes, null);
        Map<Integer, Map<Integer, RowLines>> rowMap = new LinkedHashMap<>();
        List<StagedAccountSection> sections = generator.generateSectionsFromAcquired(UUID.randomUUID(),
                "synthetic.pdf", acquired, (section, lines) -> {
                    assertThat(rowMap).as("one call per section").doesNotContainKey(section);
                    rowMap.put(section, lines);
                }).sections();
        return new Reading(sections, rowMap, acquired.runs());
    }

    /** The line a label was printed on, read from the acquired runs themselves. */
    private static RowLines lineOf(Reading reading, String label) {
        PositionedText labelRun = reading.runs().stream()
                .filter(run -> run.text().contains(label)).findFirst().orElseThrow();
        return RowLines.of(reading.runs().stream()
                .filter(run -> run.pageIndex() == labelRun.pageIndex() && Math.abs(run.y() - labelRun.y()) < 1f)
                .toList());
    }

    /** Every shown row of section 0 maps to the line its own label was printed on. */
    private static void assertEachShownRowMapsToItsOwnLine(Reading reading) {
        List<StagedRow> shown = reading.sections().get(0).rows();
        Map<Integer, RowLines> map = reading.rowMap().get(0);
        assertThat(map).hasSize(shown.size());
        for (int i = 0; i < shown.size(); i++) {
            String description = shown.get(i).description();
            String label = description.substring(description.lastIndexOf("ROW "));
            assertThat(map.get(i)).as("shown row %d (%s)", i, label).isEqualTo(lineOf(reading, label));
        }
    }

    @Test
    void eachStagedRowGetsItsLinesUnderItsDisplayedNumber() throws Exception {
        Reading reading = read(pdf(OLDEST_FIRST));

        assertThat(reading.sections()).hasSize(1);
        assertThat(reading.sections().get(0).rows()).hasSize(3);
        assertThat(reading.rowMap()).containsOnlyKeys(0);
        assertEachShownRowMapsToItsOwnLine(reading);
        assertThat(reading.rowMap().get(0).values()).allSatisfy(lines -> assertThat(lines.lines()).hasSize(1));
    }

    @Test
    void rowNumbersAreDisplayedPositions() throws Exception {
        List<Line> newestFirst = new ArrayList<>(OLDEST_FIRST);
        Collections.reverse(newestFirst);

        Reading reading = read(pdf(newestFirst));

        List<StagedRow> shown = reading.sections().get(0).rows();
        assertThat(shown.get(0).description()).as("shown oldest first").endsWith("ROW A");
        assertThat(reading.rowMap().get(0).get(0))
                .as("row 0 is the row shown first, printed last")
                .isEqualTo(lineOf(reading, "ROW A"));
        assertEachShownRowMapsToItsOwnLine(reading);
    }

    @Test
    void aRowThatDoesNotStageIsLeftOut() throws Exception {
        List<Line> printed = new ArrayList<>();
        printed.add(new Line("30/06/2026", "", "10000.00", "OPENING BALANCE"));
        printed.addAll(OLDEST_FIRST);

        Reading reading = read(pdf(printed));

        assertThat(reading.sections().get(0).rows()).hasSize(3);
        assertThat(reading.sections().get(0).rows())
                .noneSatisfy(row -> assertThat(row.description()).contains("OPENING BALANCE"));
        assertThat(reading.rowMap().get(0)).containsOnlyKeys(0, 1, 2);
        assertThat(reading.rowMap().get(0).values()).doesNotContain(lineOf(reading, "OPENING BALANCE"));
        assertEachShownRowMapsToItsOwnLine(reading);
    }

    @Test
    void theOldOverloadAndANullSinkReadTheSame() throws Exception {
        byte[] bytes = pdf(OLDEST_FIRST);
        PdfPreviewGenerator generator = PdfGeneratorTestSupport.generator();
        UUID user = UUID.randomUUID();

        var plain = generator.generateSectionsFromAcquired(user, "synthetic.pdf", generator.acquire(bytes, null));
        var nullSink = generator.generateSectionsFromAcquired(user, "synthetic.pdf", generator.acquire(bytes, null), null);
        var withSink = generator.generateSectionsFromAcquired(user, "synthetic.pdf", generator.acquire(bytes, null),
                (section, lines) -> { });

        assertThat(nullSink.sections()).isEqualTo(plain.sections());
        assertThat(withSink.sections()).isEqualTo(plain.sections());
        assertThat(withSink.documentContext().capabilities()).isEqualTo(plain.documentContext().capabilities());
    }

    /**
     * Completeness over every synthetic statement the fixture builder makes: a staged row with no
     * lines means a row-building path that records nothing.
     */
    @Test
    void everyStagedRowHasLines() throws Exception {
        List<String> checked = new ArrayList<>();
        List<byte[]> statements = new ArrayList<>(List.of(pdf(OLDEST_FIRST)));
        List<String> names = new ArrayList<>(List.of("oldest first"));
        for (Method method : PdfFixtureBuilder.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())
                    || method.getParameterCount() != 0 || method.getReturnType() != byte[].class) continue;
            statements.add((byte[]) method.invoke(null));
            names.add(method.getName());
        }
        int rowsChecked = 0;
        for (int n = 0; n < statements.size(); n++) {
            Reading reading;
            try {
                reading = read(statements.get(n));
            } catch (java.io.IOException unreadable) {
                continue; // an encrypted or deliberately damaged fixture: nothing is staged from it
            }
            for (int s = 0; s < reading.sections().size(); s++) {
                List<StagedRow> rows = reading.sections().get(s).rows();
                if (rows == null || rows.isEmpty()) continue;
                Map<Integer, RowLines> map = reading.rowMap().get(s);
                assertThat(map).as("%s section %d", names.get(n), s).isNotNull().hasSize(rows.size());
                for (int r = 0; r < rows.size(); r++) {
                    assertThat(map.get(r)).as("%s section %d row %d", names.get(n), s, r)
                            .isNotNull().isNotEqualTo(RowLines.NONE);
                    rowsChecked++;
                }
            }
            checked.add(names.get(n));
        }
        assertThat(checked).as("fixtures read").hasSizeGreaterThan(10);
        assertThat(rowsChecked).isGreaterThan(50);
    }
}
