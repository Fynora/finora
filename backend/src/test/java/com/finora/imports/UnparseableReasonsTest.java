package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnparseableReasonsTest {

    @Test
    void everyFixedReasonIsKeptAsItIs() {
        // Engine-authored text with no cell in it: already safe, and keeping it unchanged means
        // summaries stored before and after this class agree on these keys.
        for (String reason : List.of(UnparseableReasons.NO_HEADER_ROW, UnparseableReasons.NO_TRANSACTION_TABLE,
                UnparseableReasons.UNRECOGNIZED_COLUMN_VALUE, UnparseableReasons.UNPARSEABLE_RECOGNIZED_AMOUNT,
                UnparseableReasons.BALANCE_MOVED_WITHOUT_AN_AMOUNT, UnparseableReasons.NO_DATE_COLUMN,
                UnparseableReasons.NO_AMOUNT_COLUMN, UnparseableReasons.REJECTED_AFTER_PARSE)) {
            assertThat(UnparseableReasons.summaryKey(reason)).isEqualTo(reason);
        }
    }

    @Test
    void aQuotedDateCellIsReplacedByItsShape() {
        assertThat(UnparseableReasons.summaryKey(UnparseableReasons.dateUnreadable("05-Sept-2026")))
                .isEqualTo("Date value \"99-XXXX-9999\" didn't match any known date format");
    }

    @Test
    void aQuotedAmountCellIsReplacedByItsShape() {
        assertThat(UnparseableReasons.summaryKey(UnparseableReasons.amountUnreadable("1,500/-")))
                .isEqualTo("Amount value \"9,999/-\" didn't match any known numeric format");
    }

    @Test
    void theCellIsTrimmedBeforeItIsShapedAsTheParseDiagnosticsKeyIs() {
        // explainFailure quotes the cell untrimmed; failureReasonKey shapes it trimmed. One format
        // must not split into two keys over a stray space.
        String padded = UnparseableReasons.summaryKey(UnparseableReasons.dateUnreadable("  31/13/2026 "));
        assertThat(padded).isEqualTo(UnparseableReasons.summaryKey(UnparseableReasons.dateUnreadable("31/13/2026")));
        assertThat(new TransactionNormalizer(null, null, TestRuleEngines.empty())
                .failureReasonKey(Map.of("date", "  31/13/2026 ", "amount", "1.00")))
                .isEqualTo(TransactionNormalizer.REASON_DATE_UNPARSEABLE_PREFIX + "99/99/9999");
    }

    @Test
    void aCellContainingQuotesIsMaskedWhole() {
        String key = UnparseableReasons.summaryKey(UnparseableReasons.dateUnreadable("SAMPLE \"QUOTED\" NOTE"));

        assertThat(key).doesNotContain("SAMPLE").doesNotContain("QUOTED").doesNotContain("NOTE");
        assertThat(key).isEqualTo("Date value \"XXXXXX \"XXXXXX\" XXXX\" didn't match any known date format");
    }

    @Test
    void aLongCellIsCutAsEveryOtherShapeIs() {
        String notice = "THIS IS A SAMPLE NOTICE LINE THAT LANDED IN THE DATE COLUMN";

        assertThat(UnparseableReasons.summaryKey(UnparseableReasons.dateUnreadable(notice)))
                .isEqualTo("Date value \"" + ValueShape.of(notice) + "\" didn't match any known date format")
                .contains("~");
    }

    @Test
    void anEmptyQuotedCellStillMatchesItsTemplate() {
        assertThat(UnparseableReasons.summaryKey(UnparseableReasons.amountUnreadable("")))
                .isEqualTo("Amount value \"\" didn't match any known numeric format");
    }

    @Test
    void aMissingReasonIsUnspecifiedAsBefore() {
        assertThat(UnparseableReasons.summaryKey(null)).isEqualTo("unspecified");
    }

    @Test
    void anyOtherTextIsUnclassifiedAndNeverKept() {
        // The reason this is an allowlist and not a quote scrubber: a value that is not quoted would
        // pass a scrubber untouched.
        assertThat(UnparseableReasons.summaryKey("Bad value 100000000001 in row"))
                .isEqualTo(UnparseableReasons.UNCLASSIFIED);
        assertThat(UnparseableReasons.summaryKey("Date value \"31/13/2026\" was odd"))
                .isEqualTo(UnparseableReasons.UNCLASSIFIED);
        assertThat(UnparseableReasons.summaryKey("")).isEqualTo(UnparseableReasons.UNCLASSIFIED);
    }

    @Test
    void applyingItToAKeyItAlreadyProducedChangesNothing() {
        // The coverage report applies it to stored keys of any age; a key that is already safe must
        // come back exactly as it went in, or old and new summaries would stop grouping together.
        List<String> reasons = List.of(
                UnparseableReasons.dateUnreadable("05-Sept-2026"),
                UnparseableReasons.amountUnreadable("1,500/-"),
                UnparseableReasons.dateUnreadable("THIS IS A SAMPLE NOTICE LINE THAT LANDED IN THE DATE COLUMN"),
                UnparseableReasons.dateUnreadable("राम 🎂 ₹½"),
                UnparseableReasons.NO_DATE_COLUMN,
                "something the allowlist does not know",
                "");
        for (String reason : reasons) {
            String once = UnparseableReasons.summaryKey(reason);
            assertThat(UnparseableReasons.summaryKey(once)).as("summaryKey of a key it produced").isEqualTo(once);
        }
        assertThat(UnparseableReasons.summaryKey(null)).isEqualTo(UnparseableReasons.UNSPECIFIED);
        assertThat(UnparseableReasons.summaryKey(UnparseableReasons.UNSPECIFIED)).isEqualTo(UnparseableReasons.UNSPECIFIED);
    }

    @Test
    void theMarkerMigrationV282LeftIsKeptAsItIs() {
        String redactedDate = "Date value \"(redacted)\" didn't match any known date format";
        String redactedAmount = "Amount value \"(redacted)\" didn't match any known numeric format";

        assertThat(UnparseableReasons.summaryKey(redactedDate)).isEqualTo(redactedDate);
        assertThat(UnparseableReasons.summaryKey(redactedAmount)).isEqualTo(redactedAmount);
    }

    @Test
    void theReviewScreenReasonsTheNormaliserGivesAreAllOnTheAllowlist() {
        // explainFailure is the source of the quoting reasons; every verdict it can reach must map to
        // a named key, not fall through to UNCLASSIFIED.
        TransactionNormalizer normalizer = new TransactionNormalizer(null, null, TestRuleEngines.empty());
        List<Map<String, String>> rows = List.of(
                Map.of("narration", "x"),                              // no date column
                Map.of("date", "", "amount", "1.00"),                  // date column empty
                Map.of("date", "31/13/2026", "amount", "1.00"),        // date unreadable
                Map.of("date", "01/07/2026", "narration", "x"),        // no amount column
                Map.of("date", "01/07/2026", "amount", ""),            // amount column empty
                Map.of("date", "01/07/2026", "amount", "2OO.OO"));     // amount unreadable
        for (Map<String, String> row : rows) {
            String explanation = normalizer.explainFailure(row);
            assertThat(UnparseableReasons.summaryKey(explanation))
                    .as("summary key for a row the normaliser refused")
                    .isNotEqualTo(UnparseableReasons.UNCLASSIFIED);
            for (String cell : row.values()) {
                if (cell.length() >= 3) assertThat(UnparseableReasons.summaryKey(explanation)).doesNotContain(cell);
            }
        }
    }
}
