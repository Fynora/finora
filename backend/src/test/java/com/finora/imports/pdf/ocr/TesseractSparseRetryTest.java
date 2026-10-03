package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.ocr.OcrEngine.RecognisedText;
import com.finora.imports.pdf.ocr.TesseractEngine.PageReadings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which reading of each page {@link TesseractEngine#choose} keeps. The shares used are the ones
 * measured on the two real scans that set the thresholds (see the constants' doc comments).
 */
class TesseractSparseRetryTest {

    /** A page of {@code total} words, {@code low} of them scored below the low-confidence line. The
     *  text names the reading, so a test can tell which one was kept. */
    private static List<RecognisedText> page(String reading, int total, int low) {
        List<RecognisedText> runs = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            runs.add(new RecognisedText(reading, i, 10, 5, 5, 0, i < low ? 0.10f : 0.95f));
        }
        return runs;
    }

    private static List<String> kept(List<PageReadings> pages) {
        return TesseractEngine.choose(pages).stream().map(RecognisedText::text).distinct().toList();
    }

    @Test
    void aCleanPageIsNeverRetried() {
        // HSBC's scan: 0.2% low confidence -- below the retry gate, so no sparse reading exists.
        assertThat(TesseractEngine.lowConfidenceShare(page("default", 1000, 2)))
                .isLessThanOrEqualTo(TesseractEngine.SPARSE_RETRY_LOW_CONFIDENCE_SHARE);
    }

    @Test
    void aGriddedScanTakesTheSparseReadingOnEveryRetriedPageThatIsNotWorse() {
        List<PageReadings> pages = List.of(
                // A transaction page: 16.7% vs 2.4% -- the 3x evidence the document needs it.
                new PageReadings(page("default-1", 1000, 167), page("sparse-1", 1000, 24)),
                // The last page, diluted by non-grid tables: 2.7% vs 2.3%, not 3x on its own.
                new PageReadings(page("default-2", 1000, 27), page("sparse-2", 1000, 23)));

        assertThat(kept(pages)).containsExactly("sparse-1", "sparse-2");
    }

    @Test
    void aPageWhereSparseIsWorseKeepsTheDefaultEvenInADocumentThatNeedsSparse() {
        List<PageReadings> pages = List.of(
                new PageReadings(page("default-1", 1000, 167), page("sparse-1", 1000, 24)),
                new PageReadings(page("default-2", 1000, 30), page("sparse-2", 1000, 40)));

        assertThat(kept(pages)).containsExactly("sparse-1", "default-2");
    }

    @Test
    void withoutOnePageThreeTimesCleanerEveryPageKeepsTheDefault() {
        // Sparse slightly cleaner everywhere, but nowhere decisively: the default stands.
        List<PageReadings> pages = List.of(
                new PageReadings(page("default-1", 1000, 27), page("sparse-1", 1000, 23)),
                new PageReadings(page("default-2", 1000, 50), page("sparse-2", 1000, 20)));

        assertThat(kept(pages)).containsExactly("default-1", "default-2");
    }

    @Test
    void aPageThatWasNotRetriedKeepsItsDefaultReading() {
        List<PageReadings> pages = List.of(
                new PageReadings(page("default-1", 1000, 167), page("sparse-1", 1000, 24)),
                new PageReadings(page("default-2", 1000, 2), null));

        assertThat(kept(pages)).containsExactly("sparse-1", "default-2");
    }

    @Test
    void theThreeTimesBoundaryIsInclusive() {
        List<PageReadings> pages = List.of(
                new PageReadings(page("default-1", 1000, 60), page("sparse-1", 1000, 20)));

        assertThat(kept(pages)).containsExactly("sparse-1");
    }

    @Test
    void unscoredWordsCountOnlyInTheDenominator() {
        List<RecognisedText> runs = new ArrayList<>(page("x", 3, 1));
        runs.add(new RecognisedText("unscored", 0, 10, 5, 5, 0, null));

        assertThat(TesseractEngine.lowConfidenceShare(runs)).isEqualTo(0.25);
        assertThat(TesseractEngine.lowConfidenceShare(List.of())).isZero();
    }
}
