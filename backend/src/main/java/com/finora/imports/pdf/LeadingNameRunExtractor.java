package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.util.BankRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ACCOUNT_HOLDER_FROM_LEADING_RUN. The account holder printed as the leftmost run of a line near the
 * top of page 0 with unrelated prose to its right on the same physical line and no label anywhere.
 *
 * <p>A real ICICI credit-card statement prints the holder's name at the left margin and, on the
 * same line, a marketing sentence starting at mid-page. {@link PdfTableLocator} joins a line's runs
 * with single spaces into one auxiliary line, so {@link PdfMetadataExtractor}'s line-based holder
 * rules see "MS <name> Download the mobile app to -" and none of them can tell where the name
 * ends. Read at run level the name is the line's own leftmost run, complete and alone.
 *
 * <p>An anchor-position extractor in the Group F sense, not a shape guess: the position (page 0,
 * top third, leftmost run of its line) is the anchor, and the run still has to pass the same
 * name-shape, title-word and bank-name rejections the line-based leading-name rule applies.
 * Callers use it only when the line-based extractor found no holder at all.
 */
public final class LeadingNameRunExtractor {
    private LeadingNameRunExtractor() {}

    /** Same shape as PdfMetadataExtractor.LEADING_NAME_LINE: optional courtesy title, 2-4
     *  capitalised words (single-letter initials allowed), nothing else. */
    static final Pattern NAME_RUN = Pattern.compile(
            "^(?:(?i:mr|mrs|ms|miss|mx|dr|m/s)\\.?\\s+)?[A-Z][A-Za-z]*(?:\\s+[A-Z][A-Za-z]*){1,3}\\.?$");

    /** The top band of page 0, in points from the page top (an A4 page is 842pt tall, so this is
     *  its top third): below it a name-shaped run is table content or a footer. Absolute rather
     *  than a fraction of the printed extent, which on a short single-page document put the whole
     *  header block outside "the top third" of its own content. */
    private static final float TOP_BAND_POINTS = 280f;

    public static String extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return null;
        float limit = TOP_BAND_POINTS;
        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        for (List<PositionedText> row : rows) {
            if (row.isEmpty() || row.get(0).pageIndex() != 0) continue;
            if (row.get(0).y() > limit) break;
            PositionedText leftmost = row.stream().filter(t -> !t.text().isBlank())
                    .min(Comparator.comparing(PositionedText::x)).orElse(null);
            if (leftmost == null) continue;
            String candidate = leftmost.text().trim();
            if (!NAME_RUN.matcher(candidate).matches()) continue;
            if (!PdfMetadataExtractor.containsNoLeadingTitleWordStatic(candidate)) continue;
            if (!BankRegistry.UNKNOWN_ID.equals(BankRegistry.detect("", List.of(candidate)).id())) continue;
            if (ctx != null) ctx.record("ACCOUNT_HOLDER_FROM_LEADING_RUN");
            return candidate;
        }
        return null;
    }
}
