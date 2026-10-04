package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NarrationLineBreaksTest {

    private static PdfTableLocator.LocatedDocument docWith(String description) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Date", "01/07/2026");
        row.put("Narration", description);
        row.put("Amount", "10.00");
        return new PdfTableLocator.LocatedDocument(
                List.of(new PdfTableLocator.LocatedSection(List.of(), List.of(row), null)), null);
    }

    private static String narrationOf(PdfTableLocator.LocatedDocument doc) {
        return doc.sections().get(0).rows().get(0).get("Narration");
    }

    @Test
    void joinLines_keepsTheBreakForTheResolver() {
        assertThat(NarrationLineBreaks.joinLines("UPI-SAMPLE", "STORE")).isEqualTo("UPI-SAMPLE\nSTORE");
    }

    @Test
    void resolveAll_withNoEvidence_joinsWithOneSpace() {
        // Exactly the pre-existing join: the break becomes one space and nothing around it is touched.
        assertThat(narrationOf(NarrationLineBreaks.resolveAll(docWith("SALARY FROM\nSAMPLE EMPLOYER"), null)))
                .isEqualTo("SALARY FROM SAMPLE EMPLOYER");
    }

    @Test
    void resolveAll_leavesCellsWithoutABreakAlone() {
        var out = NarrationLineBreaks.resolveAll(docWith("SAMPLE"), null);
        assertThat(out.sections().get(0).rows().get(0))
                .containsEntry("Date", "01/07/2026").containsEntry("Narration", "SAMPLE").containsEntry("Amount", "10.00");
    }

    // ---- G2: a UPI handle split at its '@' (the text layer never prints a space beside '@') ----

    @Test
    void aHandleSplitAtTheAt_glues() {
        assertThat(NarrationLineBreaks.glueByEvidence("UPI-SAMPLE-9000000001@", "okaxis-UPI")).isTrue(); // synthetic-ok
        assertThat(NarrationLineBreaks.glueByEvidence("UPI-SAMPLE-9000000001", "@okaxis-UPI")).isTrue(); // synthetic-ok
    }

    // ---- G3: a field separator ending the line, an identifier starting the next ----

    @Test
    void aSeparatorBeforeAnIdentifier_glues() {
        assertThat(NarrationLineBreaks.glueByEvidence("NEFT-", "100000000001-SAMPLE")).isTrue();
        assertThat(NarrationLineBreaks.glueByEvidence("UPI/DR/100000000001/SAMPLE/UTIB/gpay-", "90000000011@okaxis/")).isTrue(); // synthetic-ok
    }

    @Test
    void aSeparatorBeforeAPlainWord_keepsTheSpace() {
        assertThat(NarrationLineBreaks.glueByEvidence("UPI/100000000001/", "SAMPLE STORE/PAYMENT")).isFalse();
        assertThat(NarrationLineBreaks.glueByEvidence("1.", "MITC")).isFalse();
        assertThat(NarrationLineBreaks.glueByEvidence(":", "STATEMENT")).isFalse();
    }

    // ---- An IFSC cut by the wrap: it is never printed with a space inside it ----

    @Test
    void anIfscSplitAcrossTheBreak_glues() {
        assertThat(NarrationLineBreaks.glueByEvidence("UPI-Debit-100000000001-SAMPLE STORE-ABCD0X", "XXXXX-sample@okaxis")).isTrue(); // synthetic-ok
        assertThat(NarrationLineBreaks.glueByEvidence("UPI-SAMPLE PAYEE-ABC", "D0XXXXXX-100000000001")).isTrue();
        assertThat(NarrationLineBreaks.glueByEvidence("UPI-SAMPLE-ABCD0XXXXX", "X")).isTrue();
        assertThat(NarrationLineBreaks.glueByEvidence("UPI/SAMPLE/ABCD0", "XXXXXX/REF")).isTrue();
    }

    @Test
    void aWholeIfscEndingTheLine_gluesToTheSeparatorThatContinuesIt() {
        assertThat(NarrationLineBreaks.glueByEvidence("UPI-Debit-100000000001-SAMPLE-ABCD0XXXXXX", "-9000000001@ptsbi")).isTrue(); // synthetic-ok
        // A different separator than the one that opened the field says nothing.
        assertThat(NarrationLineBreaks.glueByEvidence("UPI/SAMPLE/ABCD0XXXXXX", "-REF")).isFalse();
        // A whole IFSC followed by a word keeps its space.
        assertThat(NarrationLineBreaks.glueByEvidence("UPI-SAMPLE-ABCD0XXXXXX", "PAYMENT")).isFalse();
    }

    @Test
    void anIfscShapeNotDelimitedAsAField_keepsTheSpace() {
        // A word before a number is not an IFSC field unless separators delimit it on both sides.
        assertThat(NarrationLineBreaks.glueByEvidence("PAID ABCD", "0123456-REF")).isFalse();
        assertThat(NarrationLineBreaks.glueByEvidence("SAMPLE-ABCD", "0123456 STORE")).isFalse();
        // One character too many, or lower case, is not an IFSC.
        assertThat(NarrationLineBreaks.glueByEvidence("SAMPLE-ABCD0X", "XXXXXX-REF")).isFalse();
        assertThat(NarrationLineBreaks.glueByEvidence("sample-abcd0x", "xxxxx-ref")).isFalse();
        // The fifth character of an IFSC is always a zero.
        assertThat(NarrationLineBreaks.glueByEvidence("SAMPLE-ABCD1X", "XXXXX-REF")).isFalse();
        assertThat(NarrationLineBreaks.glueByEvidence("", "ABCD0XXXXXX")).isFalse();
    }

    @Test
    void anIfscSplitWhereTheWidthLayoutReadsAPrintedSpace_stillGlues() {
        // 39 characters, one short of the width: the width rule alone keeps the space here.
        List<String> cell = List.of("UPI-SAMPLE PAYEE-900000001@OKSBI-ABCD00", "XXXXX-100000000001-PAYMENT", "SENT"); // synthetic-ok
        assertThat(cell.get(0).length()).isEqualTo(39);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40))
                .isEqualTo("UPI-SAMPLE PAYEE-900000001@OKSBI-ABCD00XXXXX-100000000001-PAYMENT SENT"); // synthetic-ok
        // And a plain word break at the same place keeps it.
        assertThat(NarrationLineBreaks.resolveCell(List.of("UPI-SAMPLE PAYEE-900000001@OKSBI-SAMPLE", "STORE-100000000001", "SENT"), 40)) // synthetic-ok
                .isEqualTo("UPI-SAMPLE PAYEE-900000001@OKSBI-SAMPLE STORE-100000000001 SENT"); // synthetic-ok
    }

    @Test
    void resolveAll_recordsAnIfscJoin() {
        var ctx = new com.finora.imports.DocumentContext("PDF", "test");
        var out = NarrationLineBreaks.resolveAll(docWith("UPI-Debit-100000000001-SAMPLE-ABCD0\nXXXXXX-sample@apl"), ctx); // synthetic-ok
        assertThat(narrationOf(out)).isEqualTo("UPI-Debit-100000000001-SAMPLE-ABCD0XXXXXX-sample@apl"); // synthetic-ok
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).contains("NARRATION_WRAP_JOINED_AT_IFSC");
    }

    @Test
    void aDateThenATimeOnTheNextLine_keepsTheSpace() {
        assertThat(NarrationLineBreaks.glueByEvidence("NACH SAMPLE/18/07/2026", "15:52:30/SAMPLE")).isFalse();
    }

    // ---- G1: a document that prints its narration in fixed-width lines ----

    /** One wrapped narration: a 40-character line that arrived as two pieces split at a real space,
     *  then two more lines. */
    private static List<String> fortyCharCell(String tail, String nextLine) {
        String first = "UPI-SAMPLE PAYEE";                        // 16
        String second = "SAMPLE-" + tail;                         // 16 + 1 + 23 = 40 when tail is 16
        assertThat(first.length() + 1 + second.length()).isEqualTo(40);
        return List.of(first, second, nextLine, "REF");
    }

    @Test
    void characterWrapWidth_findsTheWidthThatEndsMostLines() {
        List<List<String>> cells = new ArrayList<>();
        for (int i = 0; i < 12; i++) cells.add(fortyCharCell("900000001@okaxis", "-SAMPLE-UPI"));
        assertThat(NarrationLineBreaks.characterWrapWidth(cells)).isEqualTo(40);
    }

    @Test
    void characterWrapWidth_isNullForAWordWrappedDocument() {
        List<List<String>> cells = new ArrayList<>();
        int[][] shapes = {{26, 21, 8}, {17, 28, 22}, {24, 19, 25}, {21, 26, 9}, {28, 17, 20}, {19, 24, 26},
                {25, 22, 14}, {23, 27, 18}, {20, 25, 21}, {27, 18, 23}, {22, 20, 27}, {18, 26, 24}};
        for (int[] s : shapes) cells.add(List.of("X".repeat(s[0]), "X".repeat(s[1]), "X".repeat(s[2])));
        assertThat(NarrationLineBreaks.characterWrapWidth(cells)).isNull();
    }

    @Test
    void characterWrapWidth_needsEnoughWrapsToTrust() {
        List<List<String>> cells = new ArrayList<>();
        for (int i = 0; i < 3; i++) cells.add(fortyCharCell("900000001@okaxis", "-SAMPLE-UPI"));
        assertThat(NarrationLineBreaks.characterWrapWidth(cells)).isNull();
    }

    @Test
    void atAFullLine_aSplitDigitRunGlues_andTheSplitInsideTheLineKeepsItsSpace() {
        List<String> cell = List.of("NEFT CR-HDFC0XXXXXX", "SAMPLE PAYER-SAM1234", "56-SAMPLE");
        assertThat(cell.get(0).length() + 1 + cell.get(1).length()).isEqualTo(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("NEFT CR-HDFC0XXXXXX SAMPLE PAYER-SAM123456-SAMPLE");
    }

    @Test
    void atAFullLine_aFieldSeparatorOnTheNextLineGlues() {
        List<String> cell = List.of("UPI-SAMPLE STORE-SAMPLESTORE@okaxis-SAMP", "-100000000001-UPI"); // synthetic-ok
        assertThat(cell.get(0)).hasSize(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("UPI-SAMPLE STORE-SAMPLESTORE@okaxis-SAMP-100000000001-UPI"); // synthetic-ok
    }

    @Test
    void atAFullLine_twoPlainWordsGlue_unlessThePageShowsTheNextLineBeganWithASpace() {
        // Measured on the real HDFC statements: every break at a full line that fell on a space
        // started the next line one space in (see LineGeometry) -- all five on one statement -- and
        // every full-line break without that indent cut a word ("PAYMEN | T", "UPIINTEN | T"; 64
        // of 64 checked). With no word of the document to say otherwise, a full line is a cut.
        List<String> cell = List.of("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENTS", "RECEIVED");
        assertThat(cell.get(0)).hasSize(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENTSRECEIVED");
        List<String> indented = List.of(cell.get(0), LineGeometry.INDENTED_ONE_SPACE + "RECEIVED");
        assertThat(NarrationLineBreaks.withoutGeometryMarks(NarrationLineBreaks.resolveCell(indented, 40)))
                .isEqualTo("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENTS RECEIVED");
    }

    @Test
    void aLineOneShortOfTheWidth_endedAtASpace() {
        List<String> cell = List.of("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENT", "123456-UPI");
        assertThat(cell.get(0)).hasSize(39);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENT 123456-UPI");
    }

    @Test
    void withNoWidth_onlyTheEvidenceRulesApply() {
        assertThat(NarrationLineBreaks.resolveCell(List.of("SAMPLE1234", "56"), null)).isEqualTo("SAMPLE1234 56");
        assertThat(NarrationLineBreaks.resolveCell(List.of("SAMPLE-9000000001@", "okaxis"), null)).isEqualTo("SAMPLE-9000000001@okaxis"); // synthetic-ok
    }

    @Test
    void resolveAll_glues_andRecordsTheRuleThatFired() {
        var ctx = new com.finora.imports.DocumentContext("PDF", "test");
        var out = NarrationLineBreaks.resolveAll(docWith("UPI-SAMPLE-9000000001@\nokaxis-UPI"), ctx); // synthetic-ok
        assertThat(narrationOf(out)).isEqualTo("UPI-SAMPLE-9000000001@okaxis-UPI"); // synthetic-ok
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).contains("NARRATION_WRAP_JOINED_AT_HANDLE");
    }

    @Test
    void atAFullLine_anUnfinishedHandleGlues_evenLetterToLetter() {
        List<String> cell = List.of("UPI-SAMPLE", "SAMPLEPAYEENAM-900000001@PTAX", "IS-HDFC0XXXXXX-UPI");
        assertThat(cell.get(0).length() + 1 + cell.get(1).length()).isEqualTo(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("UPI-SAMPLE SAMPLEPAYEENAM-900000001@PTAXIS-HDFC0XXXXXX-UPI");
    }

    @Test
    void aHandleWordBreakInsideALine_keepsItsSpace() {
        List<String> cell = List.of("PAID TO SAMPLE@OK", "AXIS STORE", "NEXT");
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("PAID TO SAMPLE@OK AXIS STORE NEXT");
    }

    @Test
    void atAFullLine_aDateThenATimeKeepsTheSpace() {
        List<String> cell = List.of("NACH SAMPLE PAYMENT RF/SAMPLE/18/07/2026", "15:52:30/SAMPLE");
        assertThat(cell.get(0)).hasSize(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("NACH SAMPLE PAYMENT RF/SAMPLE/18/07/2026 15:52:30/SAMPLE");
    }

    // ---- F-05: a control character inside a PDF text run is a line break, never stored ----

    @Test
    void aCarriageReturnInsideACell_isResolvedLikeAWrap() {
        var out = NarrationLineBreaks.resolveAll(
                docWith("UPI/100000000001/\r SAMPLE STORE/SAMPLE@PAY\r 100000000002/UPI/"), null);
        assertThat(narrationOf(out)).isEqualTo("UPI/100000000001/ SAMPLE STORE/SAMPLE@PAY 100000000002/UPI/");
    }

    @Test
    void aCarriageReturnBeforeAnIdentifierAfterASeparator_glues_andIsRecorded() {
        var ctx = new com.finora.imports.DocumentContext("PDF", "test");
        var out = NarrationLineBreaks.resolveAll(docWith("UPI/SAMPLE/UPIINTENT/\r 100000000002/UTIB"), ctx);
        assertThat(narrationOf(out)).isEqualTo("UPI/SAMPLE/UPIINTENT/100000000002/UTIB");
        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .contains("NARRATION_CONTROL_CHARACTER_AS_LINE_BREAK", "NARRATION_WRAP_JOINED_AT_SEPARATOR");
    }

    @Test
    void theOtherLineSeparators_areLineBreaksToo() {
        assertThat(narrationOf(NarrationLineBreaks.resolveAll(docWith("SAMPLE\u0085STORE\u2028PAYMENT\u2029DONE"), null)))
                .isEqualTo("SAMPLE STORE PAYMENT DONE");
    }

    @Test
    void aCarriageReturnEndingARunThatIsThenJoined_isOneBreak_notTwo() {
        // Measured on a real export: the text run ends in "\r" and the next line arrives through a
        // join, so the cell holds "\r" then the join's break. Two breaks left an empty piece that
        // doubled the space and hid the separator from the rule.
        assertThat(narrationOf(NarrationLineBreaks.resolveAll(docWith("UPI/SAMPLE/UPIINTENT/\r\n100000000002/UTIB"), null)))
                .isEqualTo("UPI/SAMPLE/UPIINTENT/100000000002/UTIB");
        assertThat(narrationOf(NarrationLineBreaks.resolveAll(docWith("UPI/100000000001/\r \n SAMPLE STORE"), null)))
                .isEqualTo("UPI/100000000001/ SAMPLE STORE");
    }

    // ---- A text layer that prints its own line ends (the real Standard Chartered export) ----
    // Measured on that export: every narration line ends in "\r" (a field boundary), in a blank (a
    // word wrap), or in neither. All 69 breaks with neither fell inside a word or an identifier, and
    // all 51 ending in a blank fell between words.

    @Test
    void whereTheTextLayerPrintsLineEnds_aLineEndingInNeitherABreakNorABlank_wrappedInsideAWord() {
        var ctx = new com.finora.imports.DocumentContext("PDF", "test");
        var out = NarrationLineBreaks.resolveAll(
                docWith("UPI/100000000001/\r\nSAMPLE@OKAXIS/SAMPLE@OKAXIS/IOB\nA0XXXXXX\r\n100000000002/UPI/"), ctx);
        assertThat(narrationOf(out)).isEqualTo("UPI/100000000001/SAMPLE@OKAXIS/SAMPLE@OKAXIS/IOBA0XXXXXX 100000000002/UPI/");
        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .contains("NARRATION_WRAP_JOINED_WITHOUT_PRINTED_SPACE");
    }

    @Test
    void whereTheTextLayerPrintsLineEnds_aLineEndingInABlank_isOneSpace() {
        assertThat(narrationOf(NarrationLineBreaks.resolveAll(
                docWith("UPI/100000000001/\r\nSAMPLE \nSTORE/SAMPLE@PAY\r\n100000000002/UPI/"), null)))
                .isEqualTo("UPI/100000000001/ SAMPLE STORE/SAMPLE@PAY 100000000002/UPI/");
    }

    @Test
    void whereTheTextLayerPrintsLineEnds_aNextLineStartingWithABlank_isOneSpace() {
        // Measured on that export: a piece joined from another column arrives with its leading
        // blank ("SAMPLE INTEREST" then " 1,000.00"); the blank is printed, so it is no wrap.
        assertThat(narrationOf(NarrationLineBreaks.resolveAll(
                docWith("UPI/100000000001/\r\nSAMPLE INTEREST\n 1,000.00"), null)))
                .isEqualTo("UPI/100000000001/ SAMPLE INTEREST 1,000.00");
    }

    @Test
    void whereTheTextLayerPrintsLineEnds_everyOtherCellOfTheDocumentReadsItToo() {
        // The evidence is the document's: a cell with no "\r" of its own still sits in a text layer
        // that prints line ends, so its unmarked break is a wrap inside a word as well.
        Map<String, String> first = new LinkedHashMap<>();
        first.put("Narration", "UPI/100000000001/\r\nSAMPLE STORE");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("Narration", "SAMPLE@OKAXIS/U\nTIB0XXXXXX");
        var doc = new PdfTableLocator.LocatedDocument(
                List.of(new PdfTableLocator.LocatedSection(List.of(), List.of(first, second), null)), null);
        var out = NarrationLineBreaks.resolveAll(doc, null);
        assertThat(out.sections().get(0).rows().get(1).get("Narration")).isEqualTo("SAMPLE@OKAXIS/UTIB0XXXXXX");
    }

    @Test
    void whereTheTextLayerDoesNotPrintLineEnds_anUnmarkedBreakKeepsItsSpace() {
        // No "\r" anywhere: a missing blank at a line end says nothing, so nothing changes. (A split
        // IFSC would glue here, but by its own rule: see anIfscSplitAcrossTheBreak_glues.)
        assertThat(narrationOf(NarrationLineBreaks.resolveAll(docWith("SAMPLE@OKAXIS/STO\nRE FRONT"), null)))
                .isEqualTo("SAMPLE@OKAXIS/STO RE FRONT");
    }

    // ---- The document's own words (measured: HDFC "PAYMEN T FROM PH ONE", Canara "PAYME NT") ----

    private static PdfTableLocator.LocatedDocument docWithNarrations(String... narrations) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String narration : narrations) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Date", "01/07/2026");
            row.put("Narration", narration);
            rows.add(row);
        }
        return new PdfTableLocator.LocatedDocument(
                List.of(new PdfTableLocator.LocatedSection(List.of(), rows, null)), null);
    }

    private static String narrationOf(PdfTableLocator.LocatedDocument doc, int row) {
        return doc.sections().get(0).rows().get(row).get("Narration");
    }

    private static java.util.List<String> capabilitiesOf(com.finora.imports.DocumentContext ctx) {
        return ctx.capabilities().stream().map(c -> c.capability()).toList();
    }

    @Test
    void aWordTheDocumentPrintsWholeElsewhere_gluesItsBreak() {
        var ctx = new com.finora.imports.DocumentContext("PDF", "test");
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "UPI-SAMPLE-PAYMENT FROM PHONE", "UPI-SAMPLE STORE-PAYMENT FROM PHO\nNE"), ctx);
        assertThat(narrationOf(out, 1)).isEqualTo("UPI-SAMPLE STORE-PAYMENT FROM PHONE");
        assertThat(capabilitiesOf(ctx)).contains("NARRATION_WRAP_JOINED_BY_DOCUMENT_WORD");
    }

    @Test
    void aPairTheDocumentPrintsWithASpace_keepsIt() {
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "UPI-SAMPLE-PAYMENT FROM PHONE", "UPI-SAMPLE-PAYMENT FROM\nPHONE"), null);
        assertThat(narrationOf(out, 1)).isEqualTo("UPI-SAMPLE-PAYMENT FROM PHONE");
    }

    @Test
    void aBlankPrintedAtTheBreak_keepsTheSpace_whateverTheDocumentsWords() {
        // A line ending in a blank, or one opening on a blank (a piece joined from another column),
        // is not a word cut in two, however often the document prints the joined word.
        var out = NarrationLineBreaks.resolveAll(docWithNarrations("UPI-SAMPLE-PAYMENT FROM PHONE",
                "UPI-SAMPLE STORE-PAYMENT FROM PHO \nNE", "UPI-SAMPLE STORE-PAYMENT FROM PHO\n NE"), null);
        assertThat(narrationOf(out, 1).replaceAll(" +", " ")).isEqualTo("UPI-SAMPLE STORE-PAYMENT FROM PHO NE");
        assertThat(narrationOf(out, 2).replaceAll(" +", " ")).isEqualTo("UPI-SAMPLE STORE-PAYMENT FROM PHO NE");
    }

    @Test
    void theMoreFrequentOfJoinedAndSpaced_wins() {
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "SAMPLE NET BANKING", "SAMPLE NET BANKING", "SAMPLE NETBANKING", "TO NET\nBANKING"), null);
        assertThat(narrationOf(out, 3)).isEqualTo("TO NET BANKING");
    }

    // ---- A document proven to cut anywhere (measured: the real slice statement, 9 IFSCs cut) ----

    private static final String[] THREE_IFSC_CUTS = {
            "UPI-Debit-100000000001-SAMPLE-ABCD0\nXXXXXX-sample@okaxis", // synthetic-ok
            "UPI-Debit-100000000002-SAMPLE-ABC\nD0XXXXXX-sample@okaxis", // synthetic-ok
            "UPI-Debit-100000000003-SAMPLE-ABCD0X\nXXXXX-sample@okaxis"}; // synthetic-ok

    private static String[] with(String[] base, String... more) {
        List<String> all = new ArrayList<>(List.of(base));
        all.addAll(List.of(more));
        return all.toArray(new String[0]);
    }

    @Test
    void inADocumentThatCutsThroughIfscs_aWordNothingElseDecides_glues() {
        var ctx = new com.finora.imports.DocumentContext("PDF", "test");
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                with(THREE_IFSC_CUTS, "UPI-Debit-100000000004-SAMPLE GROCERIE\nS-sample@okaxis")), ctx); // synthetic-ok
        assertThat(narrationOf(out, 3)).isEqualTo("UPI-Debit-100000000004-SAMPLE GROCERIES-sample@okaxis"); // synthetic-ok
        assertThat(capabilitiesOf(ctx)).contains("NARRATION_WRAP_JOINED_AT_WIDTH_WRAP");
    }

    @Test
    void withoutThatProof_theSameBreakKeepsItsSpace() {
        // Two IFSC cuts are not enough, and a document that wraps between words (the real Canara
        // statement) cuts no IFSC at all: there a payee's name broken at a space looks exactly like a cut word.
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(THREE_IFSC_CUTS[0], THREE_IFSC_CUTS[1],
                "UPI-Debit-100000000004-SAMPLE GROCERIE\nS-sample@okaxis"), null); // synthetic-ok
        assertThat(narrationOf(out, 2)).isEqualTo("UPI-Debit-100000000004-SAMPLE GROCERIE S-sample@okaxis"); // synthetic-ok
    }

    @Test
    void evenThere_twoWordsTheDocumentPrintsApart_keepTheSpace() {
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(with(THREE_IFSC_CUTS,
                "UPI-Debit-100000000004-SAMPLE PERSON-sample@okaxis-You are paying", // synthetic-ok
                "UPI-Debit-100000000005-SAMPLE\nPERSON-sample@okaxis")), null); // synthetic-ok
        assertThat(narrationOf(out, 4)).isEqualTo("UPI-Debit-100000000005-SAMPLE PERSON-sample@okaxis"); // synthetic-ok
    }

    // ---- A reference code cut in two (measured: the real Canara and ICICI statements) ----

    @Test
    void aCodeCutInTwo_glues() {
        assertThat(NarrationLineBreaks.glueByEvidence("UPI//ICI/40ABC1234D56", "E7890F12/04/07")).isTrue();
        assertThat(NarrationLineBreaks.glueByEvidence("SAMPLE1234", "56")).isFalse();
        assertThat(NarrationLineBreaks.glueByEvidence("SAMPLE", "STORE9")).isFalse();
    }

    @Test
    void aWholeIfscBesideACode_isAFieldOfItsOwn() {
        // The real Standard Chartered statement prints "IFSC <account or code>" inside one field.
        assertThat(NarrationLineBreaks.glueByEvidence("SAMPLE@ICICI/ABCD0XXX001", "0001SL00ABCD/UPI/")).isFalse(); // synthetic-ok
    }

    // ---- The page's geometry (see LineGeometry) ----

    private static final char SHORT = LineGeometry.STOPPED_SHORT;
    private static final char INDENT = LineGeometry.INDENTED_ONE_SPACE;

    @Test
    void aLineThatStoppedShort_keepsItsSpace_overTheDocumentsWords() {
        // "SAMPLESTORE" printed whole would glue the break; the page says the line stopped short.
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "SAMPLESTORE", "MOB-IMPS-CR/SAMPLE" + SHORT + "\nSTORE/KMB"), null);
        assertThat(narrationOf(out, 1)).isEqualTo("MOB-IMPS-CR/SAMPLE STORE/KMB");
    }

    @Test
    void aLineThatBeganOneSpaceIn_keepsItsSpace() {
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "STOREFRONT", "UPI-SAMPLE-NEW STORE\n" + INDENT + "FRONT AND"), null);
        assertThat(narrationOf(out, 1)).isEqualTo("UPI-SAMPLE-NEW STORE FRONT AND");
    }

    @Test
    void anIfscStillGlues_whateverThePageSays() {
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "UPI-SAMPLE-ABCD0" + SHORT + "\n" + INDENT + "XXXXXX-sample@okaxis"), null); // synthetic-ok
        assertThat(narrationOf(out, 0)).isEqualTo("UPI-SAMPLE-ABCD0XXXXXX-sample@okaxis"); // synthetic-ok
    }

    @Test
    void aShortLineBeforeASeparator_saysNothing() {
        var out = NarrationLineBreaks.resolveAll(docWithNarrations("NEFT-" + SHORT + "\n100000000001-SAMPLE"), null);
        assertThat(narrationOf(out, 0)).isEqualTo("NEFT-100000000001-SAMPLE");
    }

    @Test
    void theGeometrysMarks_neverReachACell() {
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                INDENT + "SAMPLE STORE" + SHORT, "UPI-SAMPLE" + SHORT + "\nSTORE" + SHORT), null);
        assertThat(narrationOf(out, 0)).isEqualTo("SAMPLE STORE");
        assertThat(narrationOf(out, 1)).isEqualTo("UPI-SAMPLE STORE");
    }

    // ---- A field's width (measured: the real Canara statement's UPI payee field, never over 9) ----

    @Test
    void aBreakWhoseSpaceWouldWidenItsFieldPastEveryOther_glues() {
        var ctx = new com.finora.imports.DocumentContext("PDF", "test");
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "UPI/DR/100000000001/SAMPLE AB/ABCD/x", "UPI/DR/100000000002/SAMPLE CD/ABCD/x",
                "UPI/DR/100000000003/SAMPLE EF/ABCD/x",
                "UPI/DR/100000000004/PERSON\nAB C/ABCD/x", "UPI/DR/100000000005/PERSON\nAB/ABCD/x"), ctx);
        // "PERSON AB C" would be 11 wide, past the 9 every decided payee field is: the space was not printed.
        assertThat(narrationOf(out, 3)).isEqualTo("UPI/DR/100000000004/PERSONAB C/ABCD/x");
        // "PERSON AB" is 9: the field allows the space, so nothing overrides it.
        assertThat(narrationOf(out, 4)).isEqualTo("UPI/DR/100000000005/PERSON AB/ABCD/x");
        assertThat(capabilitiesOf(ctx)).contains("NARRATION_WRAP_JOINED_BY_FIELD_WIDTH");
    }

    @Test
    void aPairTheDocumentPrintsApart_keepsItsSpace_whateverTheFieldWidth() {
        // The field width would glue "PERSON | AB C" (11 wide, past 9), but the document prints
        // "PERSON AB" with a space elsewhere: its own words come first.
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "UPI/DR/100000000001/SAMPLE AB/ABCD/x", "UPI/DR/100000000002/SAMPLE CD/ABCD/x",
                "UPI/DR/100000000003/SAMPLE EF/ABCD/x", "IMPS TO PERSON AB",
                "UPI/DR/100000000004/PERSON\nAB C/ABCD/x"), null);
        assertThat(narrationOf(out, 4)).isEqualTo("UPI/DR/100000000004/PERSON AB C/ABCD/x");
    }

    @Test
    void aFieldWidthNeedsThreeDecidedFields() {
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "UPI/DR/100000000001/SAMPLE AB/ABCD/x", "UPI/DR/100000000002/SAMPLE CD/ABCD/x",
                "UPI/DR/100000000004/PERSON\nAB C/ABCD/x"), null);
        assertThat(narrationOf(out, 2)).isEqualTo("UPI/DR/100000000004/PERSON AB C/ABCD/x");
    }

    @Test
    void twoHalvesTheDocumentPrintsAsWordsOfTheirOwn_keepTheSpace_evenWhereItCutsThroughWords() {
        // Neither "SAMPLEPERSON" nor "SAMPLE PERSON" is printed anywhere, but both halves are words
        // the document prints: that is evidence of two words, and the width-wrap guess never runs.
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(with(THREE_IFSC_CUTS,
                "UPI-Debit-100000000004-PERSON-sample@okaxis", // synthetic-ok
                "UPI-Debit-100000000005-SAMPLE\nPERSON-sample@okaxis")), null); // synthetic-ok
        assertThat(narrationOf(out, 4)).isEqualTo("UPI-Debit-100000000005-SAMPLE PERSON-sample@okaxis"); // synthetic-ok
    }

    @Test
    void aShortLineBeforeALineOpeningOnAnythingButAWordOrASlash_saysNothing() {
        // Only a line opening on a letter, a digit or a slash reads a short line as a space: one
        // opening on "@" ran out of room inside a handle, which the handle rule still glues.
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(
                "UPI-SAMPLE-9000000001" + SHORT + "\n@okaxis-UPI"), null); // synthetic-ok
        assertThat(narrationOf(out, 0)).isEqualTo("UPI-SAMPLE-9000000001@okaxis-UPI"); // synthetic-ok
    }

    // ---- A fixed-width document (measured: the real HDFC statements, 40 characters a line) ----

    private static String[] fortyCharacterRows(String... more) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(String.join("\n", fortyCharCell("900000001@okaxis", "-SAMPLE-UPI")));
        rows.addAll(List.of(more));
        return rows.toArray(new String[0]);
    }

    @Test
    void inAFixedWidthDocument_aLineThatStoppedShort_saysNothing() {
        // It cuts by character count, so a line of narrow characters ends short of the rest while
        // still cut inside a word: at a full line the cut stands.
        List<String> cell = List.of("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENTS" + SHORT, "RECEIVED");
        assertThat(NarrationLineBreaks.withoutGeometryMarks(NarrationLineBreaks.resolveCell(cell, 40)))
                .isEqualTo("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENTSRECEIVED");
    }

    @Test
    void inAFixedWidthDocument_aBreakInsideALine_keepsItsSpace_whateverTheDocumentsWords() {
        // Measured on a real HDFC statement: a two-word merchant name broke inside a line, so its space
        // was printed, though the same statement prints the merchant joined elsewhere.
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(fortyCharacterRows(
                "UPI-SAMPLESTORE-PAYMENT", "UPI-SAMPLE\nSTORE-ABC")), null);
        assertThat(narrationOf(out, 13)).isEqualTo("UPI-SAMPLE STORE-ABC");
    }

    @Test
    void inAFixedWidthDocument_aCellItsWidthCannotLayOut_isNotProvenAWidthWrap() {
        // Even in a document that also cuts IFSCs: a cell whose pieces overflow the width (measured on
        // a real HDFC statement: "PAYMENT | FROM" and a person's first and last name) proves nothing about its breaks.
        var out = NarrationLineBreaks.resolveAll(docWithNarrations(fortyCharacterRows(with(THREE_IFSC_CUTS,
                "UPI-SAMPLE-" + "X".repeat(36) + "-PERSON\nAB-UPI"))), null);
        assertThat(narrationOf(out, 15)).isEqualTo("UPI-SAMPLE-" + "X".repeat(36) + "-PERSON AB-UPI");
    }
}
