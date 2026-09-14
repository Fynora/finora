package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reports a transaction description that looks like it absorbed text that was never part of the
 * narration -- a page footer, a payment-instructions sentence, a disclaimer -- via the ordinary
 * trailing-continuation merge that legitimately assembles a wrapped narration across several
 * physical lines.
 *
 * <p><b>Why this exists.</b> A real Axis Bank credit-card statement's page-1 footer ("Your cheque
 * should be payable to...", an ECS-registration sentence, a GST-registration line) merged into the
 * real transaction directly above it and reached a user's ledger, because nothing checked the
 * SHAPE of the resulting description -- every other signal (row count, statement period, balance)
 * was unaffected, since only one row's text was corrupted, not the transaction it represented.
 *
 * <p><b>Why not a keyword list.</b> Matching known boilerplate phrasing ("cheque should be
 * payable", "ECS", "GST registration") only catches the specific banks and sentences already seen
 * -- exactly the failure mode of the bug this exists to catch, which itself came from a footer
 * sentence nothing had been evidenced against yet. What generalizes instead is the SHAPE of a
 * merged-in footer: a genuine transaction narration is compact reference text (a UPI string, a
 * merchant name, a cheque number); bank boilerplate is prose, with sentence breaks a narration
 * essentially never has, and this document's own committed real narrations were uniformly ~47
 * characters where the corrupted one ran to several times that. Both signals generalize to any
 * bank's wording; neither is specific to this one.
 *
 * <p><b>Self-calibrated per section, not a fixed byte count.</b> "Unusually long" is judged against
 * the SECTION's own median description length, because normal narration length varies hugely by
 * bank and product (a short ATM code vs. a long NEFT reference) -- a fixed threshold would either
 * miss short-narration documents or cry wolf on long-narration ones. Requires both an outlier
 * length AND a sentence-break shape together, deliberately: length alone would flag documents whose
 * genuine narrations are just long (a real NEFT/IMPS reference string can run past 100 characters
 * with no punctuation at all), and sentence-shape alone would flag a merchant name that happens to
 * contain a period. Real bank boilerplate reliably has both; a real long reference reliably has
 * only one.
 *
 * <p>Reports ambiguity, not certainty -- same posture as {@link ColumnAmbiguityValidator}: a long,
 * prose-shaped description MAY be entirely genuine (a bank that prints an unusually verbose
 * narration by design). The claim is only that this row's shape does not match its own document's
 * peers closely enough to assume it was untouched, and that a person reviewing the import should
 * look at it.
 */
@Component
public class DescriptionCorruptionValidator {

    /** Stable machine identifier -- clients group and explain by it, so it must not track wording. */
    public static final String RULE = "DESCRIPTION_CORRUPTION";

    /** Below this many non-blank descriptions, a median is not a baseline -- a 2-row statement
     *  where one description happens to be longer than the other says nothing about either being
     *  wrong. */
    private static final int MIN_ROWS_FOR_BASELINE = 5;

    /** A candidate must be at least this many times its section's own median length. Relative to
     *  the document's own peers, not a fixed byte count -- see the class doc for why. */
    private static final int LENGTH_MULTIPLE = 3;

    /** ...and also at least this many characters outright, so a section whose median is tiny (a
     *  handful of characters) does not flag a merely slightly-longer-than-usual row as if it were
     *  several sentences of boilerplate. Chosen well above the longest genuine narration observed
     *  in this codebase's committed real-document traces (the longest is under 90 characters). */
    private static final int MIN_ABSOLUTE_LENGTH = 120;

    /** A period or similar terminator, whitespace, then a capital letter -- the mechanical shape of
     *  "one sentence ends and a new one, capitalized, begins". Real transaction narration (a UPI
     *  string, a merchant name, a cheque/reference number) essentially never has this shape; bank
     *  boilerplate prose reliably does, regardless of which bank wrote it. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("[.!?]\\s+[A-Z]");

    /** One row whose description looks like it absorbed text that was never part of it. */
    private record Outlier(int rowIndex, int length, int medianLength, int sentenceBreaks) {
        Map<String, Object> toDetails() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowIndex", rowIndex);
            m.put("length", length);
            m.put("sectionMedianLength", medianLength);
            m.put("sentenceBreaks", sentenceBreaks);
            return m;
        }
    }

    public ImportDto.VerificationFinding check(List<StagedRow> rows) {
        Map<String, Object> details = new LinkedHashMap<>();

        List<Integer> lengths = new ArrayList<>();
        if (rows != null) {
            for (StagedRow row : rows) {
                String description = row.description();
                if (description != null && !description.isBlank()) lengths.add(description.trim().length());
            }
        }

        if (lengths.size() < MIN_ROWS_FOR_BASELINE) {
            details.put("reason", "Fewer than " + MIN_ROWS_FOR_BASELINE + " non-blank descriptions "
                    + "-- too small a sample to know what this section's own normal length looks like.");
            return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
        }

        int median = median(lengths);
        int threshold = Math.max(MIN_ABSOLUTE_LENGTH, median * LENGTH_MULTIPLE);

        List<Outlier> found = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String description = rows.get(i).description();
            if (description == null) continue;
            String trimmed = description.trim();
            if (trimmed.length() < threshold) continue;
            int breaks = countSentenceBreaks(trimmed);
            if (breaks < 1) continue;
            found.add(new Outlier(i, trimmed.length(), median, breaks));
        }

        details.put("rowsChecked", rows.size());
        details.put("sectionMedianLength", median);

        if (found.isEmpty()) {
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        details.put("outliers", found.stream().map(Outlier::toDetails).toList());
        details.put("explanation", found.size() == 1
                ? "One transaction's description is far longer than this statement's own typical "
                  + "narration and reads like more than one sentence -- it may have absorbed text "
                  + "that was never part of it, such as a page footer or disclaimer."
                : found.size() + " transactions' descriptions are far longer than this statement's "
                  + "own typical narration and read like more than one sentence -- they may have "
                  + "absorbed text that was never part of them, such as a page footer or disclaimer.");
        return new ImportDto.VerificationFinding(RULE, "WARNING", details);
    }

    private static int countSentenceBreaks(String text) {
        var matcher = SENTENCE_BREAK.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    /** Median rather than mean -- robust to the exact outlier this rule is looking for, which a
     *  mean would itself be dragged upward by. */
    private static int median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(mid - 1) + sorted.get(mid)) / 2
                : sorted.get(mid);
    }
}
