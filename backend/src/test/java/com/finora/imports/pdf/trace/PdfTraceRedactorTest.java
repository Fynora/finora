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
            // An integer glued to a comma is not a value on its own, so it is masked with the words.
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
        assertThat(redact("Martin 12 Marketing")).isEqualTo("Xxxxxx 12 Xxxxxxxxx");
        assertThat(redact("12 Marketing")).isEqualTo("12 Xxxxxxxxx");
    }

    /** Up to version 4 every standalone number was masked, page numbers included. From version 5
     *  an integer of up to five digits is kept: reading takes amounts printed without decimals from
     *  them, and a six-digit integer (a PIN code) or longer still is masked. */
    @Test
    void aPageFooterKeepsItsNumbers() {
        assertThat(redact("Page 1 of 2")).isEqualTo("Page 1 of 2");
    }

    /**
     * Value shapes reading relies on, kept from redactor version 5: amounts with a currency prefix
     * or a glued Cr/Dr, glued and apostrophe-year dates, amount-shaped integers, and a lone C or D
     * direction marker. Every value is invented.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Rs.1,234.00", "Rs 500", "INR 1,234.00", "\u20B9500.00", "Rs1,234.00", "INR1,234.00", "Rs.500",
            "1,234.00Cr", "500.00DR", "1,234.00cr", "Rs.1,234.00Dr",
            "12Jul", "12Jul2026", "12jul26", "05Sept2026",
            "Jul '26", "12 Jul'26", "12 Jul '26", "12 Jul\u201926",
            "1,00,000", "12,345", "1,234,567", "12345", "7", "(500)", "-42",
            "C", "d",
    })
    void aValueShapeReadingNeedsIsKept(String line) {
        assertThat(redact(line)).isEqualTo(line.trim());
    }

    /**
     * Digit groups joined by single spaces, hyphens or dots are one number. A card, account or
     * mobile number printed in groups is six or more digits however short each group is, so every
     * group is masked; a short integer on its own is still kept. Every value is invented.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "4111 1111 1111 1111         | 9999 9999 9999 9999",       // synthetic-ok: a card number in groups
            "1234 5678 9012              | 9999 9999 9999",            // synthetic-ok: an account number in groups
            "98765 43210                 | 99999 99999",               // synthetic-ok: a mobile number with a space
            "Call 98765-43210 now        | Xxxx 99999-99999 xxx",      // synthetic-ok: hyphenated
            "123 456                     | 999 999",                   // six digits: the boundary
            "Rs.1234 5678                | Xx.9999 9999",              // synthetic-ok: a prefix does not rescue a group
            "12345 1234.56               | 99999 1234.56",             // the amount is kept, the joined integer is not
    })
    void digitGroupsThatFormSixOrMoreDigitsAreMaskedTogether(String input, String expected) {
        assertThat(redact(input)).isEqualTo(expected.trim());
        assertThat(PdfTraceRedactor.redactLine(input.trim())).isEqualTo(expected.trim());
    }

    /** Short integers that do not join into six digits keep behaving as before. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Rs 500", "Page 2 of 5", "10:45", "12 345", "No 404", "10:45 12:30",
    })
    void shortIntegersThatDoNotJoinAreStillKept(String line) {
        assertThat(redact(line)).isEqualTo(line.trim());
    }

    /**
     * A number split across runs on the same line is one number too: runs whose gap is at most one
     * character's width (or whose width is unknown) join their edge digit groups. Runs in separate
     * columns, or on another line, do not.
     */
    @Test
    void digitGroupsSplitAcrossAdjacentRunsAreMaskedTogether() {
        // synthetic-ok: an invented mobile number in two runs a space apart (5 chars over 25pt).
        assertThat(texts(PdfTraceRedactor.redact(List.of(
                new PositionedText("98765", 100f, 50f, 0, 25f),
                new PositionedText("43210", 127.5f, 50f, 0, 25f)))))
                .containsExactly("99999", "99999");
        // Three touching two-digit runs: 6 digits only together.
        assertThat(texts(PdfTraceRedactor.redact(List.of(
                new PositionedText("12", 100f, 50f, 0, 10f),
                new PositionedText("34", 110f, 50f, 0, 10f),
                new PositionedText("56", 120f, 50f, 0, 10f)))))
                .containsExactly("99", "99", "99");
        // A width that was never measured is treated as adjacent: masking more is the safe side.
        assertThat(texts(PdfTraceRedactor.redact(List.of(
                new PositionedText("98765", 100f, 50f, 0),
                new PositionedText("43210", 140f, 50f, 0)))))
                .containsExactly("99999", "99999");
        // Separate columns on one line: each stays a short kept integer.
        assertThat(texts(PdfTraceRedactor.redact(List.of(
                new PositionedText("98765", 100f, 50f, 0, 25f),
                new PositionedText("43210", 300f, 50f, 0, 25f)))))
                .containsExactly("98765", "43210");
        // A line that ends in a plain group followed by a line that starts with one is read as a
        // number wrapped at the line break, so both halves are masked (fail closed).
        assertThat(texts(PdfTraceRedactor.redact(List.of(
                new PositionedText("98765", 100f, 50f, 0, 25f),
                new PositionedText("43210", 300f, 50f, 0, 25f),
                new PositionedText("12345", 100f, 70f, 0, 25f)))))
                .containsExactly("98765", "99999", "99999");
    }

    private static List<String> texts(List<PositionedText> runs) {
        return runs.stream().map(PositionedText::text).toList();
    }

    /** What the version 5 keep rules still mask: six or more digits without grouping, a currency
     *  prefix on such a number, any glued suffix other than Cr/Dr, any other lone letter, a month
     *  name glued into a longer word, and a letter with punctuation that is not a lone token. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "123456         | 999999",
            "Asha 560034    | Xxxx 999999",       // synthetic-ok: a PIN code
            "1234567        | 9999999",
            "Rs.123456      | Xx.999999",
            "1,234.00Xy     | 9,999.99Xx",
            "1,234.00Crx    | 9,999.99Xxx",
            "E              | X",
            "CD             | XX",
            "C.             | X.",
            "12Junk         | 99Xxxx",
            "12,34          | 99,99",
            "1,2345         | 9,9999",
            "Jul 2026x      | Xxx 9999x",
    })
    void aShapeOutsideTheKeepRulesIsStillMasked(String input, String expected) {
        assertThat(redact(input)).isEqualTo(expected.trim());
    }

    @Test
    void anIfscKeepsItsBankPrefixAndLosesItsBranchCode() {
        // The prefix is what bank detection reads; the branch code identifies a specific branch and
        // is the part worth removing. Masking the whole token would make every captured trace
        // useless for regression-testing detection.
        assertThat(redact("RTGS/NEFT IFSC: HDFC0001234 MICR: 500240002")) // synthetic-ok invented IFSC
                .isEqualTo("RTGS/NEFT IFSC: HDFC0XXXXXX MICR: 999999999");
    }

    /** The punctuation at an IFSC's edges is kept, so the masked token is exactly as long as the
     *  real one: every other rule keeps length, and the copy's text lines up with the real text. */
    @Test
    void anIfscNextToPunctuationKeepsThePunctuationAndTheLength() {
        for (String[] c : new String[][] {
                {"NEFT HDFC0001234, Asha", "NEFT HDFC0XXXXXX, Xxxx"},   // synthetic-ok invented IFSC
                {"IFSC:HDFC0001234.", "IFSC:HDFC0XXXXXX."},            // synthetic-ok invented IFSC
                {"(HDFC0001234)", "(HDFC0XXXXXX)"},                     // synthetic-ok invented IFSC
        }) {
            assertThat(redact(c[0])).as(c[0]).isEqualTo(c[1]).hasSameSizeAs(c[0]);
        }
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

    /** An unchanged run keeps its coordinates exactly. A changed run keeps y and page, and its x is
     *  rounded to a whole point like its width: an exact x beside a rounded width would let the
     *  masked run's width be recovered from the gap to its neighbour. */
    @Test
    void coordinatesAreKeptAndAChangedRunsXIsRounded() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(List.of(
                new PositionedText("JANE EXAMPLE", 35.4f, 720.25f, 3),
                new PositionedText("Balance", 135.4f, 720.25f, 3)));

        assertThat(redacted.get(0).text()).isEqualTo("XXXX XXXXXXX");
        assertThat(redacted.get(0).x()).isEqualTo(35f);
        assertThat(redacted.get(0).y()).isEqualTo(720.25f);
        assertThat(redacted.get(0).pageIndex()).isEqualTo(3);
        assertThat(redacted.get(1).x()).isEqualTo(135.4f);
        assertThat(redacted.get(1).y()).isEqualTo(720.25f);
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
     * A run redaction changed keeps its width rounded to a whole point (redactor version 5).
     *
     * <p>Reading uses these widths: zeroing them changed the reading of 14 real statements (a card's
     * summary labels, the page-shows-a-space evidence, right-aligned amounts). Rounded, the width
     * still places the run's right edge to within half a point, while it says much less about which
     * letters were masked than the measured value would (letters differ in width by fractions of a
     * point).
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JANE EXAMPLE     | 61.34 | 61",   // a name
            "50100999999999   | 77.84 | 78",   // an account number
            "HDFC0001234      | 58.71 | 59",   // synthetic-ok invented IFSC -- prefix kept, branch code masked, so it CHANGED
            "Zorbulax         | 44.50 | 45",   // an unrecognised word; half a point rounds up
            "Flat No 404, Sample Gardens | 118.49 | 118",
            "Zorbulax         | 0     | 0",    // a run measured with no width keeps none
    })
    void aMaskedRunKeepsItsWidthRoundedToAWholePoint(String text, float width, float rounded) {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText(text.trim(), 300f, 100f, 0, width)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).as("this input must have been changed by redaction")
                    .isNotEqualTo(text.trim());
            assertThat(run.width()).isEqualTo(rounded);
            assertThat(run.endX()).isEqualTo(300f + rounded);
        });
    }

    /** A partially masked run is a masked run: one hidden character is enough for its width to be
     *  rounded, because the width describes the original glyphs, not the text beside it. */
    @Test
    void aRunWithOneMaskedTokenAmongStructuralOnesHasItsWidthRounded() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(List.of(
                new PositionedText("UPI-JANE EXAMPLE-PAYMENT FROM PHONE", 50f, 100f, 0, 180.3f)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("UPI-XXXX XXXXXXX-PAYMENT FROM PHONE");
            assertThat(run.width()).isEqualTo(180f);
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
            assertThat(run.width()).isEqualTo(90f);
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

    /**
     * An invisible format character (a zero-width joiner or non-joiner) inside a word is masked to x
     * like a mark, so a masked word no longer shows where its conjuncts or joins were. One outside
     * any token (before a word, after an amount, between spaces) carries nothing and is kept, so a
     * kept amount stays kept. Every value is invented.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\u0915\u094D\u200D\u0937 | xxxx",                     // synthetic-ok: a conjunct with ZWJ
            "Asha\u200CVerma | XxxxxXxxxx",                        // synthetic-ok: ZWNJ inside a word
            "UPI Asha\u200D\u200DVerma 1,234.00 | UPI XxxxxxXxxxx 1,234.00", // synthetic-ok
    })
    void aFormatCharacterInsideATokenIsMasked(String input, String expected) {
        String redacted = redact(input);
        assertThat(redacted).isEqualTo(expected.trim());
        assertThat(redacted.codePointCount(0, redacted.length()))
                .isEqualTo(input.trim().codePointCount(0, input.trim().length()));
        assertThat(redacted.codePoints().filter(cp -> Character.getType(cp) == Character.FORMAT)).isEmpty();
    }

    /** Since redactor version 5 a format character outside a token is masked too (it can carry a
     *  hidden message anywhere). An amount glued to one is not standing apart, so it is masked as a
     *  number rather than kept. */
    @Test
    void aFormatCharacterOutsideAnyTokenIsMasked() {
        assertThat(redact("\u200DBalance 1,234.00 \u200B Cr")).isEqualTo("xBalance 1,234.00 x Cr");
        assertThat(redact("1,234.00\u200B")).isEqualTo("9,999.99x");
        assertThat(PdfTraceRedactor.maskOf(0x200D)).isEqualTo('x');
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
