package com.finora.imports.pdf;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one same-line run of glyphs into separate segments wherever the horizontal jump between
 * two consecutive glyphs is wide enough to be a table-column boundary rather than ordinary
 * inter-word spacing.
 *
 * <p>Exists because PDFBox's own {@code PDFTextStripper} line-grouping (which decides how many
 * glyphs arrive together in one {@code writeString} call) is tuned for reading prose, not a table
 * header row -- confirmed on two real documents (a third-party-generated SBI statement and a real
 * Standard Chartered statement) where two visually distinct header cells on the SAME line,
 * separated by 29-185pt with no intervening glyph at all, arrived in a single {@code writeString}
 * call as one merged run ("Date(Value Ref No.", "  Value Description"). {@link PdfTableLocator}'s
 * column detection can only ever see whatever granularity reaches it as separate {@link
 * PositionedText} runs, so once two cells are merged before that point, no downstream layer can
 * ever split them back apart -- confirmed: this is exactly why both real documents located a
 * "table" with only 2-3 garbled columns instead of 6-7 and ended up with zero staged rows.
 *
 * <p>The threshold, {@code 3x} the current font size, is evidence-based, not invented: it sits
 * safely above the largest genuine same-word gap possible (a real inter-word space is a real
 * glyph with its own {@code x}, not a gap between two non-space glyphs, so its measured gap is
 * always {@code 0}) and safely below the smallest of the two real merges this fixes -- 29.4pt at
 * a 7pt font is {@code 4.2x}; the other two measured gaps are {@code 10.8x} and {@code 18.5x}.
 *
 * <p>One narrower split sits below that threshold: a jump wider than one font size whose remaining
 * glyphs spell a single reference-shaped token ({@link PdfTableLocator#looksLikeReferenceToken}).
 * Measured on a real Indian Overseas Bank statement, where a narration line and the reference
 * column's value arrived as one run with only a 1.63x and a 2.96x jump between them, so both
 * references were stored inside the description. A real reference column then claims the split
 * piece through {@code REFERENCE_KEPT_IN_ITS_COLUMN}. Across the whole real corpus (506 in-run
 * jumps of at least half a font size) those two are the only jumps followed by one
 * reference-shaped token, so nothing else splits: an ordinary word, a reference followed by more
 * narration, and a reference after a plain space (a gap of 0) all stay in one run.
 *
 * <p>Kept as a small, pure function independent of PDFBox's {@code TextPosition} (which has no
 * usable public constructor and cannot be hand-built in a test) specifically so the splitting
 * algorithm itself -- the part this class exists to get right -- can be unit-tested directly,
 * without needing to reproduce whatever PDF-generation quirk caused either real document's own
 * {@code writeString} call to merge in the first place.
 */
final class GlyphRunSplitter {

    private GlyphRunSplitter() {}

    /** One glyph's own horizontal extent, font size, and y -- the fields a caller needs both to
     *  make the splitting decision (x/endX/fontSize) and to place each resulting segment
     *  correctly afterward (y, carried through rather than reused from the un-split call's own
     *  first glyph -- two glyphs on what looks like one visual line can have measurably different
     *  y values, confirmed on the same real documents GlyphRunSplitter's own doc comment cites: a
     *  merged run's second cell sat 2.4pt off its first cell's y). {@code text} is carried through
     *  unsplit so a caller never has to re-associate a split segment with its own characters. */
    record Glyph(String text, float x, float endX, float y, float fontSize) {}

    static List<List<Glyph>> split(List<Glyph> glyphs) {
        List<List<Glyph>> segments = new ArrayList<>();
        List<Glyph> segment = new ArrayList<>();
        Glyph prev = null;
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph g = glyphs.get(i);
            if (prev != null) {
                float gap = g.x() - prev.endX();
                float fontSize = Math.max(g.fontSize(), prev.fontSize());
                if (gap > 3f * fontSize) {
                    segments.add(segment);
                    segment = new ArrayList<>();
                } else if (gap > fontSize && isReferenceToTheEnd(glyphs, i)) {
                    // The blank printed before the jump belongs to neither piece: left on the
                    // narration it became a double space once the next line was joined on.
                    while (!segment.isEmpty() && segment.get(segment.size() - 1).text().isBlank()) {
                        segment.remove(segment.size() - 1);
                    }
                    if (!segment.isEmpty()) segments.add(segment);
                    segment = new ArrayList<>();
                }
            }
            segment.add(g);
            prev = g;
        }
        if (!segment.isEmpty()) segments.add(segment);
        return segments;
    }

    /** True when the glyphs from {@code from} up to the next column-wide jump (or the end of the run)
     *  spell exactly one reference-shaped token -- see the class comment. */
    private static boolean isReferenceToTheEnd(List<Glyph> glyphs, int from) {
        StringBuilder tail = new StringBuilder();
        Glyph prev = null;
        for (int i = from; i < glyphs.size(); i++) {
            Glyph g = glyphs.get(i);
            if (prev != null && g.x() - prev.endX() > 3f * Math.max(g.fontSize(), prev.fontSize())) break;
            tail.append(g.text());
            prev = g;
        }
        return PdfTableLocator.looksLikeReferenceToken(tail.toString());
    }
}
