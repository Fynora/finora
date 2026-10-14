package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PositionedText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A number a customer typed or a bank printed about them -- a mobile, card, account or Aadhaar
 * number -- must never reach a blanked copy in clear, however it is grouped or split. Every probe
 * below is invented and is checked twice: the redactor must mask every digit of it, and the privacy
 * check must refuse it when the redactor is bypassed (raw runs straight in), so a future regression
 * in the redactor is refused rather than shipped.
 */
class NumberGroupPrivacyTest {

    private static final String NUMBER_SPLIT = "number split into groups";

    /** One run each. Every one of these is one number of six or more digits. */
    static Stream<String> oneRunProbes() {
        return Stream.of(
                "98765 43210",                              // synthetic-ok: no-break space
                "4111 1111 1111 1111",            // synthetic-ok: card, no-break spaces
                "98765 43210",                              // synthetic-ok: thin space
                "98765 43210",                              // synthetic-ok: narrow no-break space
                "98765–43210",                              // synthetic-ok: en dash
                "98765  43210",                                  // synthetic-ok: double space
                "98765 - 43210",                                 // synthetic-ok: spaced hyphen
                "98765/43210",                                   // synthetic-ok: slash
                "4111/1111/1111/1111",                           // synthetic-ok: card, slashes
                "98765:43210",                                   // synthetic-ok: colon
                "98765_43210",                                   // synthetic-ok: underscore
                "987,654,321",                                   // synthetic-ok: comma groups, nine digits
                "98,76,54,321",                                  // synthetic-ok: Indian comma groups
                "9,876,543,210",                                 // synthetic-ok: a mobile in comma groups
                "1234-56-7890",                                  // synthetic-ok: date-like but not a date
                "9876.54.3210",                                  // synthetic-ok: date-like but not a date
                "4111 1111 1111 1111",                           // synthetic-ok: card, spaces
                "1234 5678 9012",                                // synthetic-ok: Aadhaar shape, spaces
                "98765 43210",                                   // synthetic-ok: mobile, one space
                "Call 98765-43210 now");                         // synthetic-ok: hyphenated mobile in text
    }

    /** Several runs each: the number is split across runs on one printed line, or wraps. */
    static Stream<List<PositionedText>> multiRunProbes() {
        return Stream.of(
                // synthetic-ok: a card in four touching runs, groups ending in a no-break space
                List.of(run("4111 ", 100f, 50f, 25f), run("1111 ", 125f, 50f, 25f),
                        run("1111 ", 150f, 50f, 25f), run("1111", 175f, 50f, 20f)),
                // synthetic-ok: a mobile in two runs 1.5 characters apart (5pt per character)
                List.of(run("98765", 100f, 50f, 25f), run("43210", 132.5f, 50f, 25f)),
                // synthetic-ok: a card in four runs with two-character gaps
                List.of(run("4111", 100f, 50f, 20f), run("1111", 130f, 50f, 20f),
                        run("1111", 160f, 50f, 20f), run("1111", 190f, 50f, 20f)),
                // synthetic-ok: a mobile in two runs whose y differs by 0.6pt
                List.of(run("98765", 100f, 50f, 25f), run("43210", 127.5f, 50.6f, 25f)),
                // synthetic-ok: a UPI id wrapped onto the next printed line
                List.of(run("UPI/98765", 100f, 50f, 45f), run("43210@ybl", 100f, 62f, 45f)),
                // synthetic-ok: a mobile wrapped at a space onto the next printed line
                List.of(run("Mobile 98765", 100f, 50f, 60f), run("43210", 100f, 62f, 25f)),
                // synthetic-ok: a card wrapped between its groups
                List.of(run("Card 4111 1111", 100f, 50f, 70f), run("1111 1111", 100f, 62f, 45f)),
                // synthetic-ok: a mobile wrapped at a hyphen
                List.of(run("Call 98765-", 100f, 50f, 55f), run("43210 today", 100f, 62f, 55f)),
                // synthetic-ok: three short pieces over three lines
                List.of(run("Ref 12", 100f, 50f, 30f), run("34", 100f, 62f, 10f), run("56", 100f, 74f, 10f)));
    }

    @ParameterizedTest
    @MethodSource("oneRunProbes")
    void theRedactorMasksEveryDigitOfAGroupedNumber(String text) {
        String redacted = PdfTraceRedactor.redact(List.of(run(text, 100f, 50f, 0f))).get(0).text();
        assertThat(clearDigits(text, redacted)).as(text).isZero();
        assertThat(PdfTraceRedactor.redactLine(text)).as(text).isEqualTo(redacted);
        assertThat(TraceValidator.privacyKinds(PdfTraceRedactor.redact(List.of(run(text, 100f, 50f, 0f)))))
                .as("the masked copy itself must not be refused: " + text).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("oneRunProbes")
    void thePrivacyCheckRefusesAGroupedNumberTheRedactorLetThrough(String text) {
        assertThat(TraceValidator.privacyKinds(List.of(run(text, 100f, 50f, 0f)))).as(text).contains(NUMBER_SPLIT);
    }

    @ParameterizedTest
    @MethodSource("multiRunProbes")
    void theRedactorMasksEveryDigitOfANumberSplitAcrossRuns(List<PositionedText> runs) {
        List<PositionedText> redacted = PdfTraceRedactor.redact(runs);
        for (int i = 0; i < runs.size(); i++) {
            assertThat(clearDigits(runs.get(i).text(), redacted.get(i).text())).as(runs.get(i).text()).isZero();
        }
        assertThat(TraceValidator.privacyKinds(redacted)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("multiRunProbes")
    void thePrivacyCheckRefusesANumberSplitAcrossRunsTheRedactorLetThrough(List<PositionedText> runs) {
        assertThat(TraceValidator.privacyKinds(runs)).contains(NUMBER_SPLIT);
    }

    /** What reading needs stays: amounts, a short integer on its own, a time, dates. None of these is
     *  refused when it reaches the check in clear. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Rs 500", "Page 2 of 5", "10:45", "10:45 12:30", "12 345", "1,234.00", "1,234.00 5,678.00",
            "01/07/2026", "01/07/2026 31/07/2026", "30/06/2026 14:18", "2026-10-14", "12 Jul '26",
            "0.00 538.00 25,000.00 24,462.00", "1,00,000", "12,345", "(500)", "-42", "Rs.1,234.00Dr",
            "1,234.00 Cr", "12Jul2026", "16-Feb-2026", "01/07/2026-31/07/2026"})
    void valuesReadingNeedsAreKeptAndNotRefused(String text) {
        assertThat(PdfTraceRedactor.redactLine(text)).isEqualTo(text);
        assertThat(TraceValidator.privacyKinds(List.of(run(text, 100f, 50f, 0f)))).as(text).isEmpty();
    }

    /** Lines that only look wrapped stay as they are: fewer than six digits across the break, a
     *  value at the break, a word at the break, or another page. */
    @Test
    void shortOrValueEndingLinesAreNotJoined() {
        List<List<PositionedText>> cases = List.of(
                List.of(run("Total 12", 100f, 50f, 40f), run("345 items", 100f, 62f, 45f)),        // 5 digits
                List.of(run("Page 2 of 5", 100f, 50f, 55f), run("1,234.00 Cr", 100f, 62f, 55f)),  // a value starts the next line
                List.of(run("Balance 1,234.00", 100f, 50f, 80f), run("98765 Xxxx", 100f, 62f, 50f)), // a value ends the line
                List.of(run("No 98765 here", 100f, 50f, 65f), run("43210", 100f, 62f, 25f)),     // a word at the break
                List.of(new PositionedText("Page 98765", 100f, 50f, 0, 50f),
                        new PositionedText("43210", 100f, 62f, 1, 25f)));                        // another page
        for (List<PositionedText> runs : cases) {
            List<PositionedText> redacted = PdfTraceRedactor.redact(runs);
            for (int i = 0; i < runs.size(); i++) {
                String original = runs.get(i).text();
                String digitsKept = original.replaceAll("[^0-9]", "");
                assertThat(redacted.get(i).text().replaceAll("[^0-9]", "")).as(original).isEqualTo(digitsKept);
            }
            assertThat(TraceValidator.privacyKinds(runs)).as(runs.toString()).isEmpty();
        }
    }

    /** A date shape is a date only when its parts can be a day, a month and a year. */
    @Test
    void aNumberShapedLikeADateWithImpossiblePartsIsMasked() {
        assertThat(PdfTraceRedactor.redactLine("45/67/2026")).isEqualTo("99/99/9999");
        assertThat(PdfTraceRedactor.redactLine("2026-13-40")).isEqualTo("9999-99-99");
        assertThat(PdfTraceRedactor.redactLine("07/31/2026")).isEqualTo("07/31/2026");
    }

    /** A short integer glued to a slash, an at sign, a colon, an underscore or a letter is part of an
     *  identifier (a UPI id, a reference), not a value, and is masked. */
    @ParameterizedTest
    @ValueSource(strings = {"UPI/12345", "12345@ybl", "Ref:123", "id_42", "A12", "12,", "Flat 404,"})
    void aShortIntegerGluedToAnIdentifierIsMasked(String text) {
        String redacted = PdfTraceRedactor.redactLine(text);
        assertThat(clearDigits(text, redacted)).as(text + " -> " + redacted).isZero();
    }

    /** A Cf character anywhere -- a zero-width space between tokens, a tag character -- is masked. */
    @Test
    void everyFormatCharacterIsMasked() {
        String text = "Balance ​ 1,234.00 󠁁󠁂";   // synthetic-ok: ZWSP and tag characters
        String redacted = PdfTraceRedactor.redactLine(text);
        assertThat(redacted.codePoints().filter(cp -> Character.getType(cp) == Character.FORMAT)).isEmpty();
        assertThat(redacted).startsWith("Balance x 1,234.00 ");
    }

    /** A lone C or D is kept only as a whole run (a Type column cell), never as an initial. */
    @Test
    void aDirectionLetterIsKeptOnlyAsAWholeRun() {
        assertThat(PdfTraceRedactor.redactLine("C")).isEqualTo("C");
        assertThat(PdfTraceRedactor.redactLine(" d ")).isEqualTo(" d ");
        assertThat(PdfTraceRedactor.redactLine("C Kumar")).isEqualTo("X Xxxxx");   // synthetic-ok
        assertThat(PdfTraceRedactor.redactLine("1,234.00 D")).isEqualTo("1,234.00 X");
    }

    /** A changed run's x is rounded to a whole point like its width; an unchanged run's is exact. */
    @Test
    void aChangedRunHasItsXRounded() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(List.of(
                new PositionedText("Asha", 100.37f, 50f, 0, 20.4f),          // synthetic-ok
                new PositionedText("1,234.00", 200.37f, 50f, 0, 30.4f)));
        assertThat(redacted.get(0).x()).isEqualTo(100f);
        assertThat(redacted.get(0).width()).isEqualTo(20f);
        assertThat(redacted.get(1).x()).isEqualTo(200.37f);
        assertThat(redacted.get(1).width()).isEqualTo(30.4f);
    }

    private static PositionedText run(String text, float x, float y, float width) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** How many of the original's ASCII digits are still the same digit, at the same place, in the
     *  redacted text, by code point. */
    private static int clearDigits(String original, String redacted) {
        int[] a = original.codePoints().toArray();
        int[] b = redacted.codePoints().toArray();
        int clear = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] >= '0' && a[i] <= '9' && a[i] != '9' && b[i] == a[i]) clear++;
        }
        return clear;
    }
}
