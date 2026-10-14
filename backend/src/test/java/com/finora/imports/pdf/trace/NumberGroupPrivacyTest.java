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
            "10:45", "10:45 12:30", "1,234.00", "1,234.00 5,678.00",
            "01/07/2026", "01/07/2026 31/07/2026", "30/06/2026 14:18", "2026-10-14", "12 Jul '26",
            "0.00 538.00 25,000.00 24,462.00", "1,00,000", "12,345", "(1,234.00)", "-500.00", "Rs.1,234.00Dr",
            "1,234.00 Cr", "12Jul2026", "16-Feb-2026", "01/07/2026-31/07/2026"})
    void valuesReadingNeedsAreKeptAndNotRefused(String text) {
        assertThat(PdfTraceRedactor.redactLine(text)).isEqualTo(text);
        List<PositionedText> runs = List.of(run(text, 100f, 50f, 0f));
        assertThat(TraceValidator.privacyKinds(runs)).as(text).isEmpty();
        assertThat(TraceValidator.copyLeaks(runs, runs)).as("values in clear are not leaks: " + text).isEmpty();
    }

    /** A plain integer is not a value the copy keeps: production (redactor version 4) masked every
     *  one, and keeping them cost no matches when measured, so they are masked again. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "Rs 500      | Rs 999", "Page 2 of 5 | Page 9 of 9", "12 345      | 99 999", "(500)       | (999)",
            "-42         | -99", "Rs.500      | Xx.999", "500Cr       | 999Xx", "No 404      | No 999",
    })
    void plainIntegersAreMasked(String input, String expected) {
        assertThat(PdfTraceRedactor.redactLine(input.trim())).isEqualTo(expected.trim());
    }

    /** The reviewer's second round of probes. Each is fully masked by the redactor, and the exact
     *  check -- which compares the copy with the statement -- refuses it when the redactor is
     *  bypassed (the copy equal to the statement). */
    static Stream<List<PositionedText>> secondRoundProbes() {
        return Stream.of(
                // synthetic-ok: a table row's narration cell wraps; amounts sit right of it on both lines
                List.of(run("IMPS TO MOB 98765", 100f, 50f, 85f), run("1,234.00", 400f, 50f, 40f),
                        run("43210 ASHA", 100f, 62f, 50f), run("5,000.00", 400f, 62f, 40f)),
                List.of(run("98765\u200B43210", 100f, 50f, 50f)),              // synthetic-ok: zero-width space
                List.of(run("98765\u00AD43210", 100f, 50f, 50f)),              // synthetic-ok: soft hyphen
                List.of(run("98765\uDB40\uDC2D43210", 100f, 50f, 50f)),       // synthetic-ok: tag hyphen
                List.of(run("98765\uE00043210", 100f, 50f, 50f)),              // synthetic-ok: private-use dash
                List.of(run("98765 \uFF14\uFF13\uFF12\uFF11\uFF10", 100f, 50f, 55f)), // synthetic-ok: full-width half
                // synthetic-ok: runs whose width was never measured, far apart
                List.of(new PositionedText("98765", 100f, 50f, 0), new PositionedText("43210", 400f, 50f, 0)),
                // synthetic-ok: y drifting 1.2pt per run
                List.of(run("98765", 100f, 50f, 25f), run("43210", 127f, 51.2f, 25f), run("12345", 154f, 52.4f, 25f)),
                List.of(run("98765 -- 43210", 100f, 50f, 70f)),                 // synthetic-ok: four-character separator
                List.of(run("98765 / - 43210", 100f, 50f, 75f)),                // synthetic-ok: five-character separator
                // synthetic-ok: a page break between the halves
                List.of(new PositionedText("Mobile 98765", 100f, 700f, 0, 60f),
                        new PositionedText("43210", 100f, 50f, 1, 25f)));
    }

    @ParameterizedTest
    @MethodSource("secondRoundProbes")
    void secondRoundProbesAreMaskedAndTheExactCheckRefusesThemUnmasked(List<PositionedText> runs) {
        List<PositionedText> redacted = PdfTraceRedactor.redact(runs);
        for (int i = 0; i < runs.size(); i++) {
            String original = runs.get(i).text();
            String copy = redacted.get(i).text();
            if (original.matches("[0-9,]+\\.[0-9]{2}")) {
                assertThat(copy).as("an amount beside the narration").isIn(original, original.replaceAll("[0-9]", "9"));
                continue;
            }
            assertThat(clearDigits(original, copy)).as(original + " -> " + copy).isZero();
            assertThat(copy.codePoints().filter(cp -> cp > 127 && Character.isDigit(cp))).isEmpty();
        }
        assertThat(TraceValidator.copyLeaks(runs, redacted)).isEmpty();
        assertThat(TraceValidator.privacyKinds(redacted)).isEmpty();
        assertThat(TraceValidator.copyLeaks(runs, runs)).isNotEmpty();
    }

    /** Every earlier probe too: the exact check refuses each unmasked, and passes each masked. */
    @ParameterizedTest
    @MethodSource("oneRunProbes")
    void theExactCheckRefusesEveryOneRunProbeUnmasked(String text) {
        List<PositionedText> runs = List.of(run(text, 100f, 50f, 0f));
        assertThat(TraceValidator.copyLeaks(runs, runs)).as(text).isNotEmpty();
        assertThat(TraceValidator.copyLeaks(runs, PdfTraceRedactor.redact(runs))).as(text).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("multiRunProbes")
    void theExactCheckRefusesEveryMultiRunProbeUnmasked(List<PositionedText> runs) {
        assertThat(TraceValidator.copyLeaks(runs, runs)).isNotEmpty();
        assertThat(TraceValidator.copyLeaks(runs, PdfTraceRedactor.redact(runs))).isEmpty();
    }

    /** The IFSC rule keeps the bank prefix and the fixed fifth character, always '0' in an IFSC;
     *  that digit is the format, not data, and is the one digit the exact check lets through. */
    @Test
    void anIfscsFixedZeroIsNotALeakButItsBranchDigitsAre() {
        List<PositionedText> runs = List.of(run("IFSC HDFC0001234", 100f, 50f, 0f));   // synthetic-ok: invented branch code
        assertThat(TraceValidator.copyLeaks(runs, PdfTraceRedactor.redact(runs))).isEmpty();
        assertThat(TraceValidator.copyLeaks(runs, runs)).isNotEmpty();
    }

    /** M1: the exact check judges every number the copy shows, not only the statement's digits
     *  where they stand: digits moved to another run are still refused. */
    @Test
    void digitsMovedToAnotherPlaceInTheCopyAreRefused() {
        List<PositionedText> original = List.of(run("98765", 10f, 50f, 25f), run("43210", 300f, 50f, 25f));   // synthetic-ok
        List<PositionedText> copy = List.of(run("43210", 10f, 50f, 25f), run("98765", 300f, 50f, 25f));       // synthetic-ok
        assertThat(TraceValidator.copyLeaks(original, copy)).isNotEmpty();
    }

    /** M2: an amount whose integer part has eight or more digits in a row is a reference, as the
     *  redactor's long-digit rule says, not a value: masked, and refused when shown. */
    @Test
    void anAmountWithEightIntegerDigitsInARowIsNotAValue() {
        String text = "98765432.10";   // synthetic-ok
        assertThat(PdfTraceRedactor.redactLine(text)).isEqualTo("99999999.99");
        List<PositionedText> raw = List.of(run(text, 100f, 50f, 0f));
        assertThat(TraceValidator.privacyKinds(raw)).contains(NUMBER_SPLIT);
        assertThat(TraceValidator.copyLeaks(raw, raw)).isNotEmpty();
    }

    /** M3: numbers outside the decimal-digit category (superscript, circled) are numbers to the
     *  exact check too. */
    @ParameterizedTest
    @ValueSource(strings = {"Ref \u2460\u2461\u2462\u2463\u2464\u2465", "Ref \u00B9\u00B2\u00B3\u2074\u2075\u2076", "\u2160\u2161"})
    void numbersInAnyNumberCategoryAreRefusedWhenShown(String text) {
        List<PositionedText> raw = List.of(run(text, 100f, 50f, 0f));
        assertThat(TraceValidator.copyLeaks(raw, raw)).as(text).isNotEmpty();
        assertThat(TraceValidator.copyLeaks(raw, PdfTraceRedactor.redact(raw))).as(text).isEmpty();
    }

    /** M4: values glued together by a colon are one longer number, not two values. */
    @ParameterizedTest
    @ValueSource(strings = {"12:34:56.78", "10:45:30.5", "12:34:56", "01/07/2026:12345"})
    void valuesGluedByAColonAreMasked(String text) {
        String redacted = PdfTraceRedactor.redactLine(text);
        assertThat(clearDigits(text, redacted)).as(text + " -> " + redacted).isZero();
        List<PositionedText> raw = List.of(run(text, 100f, 50f, 0f));
        assertThat(TraceValidator.copyLeaks(raw, raw)).as(text).isNotEmpty();
    }

    /** M6: an amount-shaped piece next to a short plain group is part of one long number. */
    @Test
    void anAmountShapedPieceBesideAShortGroupIsMasked() {
        assertThat(PdfTraceRedactor.redactLine("9876.54 3210")).isEqualTo("9999.99 9999");   // synthetic-ok
    }

    /** Known limit (accepted under the amounts policy): a chain made only of amount-shaped values has
     *  no digit cap, so amounts printed side by side are kept whatever they add up to. */
    @Test
    void aChainOfAmountsOnlyIsKept() {
        assertThat(PdfTraceRedactor.redactLine("98,765 43,210")).isEqualTo("98,765 43,210");   // synthetic-ok
    }

    /** A copy that does not line up with the statement (a different number of runs or code points)
     *  cannot be checked digit by digit, so it is refused. */
    @Test
    void aCopyThatDoesNotLineUpIsRefused() {
        List<PositionedText> runs = List.of(run("Balance 1,234.00", 100f, 50f, 0f));
        assertThat(TraceValidator.copyLeaks(runs, List.of(run("Balance 1,234.0", 100f, 50f, 0f)))).isNotEmpty();
        assertThat(TraceValidator.copyLeaks(runs, List.of())).isNotEmpty();
    }

    /** At a line break where a value ends one line or starts the next, the value is kept and
     *  nothing is refused. */
    @Test
    void valuesAtALineBreakAreKept() {
        List<List<PositionedText>> cases = List.of(
                List.of(run("Page 2 of 5", 100f, 50f, 55f), run("1,234.00 Cr", 100f, 62f, 55f)),
                List.of(run("Balance 1,234.00", 100f, 50f, 80f), run("98765 Xxxx", 100f, 62f, 50f)),
                List.of(run("Total 12", 100f, 50f, 40f), run("01/07/2026 UPI", 100f, 62f, 70f)));
        for (List<PositionedText> runs : cases) {
            List<PositionedText> redacted = PdfTraceRedactor.redact(runs);
            assertThat(String.join(" ", redacted.stream().map(PositionedText::text).toList()))
                    .containsAnyOf("1,234.00", "01/07/2026");
            assertThat(TraceValidator.copyLeaks(runs, redacted)).isEmpty();
            assertThat(TraceValidator.privacyKinds(redacted)).isEmpty();
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
