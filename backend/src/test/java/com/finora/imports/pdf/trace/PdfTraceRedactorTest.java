package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PositionedText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The redactor is a privacy control, so it gets tested like one: with input that WOULD be a
 * disclosure if it survived. Every value below is invented -- the point is that each is
 * structurally indistinguishable from what a real statement carries, so if the redactor lets one
 * through it would let the real thing through too.
 */
class PdfTraceRedactorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JANE EXAMPLE                          | XXXX XXXXXXX",
            "Flat No 404, Sample Gardens           | Xxxx No 999, Xxxxxx Xxxxxxx",
            "Account No : 50100999999999           | Account No : 99999999999999",
            "jane.example@sample.test              | xxxx.xxxxxxx@xxxxxx.xxxx",
            "Cust ID : 288080705                   | Cust ID : 999999999",
            // Only the counterparty is masked. "UPI" and "PAYMENT FROM PHONE" are instrument and
            // boilerplate -- structural, and identifying nobody once the name between them is gone.
            "UPI-JANE EXAMPLE-PAYMENT FROM PHONE   | UPI-XXXX XXXXXXX-PAYMENT FROM PHONE",
    })
    void personalDataIsMaskedCharacterForCharacter(String input, String expected) {
        // Length is preserved deliberately: it is what makes a narration wrap onto a second line,
        // which is the structure a trace exists to reproduce.
        assertThat(redact(input)).isEqualTo(expected.trim());
        assertThat(redact(input)).hasSameSizeAs(input.trim());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // Column headers and metadata labels -- redacting these would leave a trace unable to
            // exercise the header detection it was captured to cover.
            "Txn Date Narration Withdrawals Deposits Closing Balance",
            "Statement From : 03/07/2026 To 31/07/2026",
            "RTGS/NEFT IFSC : HDFC0XXXXXX",
            "Opening Balance Debit Amount Credit Amount Closing Balance",
            "0.00 538.00 25,000.00 24,462.00",
            // Named-month dates with spaces (a real card statement's rows, a real savings export's
            // Date/Value Date cells) -- preserved whole, or every date row loses its anchor.
            "06 Jul 26 XXXXXXXX 1,234.00 Cr",
            "May 04 May 03 UPI 999999999999",
            "16 February 2026",
    })
    void statementStructureSurvivesUntouched(String structuralLine) {
        assertThat(redact(structuralLine)).isEqualTo(structuralLine);
    }

    @Test
    void aSpacedNamedMonthDate_keepsOnlyTheDate_andMasksTheNarrationAroundIt() {
        assertThat(redact("06 Jul 26 SAMPLE ZORBLAT 000000000001 1,234.00 Cr"))
                .isEqualTo("06 Jul 26 XXXXXX XXXXXXX 999999999999 1,234.00 Cr");
        // A month name with no day number beside it is an ordinary word and is still masked.
        assertThat(redact("Mayfair Stores")).isEqualTo("Xxxxxxx Xxxxxx");
        assertThat(redact("May Flowers")).isEqualTo("Xxx Xxxxxxx");
        // A word that merely begins with a month abbreviation is not a month.
        assertThat(redact("Martin 12 Marketing")).isEqualTo("Xxxxxx 99 Xxxxxxxxx");
        assertThat(redact("12 Marketing")).isEqualTo("99 Xxxxxxxxx");
    }

    @Test
    void aPageFooterKeepsItsShapeThoughNotItsNumbers() {
        // Deliberate: PdfTableLocator's PAGE_FOOTER matches on the words "page ... of" and never
        // reads the digits, so preserving them would buy no fidelity while widening what a trace
        // is allowed to carry. Masking every standalone number keeps one simple rule.
        assertThat(redact("Page 1 of 2")).isEqualTo("Page 9 of 9");
    }

    @Test
    void anIfscKeepsItsBankPrefixAndLosesItsBranchCode() {
        // The prefix is what bank detection reads; the branch code identifies a specific branch and
        // is the part worth removing. Masking the whole token would make every captured trace
        // useless for regression-testing detection.
        assertThat(redact("RTGS/NEFT IFSC: HDFC0001234 MICR: 500240002")) // synthetic-ok invented IFSC
                .isEqualTo("RTGS/NEFT IFSC: HDFC0XXXXXX MICR: 999999999");
    }

    @Test
    void datesAndAmountsAreKept_soATraceCanStillTestArithmetic() {
        assertThat(redact("10/07/2026 25,000.00 (Cr) 24,462.00"))
                .isEqualTo("10/07/2026 25,000.00 (Cr) 24,462.00");
    }

    @Test
    void anUnrecognizedTokenIsMasked_becauseTheAllowlistMustFailClosed() {
        // The whole design rests on this: a denylist fails OPEN on the case nobody anticipated, and
        // here that failure mode is a customer's data in a public repository. An unfamiliar word is
        // redacted even when it turns out to have been harmless.
        assertThat(redact("Zorbulax Quingle")).isEqualTo("Xxxxxxxx Xxxxxxx");
    }

    @Test
    void coordinatesAreNeverAltered() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText("JANE EXAMPLE", 35.5f, 720.25f, 3)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("XXXX XXXXXXX");
            assertThat(run.x()).isEqualTo(35.5f);
            assertThat(run.y()).isEqualTo(720.25f);
            assertThat(run.pageIndex()).isEqualTo(3);
        });
    }

    /**
     * A run redaction left byte-identical keeps its measured width.
     *
     * <p>This is the whole point of capturing width at all: {@code PdfTableLocator}'s right-edge
     * correction is guarded on {@code width() > 0} and only ever reads pure numbers and header
     * labels -- exactly the runs the allowlist preserves verbatim. Zeroing them made the capability
     * unreachable from any trace, and the width discloses nothing, because the text it measures is
     * printed in cleartext on the same line of the same file.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "24,462.00   | 41.02",   // amount -- the RIGHT_ALIGNED_AMOUNTS input
            "0.00        | 15.57",   // the short amount that mis-bucketed on the real statement
            "Narration   | 44.91",   // structural header label -- the headerEnds input
            "Closing Balance | 72.34",
            "10/07/2026  | 49.83",   // date
            "Page 9 of 9 | 40.12",   // already-masked shape: unchanged by redaction, so width stays
    })
    void anUnmaskedRunKeepsItsMeasuredWidth(String text, float width) {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText(text.trim(), 300f, 100f, 0, width)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).as("this input must be preserved verbatim").isEqualTo(text.trim());
            assertThat(run.width()).isEqualTo(width);
            assertThat(run.endX()).isEqualTo(300f + width);
        });
    }

    /**
     * A run redaction changed loses its width, whatever it was measured at.
     *
     * <p>For digits this leaks nothing anyway (digit glyphs are uniform-width in every text font), but
     * for letters a width constrains the multiset of masked characters. The pipeline does read
     * these widths (see {@code PdfTraceRedactor#redact}: dropping them changes some readings); the
     * disclosure is declined anyway.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JANE EXAMPLE     | 61.34",   // a name
            "50100999999999   | 77.84",   // an account number
            "HDFC0001234      | 58.71",   // synthetic-ok invented IFSC -- prefix kept, branch code masked, so it CHANGED
            "Zorbulax         | 44.02",   // an unrecognised word
            "Flat No 404, Sample Gardens | 118.90",
    })
    void aMaskedRunLosesItsWidth(String text, float width) {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText(text.trim(), 300f, 100f, 0, width)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).as("this input must have been changed by redaction")
                    .isNotEqualTo(text.trim());
            assertThat(run.width()).isZero();
            assertThat(run.endX()).isEqualTo(run.x());
        });
    }

    /** A partially masked run is a masked run: one hidden character is enough to drop the width,
     *  because the width describes the original glyphs and would no longer match the text beside it. */
    @Test
    void aRunWithOneMaskedTokenAmongStructuralOnesLosesItsWidth() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(List.of(
                new PositionedText("UPI-JANE EXAMPLE-PAYMENT FROM PHONE", 50f, 100f, 0, 180.5f)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("UPI-XXXX XXXXXXX-PAYMENT FROM PHONE");
            assertThat(run.width()).isZero();
        });
    }

    /**
     * See {@code TRAILING_CURRENCY_MARKER_LETTER}'s own doc comment: a real Kotak Mahindra Bank
     * credit-card statement's amount-column header is one single run reading "(Rs.)R" -- the
     * trailing "R" a rupee-glyph rendering artifact, not a recognizable word. Masking it alone used
     * to zero the WHOLE run's width (redaction's own all-or-nothing rule for a run whose text
     * changed at all), which broke PdfTableLocator's RIGHT_ALIGNED_AMOUNTS correction for that
     * column and staged every real purchase row on that statement as unparseable.
     */
    @Test
    void aCurrencyMarkersTrailingLetterSurvivesWhole_soItsRunKeepsItsRealWidth() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText("(Rs.)R", 545.59f, 498.50f, 0, 24.41f)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("(Rs.)R");
            assertThat(run.width()).isEqualTo(24.41f);
        });
    }

    /** The exception above is scoped to a currency marker's own closing paren, not any bare letter
     *  anywhere -- a stray single-letter initial elsewhere in the document must still be masked. */
    @Test
    void aBareLetterNotFollowingACurrencyMarker_isStillMasked() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText("JANE R SMITH", 50f, 100f, 0, 90f)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("XXXX X XXXXX");
            assertThat(run.width()).isZero();
        });
    }

    @Test
    void redactLineMasksExactlyAsARunWouldBe() {
        String line = "UPI Asha Verma 9876543210 1,234.56 12/07/2026 Balance"; // synthetic-ok
        PositionedText run = new PositionedText(line, 0f, 0f, 0, 10f);
        assertThat(PdfTraceRedactor.redactLine(line))
                .isEqualTo(PdfTraceRedactor.redact(List.of(run)).get(0).text());
        assertThat(PdfTraceRedactor.redactLine(line)).doesNotContain("Asha", "Verma", "9876543210"); // synthetic-ok
        assertThat(PdfTraceRedactor.redactLine(line)).contains("1,234.56", "12/07/2026", "Balance");
    }

    /**
     * Text in another script is masked like Latin text. Before this, a token made only of
     * non-ASCII letters or digits was returned verbatim, and combining marks were copied out as
     * if they were punctuation. Every value is invented; written as escapes so the expected masks
     * can be counted by code point.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // A two-word Devanagari name: letters, a vowel sign (a spacing mark), a virama (a
            // non-spacing mark). One x per code point, marks included.
            "\u0905\u092E\u093F\u0924 \u0936\u0930\u094D\u092E\u093E | xxxx xxxxx",   // synthetic-ok
            // A Tamil word, with two viramas.
            "\u0BB5\u0BA3\u0B95\u0BCD\u0B95\u0BAE\u0BCD | xxxxxxx",   // synthetic-ok
            // A ten-digit number in Devanagari digits.
            "\u0969\u0968\u096D\u096E\u096F\u0966\u0967\u0968\u0969\u096A | 9999999999",   // synthetic-ok
            // Full-width Latin letters.
            "\uFF21\uFF33\uFF28\uFF21 | XXXX",   // synthetic-ok
            // Accented Latin letters with no plain ASCII letter beside them.
            "\u00C0\u00C9\u00CE \u00D4\u00DB | XXX XX",   // synthetic-ok
            // Letters outside the Basic Multilingual Plane (mathematical bold): one X per code
            // point, not one per UTF-16 unit.
            "\uD835\uDC00\uD835\uDC12\uD835\uDC07\uD835\uDC00 | XXXX",   // synthetic-ok
            // A Devanagari word inside an otherwise kept line: only the word changes.
            "UPI \u0905\u092E\u093F\u0924 1,234.00 Cr | UPI xxxx 1,234.00 Cr",   // synthetic-ok
    })
    void textInAnotherScriptIsMaskedCodePointForCodePoint(String input, String expected) {
        String redacted = redact(input);
        assertThat(redacted).isEqualTo(expected.trim());
        assertThat(redacted.codePointCount(0, redacted.length()))
                .isEqualTo(input.trim().codePointCount(0, input.trim().length()));
        assertThat(redacted.codePoints().filter(cp -> cp > 127)).isEmpty();
    }

    /** A combining mark with no letter before it is still text: it is masked, not copied out. */
    @Test
    void aLoneCombiningMarkIsMasked() {
        assertThat(redact("Ref \u0301\u093F")).isEqualTo("Ref xx");
    }

    /** A vocabulary word spelled with a character that lower-cases to ASCII (the Kelvin sign) is
     *  not the vocabulary word: only plain ASCII is ever kept. */
    @Test
    void aVocabularyLookalikeOutsideAsciiIsMasked() {
        assertThat(redact("\u212Aotak")).isEqualTo("Xxxxx");
    }

    /** Non-ASCII punctuation and symbols are layout, not identity, and stay as they were. */
    @Test
    void nonAsciiPunctuationAndSymbolsAreKept() {
        assertThat(redact("\u20B9 1,234.00 \u2013 Balance")).isEqualTo("\u20B9 1,234.00 \u2013 Balance");
    }

    /** The one-code-point mask the blanked-copy comparison uses is the redactor's own: masking a
     *  word that no rule keeps gives the same characters one at a time. */
    @Test
    void maskOfIsWhatTheRedactorWritesForEachCodePoint() {
        assertThat(PdfTraceRedactor.maskOf('A')).isEqualTo('X');
        assertThat(PdfTraceRedactor.maskOf('a')).isEqualTo('x');
        assertThat(PdfTraceRedactor.maskOf('7')).isEqualTo('9');
        assertThat(PdfTraceRedactor.maskOf(0x096D)).isEqualTo('9');   // a Devanagari digit
        assertThat(PdfTraceRedactor.maskOf(0x093F)).isEqualTo('x');   // a Devanagari vowel sign (a mark)
        assertThat(PdfTraceRedactor.maskOf('-')).isEqualTo('-');
        assertThat(PdfTraceRedactor.maskOf(' ')).isEqualTo(' ');
        assertThat(PdfTraceRedactor.maskOf(0x20B9)).isEqualTo(0x20B9);   // the rupee sign is a symbol
        String word = "Qwz7अि";   // synthetic-ok: no rule keeps it
        StringBuilder oneByOne = new StringBuilder();
        word.codePoints().forEach(cp -> oneByOne.appendCodePoint(PdfTraceRedactor.maskOf(cp)));
        assertThat(PdfTraceRedactor.redactLine(word)).isEqualTo(oneByOne.toString());
    }

    private String redact(String text) {
        return PdfTraceRedactor.redact(List.of(new PositionedText(text.trim(), 0f, 0f, 0)))
                .get(0).text();
    }
}
