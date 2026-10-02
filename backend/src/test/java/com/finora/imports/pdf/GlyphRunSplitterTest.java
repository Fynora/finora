package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlyphRunSplitterTest {

    private static GlyphRunSplitter.Glyph glyph(String text, float x, float endX, float fontSize) {
        return new GlyphRunSplitter.Glyph(text, x, endX, 100f, fontSize);
    }

    private static List<String> texts(List<List<GlyphRunSplitter.Glyph>> segments) {
        return segments.stream()
                .map(seg -> seg.stream().map(GlyphRunSplitter.Glyph::text).reduce("", String::concat))
                .toList();
    }

    // Coordinates copied verbatim from a direct PDFBox TextPosition inspection of the real SC bank
    // statement's own table header row: "Value" ends at x=103.09, "Description" starts at
    // x=180.55 (a 75.52pt gap, no intervening glyph) -- font 7pt, so 75.52/7 = 10.8x. This is the
    // exact real-document shape GlyphRunSplitter exists to split.
    @Test
    void split_separatesTwoHeaderCells_whenPdfBoxMergedThemAcrossAWideGap() {
        var glyphs = List.of(
                glyph("V", 84.41f, 89.08f, 7f), glyph("a", 89.08f, 92.97f, 7f),
                glyph("l", 92.97f, 94.92f, 7f), glyph("u", 94.92f, 99.20f, 7f),
                glyph("e", 99.20f, 103.09f, 7f), glyph("D", 180.55f, 185.60f, 7f),
                glyph("e", 185.60f, 189.50f, 7f));

        var result = GlyphRunSplitter.split(glyphs);

        assertThat(texts(result)).containsExactly("Value", "De");
    }

    // Same real document, a narrower real merge: "Cheque" ends at x=366.09, "Deposit" starts at
    // x=395.46 (29.37pt gap) -- font 7pt, so 29.37/7 = 4.2x, the SMALLEST of the real gaps this
    // splitter was evidenced against. Confirms the threshold has real margin, not just enough for
    // the widest case.
    @Test
    void split_separatesTwoHeaderCells_atTheSmallestRealGapMeasured() {
        var glyphs = List.of(
                glyph("e", 360.25f, 364.14f, 7f), glyph(" ", 364.14f, 366.09f, 7f),
                glyph("D", 395.46f, 400.51f, 7f), glyph("e", 400.51f, 404.41f, 7f));

        var result = GlyphRunSplitter.split(glyphs);

        assertThat(texts(result)).containsExactly("e ", "De");
    }

    @Test
    void split_keepsOrdinaryWordsTogether_whenSeparatedOnlyByARealSpaceGlyph() {
        var glyphs = List.of(
                glyph("D", 43.72f, 48.77f, 7f), glyph("a", 48.77f, 52.67f, 7f),
                glyph("t", 52.67f, 55.00f, 7f), glyph("e", 55.00f, 58.89f, 7f),
                glyph(" ", 58.89f, 60.47f, 7f), glyph("B", 60.47f, 65.52f, 7f));

        var result = GlyphRunSplitter.split(glyphs);

        assertThat(texts(result)).containsExactly("Date B");
    }

    @Test
    void split_returnsOneSegment_forASingleGlyph() {
        assertThat(texts(GlyphRunSplitter.split(List.of(glyph("X", 10f, 15f, 10f)))))
                .containsExactly("X");
    }

    @Test
    void split_returnsEmpty_forEmptyInput() {
        assertThat(GlyphRunSplitter.split(List.of())).isEmpty();
    }

    /** A gap right at 3x font size (the boundary itself) must NOT split -- only a gap strictly
     *  greater than the threshold does, matching the {@code >} comparison in the implementation. */
    @Test
    void split_doesNotSplit_atExactlyTheThreshold() {
        var glyphs = List.of(glyph("A", 0f, 10f, 10f), glyph("B", 40f, 50f, 10f)); // gap = 30 = 3x10

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("AB");
    }

    @Test
    void split_splits_justOverTheThreshold() {
        var glyphs = List.of(glyph("A", 0f, 10f, 10f), glyph("B", 40.1f, 50.1f, 10f)); // gap = 30.1 > 3x10

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("A", "B");
    }

    // --- A reference printed in its own column, closer than a column gap --------------------------
    // Shapes measured on a real Indian Overseas Bank statement (values here are synthetic): the
    // narration's last line and the reference column's value arrived as ONE PDFBox run, with a
    // 1.63x and a 2.96x font-size jump before the reference -- under the 3x column threshold.

    /** Lays {@code text} out from {@code x}, one 5pt advance per character, at a 9pt font. */
    private static List<GlyphRunSplitter.Glyph> word(String text, float x) {
        List<GlyphRunSplitter.Glyph> out = new java.util.ArrayList<>();
        for (char c : text.toCharArray()) {
            out.add(glyph(String.valueOf(c), x, x + 5f, 9f));
            x += 5f;
        }
        return out;
    }

    private static List<GlyphRunSplitter.Glyph> join(List<GlyphRunSplitter.Glyph> a, List<GlyphRunSplitter.Glyph> b) {
        List<GlyphRunSplitter.Glyph> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    @Test
    void split_separatesATrailingReference_atTheSmallerMeasuredGap() {
        var head = word("SAMPLE NAME ", 100f);          // ends at x=160
        var glyphs = join(head, word("S12345678", 160f + 1.63f * 9f));

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("SAMPLE NAME", "S12345678");
    }

    @Test
    void split_separatesATrailingReference_justUnderTheColumnThreshold() {
        var head = word("SAMPLE FEE ", 100f);           // ends at x=155
        var glyphs = join(head, word("S12345678", 155f + 2.96f * 9f));

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("SAMPLE FEE", "S12345678");
    }

    /** The blank printed before the jump belongs to neither piece: left on the narration, it became
     *  a double space once the narration's next line was joined on with a space of its own. The
     *  piece's measured right edge moves back to its last inked glyph with it. */
    @Test
    void split_dropsTheBlankBeforeAReference_fromTheNarrationPiece() {
        var glyphs = join(word("SAMPLE FEE ", 100f), word("S12345678", 155f + 2.96f * 9f));

        var narration = GlyphRunSplitter.split(glyphs).get(0);

        assertThat(narration.get(narration.size() - 1).endX()).isEqualTo(150f);
    }

    /** The 3x column split is unchanged: its pieces keep their glyphs exactly as printed. */
    @Test
    void split_keepsTheTrailingBlank_onAColumnSplit() {
        var glyphs = join(word("SAMPLE ", 100f), word("NEXT", 135f + 3.5f * 9f));

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("SAMPLE ", "NEXT");
    }

    @Test
    void split_keepsAWordTogether_whenWhatFollowsTheGapIsNotAReference() {
        var glyphs = join(word("SAMPLE ", 100f), word("PAYMENT", 135f + 1.63f * 9f));

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("SAMPLE PAYMENT");
    }

    @Test
    void split_keepsTheRunTogether_whenTheReferenceIsNotTheLastWord() {
        var glyphs = join(word("SAMPLE ", 100f), word("S12345678 FEE", 135f + 1.63f * 9f));

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("SAMPLE S12345678 FEE");
    }

    @Test
    void split_keepsAReferenceTogether_whenItFollowsAnOrdinarySpace() {
        var glyphs = word("UPI S12345678", 100f);

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("UPI S12345678");
    }

    /** A gap of exactly one font size does not split; only a wider one does. */
    @Test
    void split_keepsAReferenceTogether_atExactlyOneFontSize() {
        var glyphs = join(word("SAMPLE ", 100f), word("S12345678", 135f + 9f));

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("SAMPLE S12345678");
    }

    @Test
    void split_keepsATooShortToken_withItsNarration() {
        var glyphs = join(word("SAMPLE ", 100f), word("S1234", 135f + 1.63f * 9f));

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("SAMPLE S1234");
    }

    @Test
    void split_keepsAWordWithoutADigit_withItsNarration() {
        var glyphs = join(word("SAMPLE ", 100f), word("REFERENCE", 135f + 1.63f * 9f));

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("SAMPLE REFERENCE");
    }
}
