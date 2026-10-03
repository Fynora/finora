package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.ocr.OcrEngine.RecognisedText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RunAssembler#TALL_RUN}: a run OCR boxed taller than a text line is placed by its baseline.
 * Geometry is the production engine's own on a scanned Union Bank of India statement (one
 * transaction: narration line one above its date line, line two below); text is invented.
 */
class RunAssemblerTallRunTest {

    private static RecognisedText run(String text, float x, float baseline, float width, float height) {
        return new RecognisedText(text, x, baseline, width, height, 0, 0.95f);
    }

    /** One transaction, plus enough ordinary rows above it to give the page a realistic median. */
    private static List<RecognisedText> transaction(RecognisedText tall) {
        List<RecognisedText> runs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            float y = 300f + i * 19f;
            runs.add(run("01-09-2026", 51.6f, y, 38.4f, 5.0f));
            runs.add(run("10.00", 351.1f, y, 18f, 5.0f));
        }
        runs.add(run("UPIAR/000000000001/DR/SAMPLE", 102.5f, 431.0f, 114.5f, 7.7f));   // line one
        runs.add(run("09-09-2026", 51.6f, 434.6f, 38.4f, 5.0f));                       // date line
        runs.add(run("60.00", 351.1f, 434.6f, 18f, 5.0f));
        runs.add(run("XX/BARB/0000000000@", 102.5f, 441.4f, 96f, 6.5f));             // line two
        runs.add(tall);
        return runs;
    }

    private static float yOf(List<RecognisedText> assembled, String startsWith) {
        return assembled.stream().filter(r -> r.text().startsWith(startsWith)).findFirst().orElseThrow().y();
    }

    @Test
    void aTallTailJoinsTheNarrationLineItEndsOn_andTheDateLineStaysWhereItWasPrinted() {
        List<RecognisedText> assembled = RunAssembler.assemble(
                transaction(run("pty", 199.4f, 442.6f, 12f, 11.8f)));

        assertThat(yOf(assembled, "09-09-2026")).isEqualTo(434.6f);
        assertThat(assembled).extracting(RecognisedText::text).contains("XX/BARB/0000000000@ pty");
    }

    @Test
    void aTallRunOfVerticalBarsIsTableRuling_andIsDropped() {
        List<RecognisedText> assembled = RunAssembler.assemble(
                transaction(run("|", 96.5f, 442.6f, 4.1f, 12.2f)));

        assertThat(yOf(assembled, "09-09-2026")).isEqualTo(434.6f);
        assertThat(assembled).extracting(RecognisedText::text).noneMatch(t -> t.contains("|"));
    }

    @Test
    void aTallRunNearNoLineStandsOnItsOwn() {
        List<RecognisedText> assembled = RunAssembler.assemble(
                transaction(run("LOGO", 300f, 40f, 60f, 18f)));

        assertThat(assembled).extracting(RecognisedText::text).contains("LOGO");
        assertThat(yOf(assembled, "LOGO")).isEqualTo(40f);
    }
}
