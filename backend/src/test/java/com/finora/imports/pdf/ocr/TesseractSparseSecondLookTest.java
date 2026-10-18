package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.ScannedPdfFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link TesseractEngine#recogniseSparse}, the second look's reading, through the real engine: every
 * page read in sparse-text mode alone, positioned like the default reading so the same parser reads
 * it. Skipped where tesseract is not installed, like this package's other engine tests.
 */
class TesseractSparseSecondLookTest {

    @BeforeEach
    void requireTesseract() {
        assumeTrue(TesseractEngine.available(), "tesseract is not installed");
    }

    @Test
    void readsTheSameAmountsAsTheDefaultReadingOnACleanScan() throws Exception {
        byte[] scanned = ScannedPdfFixture.scan(PdfFixtureBuilder.buildAmountOnLastLineSampleEveryAmountRead(), 300);

        var sparse = new TesseractEngine().recogniseSparse(scanned, 300);
        var standard = new TesseractEngine().recognise(scanned, 300);

        assertThat(sparse).isNotEmpty();
        assertThat(sparse).extracting(OcrEngine.RecognisedText::text)
                .contains("50,000.00", "58,000.00", "1,000.00", "54,000.00");
        // Positions are in the same space as the default reading's, page by page.
        assertThat(sparse).allMatch(t -> t.pageIndex() == 0);
        float defaultTop = (float) standard.stream().mapToDouble(OcrEngine.RecognisedText::y).min().orElseThrow();
        float sparseTop = (float) sparse.stream().mapToDouble(OcrEngine.RecognisedText::y).min().orElseThrow();
        assertThat(Math.abs(defaultTop - sparseTop)).isLessThan(5f);
    }
}
