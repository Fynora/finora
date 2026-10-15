package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PositionedText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A blanked copy keeps a printed line verbatim only when the whole line is a listed bank sentence,
 * and never anything printed beside it. Every sentence, name and number here is synthetic.
 */
class PdfTraceRedactorBankSentenceTest {

    private static final String SENTENCE = "This is a computer generated statement";
    private static final Set<String> S = BankSentences.forTesting(SENTENCE);

    @Test
    void aWholeLineSentenceIsKeptVerbatim() {
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(SENTENCE, 50.2f, 700f, 0, 190.4f)), S);

        assertRun(out.get(0), SENTENCE, 50.2f, 190.4f);
    }

    @Test
    void aSentenceSplitAcrossTwoRunsOnOneLineIsKept() {
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText("This is a computer", 50.2f, 700f, 0, 100.4f),
                new PositionedText("generated statement", 153.6f, 700f, 0, 90.3f)), S);

        assertRun(out.get(0), "This is a computer", 50.2f, 100.4f);
        assertRun(out.get(1), "generated statement", 153.6f, 90.3f);
    }

    @Test
    void aSentenceSplitAcrossTwoRunsFarApartOnOneLineIsKept() {
        // Runs that do not join as neighbours are separated by four spaces in the joined line;
        // normalising collapses them, so the whole printed line still reads as the sentence.
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText("This is a computer", 50.2f, 700f, 0, 100.4f),
                new PositionedText("generated statement", 400.6f, 700.9f, 0, 90.3f)), S);

        assertRun(out.get(0), "This is a computer", 50.2f, 100.4f);
        assertRun(out.get(1), "generated statement", 400.6f, 90.3f);
    }

    @Test
    void aNameGluedIntoTheSameRunMasksTheWholeRun() {
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(SENTENCE + " Jane Example", 50.2f, 700f, 0, 260.4f)), S);

        assertRun(out.get(0), "This is a xxxxxxxx xxxxxxxxx statement Xxxx Xxxxxxx", 50f, 260f);
    }

    @Test
    void aNameBeforeTheSentenceInTheSameRunMasksTheWholeRun() {
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText("Jane Example " + SENTENCE, 50.2f, 700f, 0, 260.4f)), S);

        assertRun(out.get(0), "Xxxx Xxxxxxx This is a xxxxxxxx xxxxxxxxx statement", 50f, 260f);
    }

    @Test
    void aNameAsAnotherRunOnTheSameLineMasksTheWholeLine() {
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(SENTENCE, 50.2f, 700f, 0, 190.4f),
                new PositionedText("Jane Example", 243.1f, 700f, 0, 70.6f)), S);

        assertRun(out.get(0), "This is a xxxxxxxx xxxxxxxxx statement", 50f, 190f);
        assertRun(out.get(1), "Xxxx Xxxxxxx", 243f, 71f);
    }

    @Test
    void aNameBeforeTheSentenceMasksTheWholeLine() {
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText("Jane Example", 50.2f, 700f, 0, 70.6f),
                new PositionedText(SENTENCE, 123.1f, 700f, 0, 190.4f)), S);

        assertRun(out.get(0), "Xxxx Xxxxxxx", 50f, 71f);
        assertRun(out.get(1), "This is a xxxxxxxx xxxxxxxxx statement", 123f, 190f);
    }

    @Test
    void aNumberOnTheSameLineMasksTheWholeLine() {
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(SENTENCE, 50.2f, 700f, 0, 190.4f),
                new PositionedText("100000000001", 400.1f, 700f, 0, 60.6f)), S);

        assertRun(out.get(0), "This is a xxxxxxxx xxxxxxxxx statement", 50f, 190f);
        assertRun(out.get(1), "999999999999", 400f, 61f);
    }

    @Test
    void aNameOnTheNextLineLeavesTheSentenceKeptAndTheNameMasked() {
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(SENTENCE, 50.2f, 700f, 0, 190.4f),
                new PositionedText("Jane Example", 50.2f, 714f, 0, 70.6f)), S);

        assertRun(out.get(0), SENTENCE, 50.2f, 190.4f);
        assertRun(out.get(1), "Xxxx Xxxxxxx", 50f, 71f);
    }

    @Test
    void theSameSentenceOnAnotherPageIsItsOwnLine() {
        // Same y, different page: two printed lines, each the whole sentence.
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(SENTENCE, 50.2f, 700f, 0, 190.4f),
                new PositionedText("Jane Example", 250.2f, 700f, 1, 70.6f),
                new PositionedText(SENTENCE, 50.2f, 700f, 2, 190.4f)), S);

        assertRun(out.get(0), SENTENCE, 50.2f, 190.4f);
        assertRun(out.get(1), "Xxxx Xxxxxxx", 250f, 71f);
        assertRun(out.get(2), SENTENCE, 50.2f, 190.4f);
    }

    @Test
    void aSentenceWrappedOverTwoLinesIsMaskedOnBoth() {
        // Known limit: neither printed line is the whole sentence alone.
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText("This is a computer", 50.2f, 700f, 0, 100.4f),
                new PositionedText("generated statement", 50.2f, 714f, 0, 90.3f)), S);

        assertRun(out.get(0), "This is a xxxxxxxx", 50f, 100f);
        assertRun(out.get(1), "xxxxxxxxx statement", 50f, 90f);
    }

    @Test
    void aPrefixOrSuffixOfTheSentenceKeepsNothing() {
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText("This is a computer generated", 50.2f, 700f, 0, 140.4f),
                new PositionedText("a computer generated statement", 50.2f, 714f, 0, 150.3f)), S);

        assertRun(out.get(0), "This is a xxxxxxxx xxxxxxxxx", 50f, 140f);
        assertRun(out.get(1), "a xxxxxxxx xxxxxxxxx statement", 50f, 150f);
    }

    @Test
    void punctuationBesideTheSentenceIsNotAMatch() {
        // The entry is the exact words; a line with a full stop is a different line and is masked.
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(SENTENCE + ".", 50.2f, 700f, 0, 192.4f)), S);

        assertRun(out.get(0), "This is a xxxxxxxx xxxxxxxxx statement.", 50f, 192f);
    }

    @Test
    void aLineSpelledWithACharacterThatLowersIntoAsciiIsNotAMatch() {
        // The Kelvin sign lower-cases to "k", so the normalised line equals the entry; the line is
        // not plain ASCII, so it is masked as before.
        Set<String> keep = BankSentences.forTesting("please keep this statement safe");
        String printed = "please \u212Aeep this statement safe";
        assertThat(BankSentences.normalise(printed)).isEqualTo("please keep this statement safe");

        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(printed, 50.2f, 700f, 0, 150.4f)), keep);

        assertRun(out.get(0), "xxxxxx Xxxx this statement xxxx", 50f, 150f);
        assertThat(PdfTraceRedactor.redact(List.of(
                new PositionedText("please keep this statement safe", 50.2f, 700f, 0, 150.4f)), keep).get(0).text())
                .isEqualTo("please keep this statement safe");
    }

    @Test
    void aWideSpaceInsideTheLineIsNotAMatch() {
        String printed = "This is a computer\u2003generated statement";
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(printed, 50.2f, 700f, 0, 190.4f)), S);

        assertRun(out.get(0), "This is a xxxxxxxx\u2003xxxxxxxxx statement", 50f, 190f);
    }

    @Test
    void caseAndSpacingDoNotMatter() {
        String printed = "THIS  IS A COMPUTER GENERATED STATEMENT";
        List<PositionedText> out = PdfTraceRedactor.redact(List.of(
                new PositionedText(printed, 50.2f, 700f, 0, 200.4f)), S);

        assertRun(out.get(0), printed, 50.2f, 200.4f);
    }

    @Test
    void anEmptyListChangesNothing() {
        // Expected values generated once by running the version-5 redactor (origin/main at
        // 184eb32e5, PdfTraceRedactor.redact) on statement() below, and pasted here as literals. (The
        // last run's ten digits were then a different ten-digit number; the placeholder that replaced
        // it for the fixture guard masks to the same text.)
        String[][] version5 = {
                {"Statement of Account", "200.3", "110.4"},
                {"Xxxx Xxxxxxx", "50.0", "71.0"},
                {"Account No : 999999999999", "300.0", "131.0"},
                {"Date", "50.2", "25.3"},
                {"Narration", "120.4", "50.1"},
                {"Withdrawal", "300.1", "55.2"},
                {"Deposit", "380.6", "40.4"},
                {"Balance", "460.3", "45.8"},
                {"01/07/2026", "50.2", "50.5"},
                {"XXX/999999999999/Xxxx Xxxxxxx/PAID", "120.0", "170.0"},
                {"1,250.00", "300.1", "45.3"},
                {"48,750.00", "460.3", "50.6"},
                {"02/07/2026", "50.2", "50.5"},
                {"NEFT-XXXXXX XXXXXXX", "120.0", "101.0"},
                {"20,000.00", "380.6", "50.6"},
                {"68,750.00", "460.3", "50.6"},
                {"This is a xxxxxxxx xxxxxxxxx", "50.0", "140.0"},
                {"statement", "192.7", "45.1"},
                {"Page 9 of 9", "261.0", "50.0"},
                {"Xxxx 9999999999 for xxxx", "50.0", "111.0"},
        };
        List<PositionedText> in = statement();
        List<PositionedText> out = PdfTraceRedactor.redact(in, Set.of());

        assertThat(out).hasSize(version5.length);
        for (int r = 0; r < out.size(); r++) {
            assertRun(out.get(r), version5[r][0], Float.parseFloat(version5[r][1]), Float.parseFloat(version5[r][2]));
            assertThat(out.get(r).y()).isEqualTo(in.get(r).y());
            assertThat(out.get(r).pageIndex()).isEqualTo(in.get(r).pageIndex());
        }
        // The production list is empty until the owner's seed lands: the public method is the same.
        assertThat(PdfTraceRedactor.redact(in, BankSentences.listed())).isEqualTo(out);
    }

    @Test
    void theStatementKeepsOnlyTheListedLine() {
        List<PositionedText> in = statement();
        List<PositionedText> withList = PdfTraceRedactor.redact(in, S);
        List<PositionedText> without = PdfTraceRedactor.redact(in, Set.of());

        for (int r = 0; r < in.size(); r++) {
            if (r == 16 || r == 17) {
                assertRun(withList.get(r), in.get(r).text(), in.get(r).x(), in.get(r).width());
            } else {
                assertThat(withList.get(r)).isEqualTo(without.get(r));
            }
        }
        assertThat(PdfTraceRedactor.runsOfListedLines(in, S)).containsExactlyInAnyOrder(16, 17);
    }

    @Test
    void aKeptLineStillPassesThePrivacyChecks() {
        List<PositionedText> in = new ArrayList<>(statement());
        in.add(new PositionedText(SENTENCE, 50.2f, 630f, 1, 190.4f));
        List<PositionedText> copy = PdfTraceRedactor.redact(in, S);
        assertThat(copy.get(copy.size() - 1).text()).isEqualTo(SENTENCE);

        // Through the trace format, as a blanked copy is stored and read back.
        List<PositionedText> readBack = TraceFormat.parse(TraceFormat.format(copy));
        assertThat(TraceValidator.privacyKinds(readBack)).isEmpty();
        assertThat(TraceValidator.copyLeaks(in, copy)).isEmpty();
    }

    @Test
    void theFingerprintChangesWithTheList() {
        // 1B94F23A: allowlistFingerprint() on origin/main at 184eb32e5, before this list existed.
        assertThat(PdfTraceRedactor.allowlistFingerprint(Set.of())).isEqualTo("1B94F23A");
        assertThat(PdfTraceRedactor.allowlistFingerprint()).isEqualTo(
                PdfTraceRedactor.allowlistFingerprint(BankSentences.listed()));
        assertThat(PdfTraceRedactor.allowlistFingerprint(S)).isNotEqualTo("1B94F23A");
        assertThat(PdfTraceRedactor.allowlistFingerprint(BankSentences.forTesting(SENTENCE, "please examine this statement upon receipt")))
                .isNotEqualTo(PdfTraceRedactor.allowlistFingerprint(S));
        // Order of declaration does not matter, only content.
        assertThat(PdfTraceRedactor.allowlistFingerprint(BankSentences.forTesting("please examine this statement upon receipt", SENTENCE)))
                .isEqualTo(PdfTraceRedactor.allowlistFingerprint(BankSentences.forTesting(SENTENCE, "please examine this statement upon receipt")));
    }

    private static void assertRun(PositionedText run, String text, float x, float width) {
        assertThat(run.text()).isEqualTo(text);
        assertThat(run.x()).isEqualTo(x);
        assertThat(run.width()).isEqualTo(width);
    }

    /** A synthetic statement of 20 runs; runs 16 and 17 are the sentence printed in two runs. */
    private static List<PositionedText> statement() {
        return List.of(
                new PositionedText("Statement of Account", 200.3f, 760f, 0, 110.4f),
                new PositionedText("Jane Example", 50.2f, 740f, 0, 70.6f),
                new PositionedText("Account No : 100000000001", 300.1f, 740f, 0, 130.7f),
                new PositionedText("Date", 50.2f, 720f, 0, 25.3f),
                new PositionedText("Narration", 120.4f, 720f, 0, 50.1f),
                new PositionedText("Withdrawal", 300.1f, 720f, 0, 55.2f),
                new PositionedText("Deposit", 380.6f, 720f, 0, 40.4f),
                new PositionedText("Balance", 460.3f, 720f, 0, 45.8f),
                new PositionedText("01/07/2026", 50.2f, 705f, 0, 50.5f),
                new PositionedText("UPI/100000000001/Jane Example/PAID", 120.4f, 705f, 0, 170.2f),
                new PositionedText("1,250.00", 300.1f, 705f, 0, 45.3f),
                new PositionedText("48,750.00", 460.3f, 705f, 0, 50.6f),
                new PositionedText("02/07/2026", 50.2f, 690f, 0, 50.5f),
                new PositionedText("NEFT-SAMPLE TRADERS", 120.4f, 690f, 0, 100.9f),
                new PositionedText("20,000.00", 380.6f, 690f, 0, 50.6f),
                new PositionedText("68,750.00", 460.3f, 690f, 0, 50.6f),
                new PositionedText("This is a computer generated", 50.2f, 675f, 0, 140.4f),
                new PositionedText("statement", 192.7f, 675f, 0, 45.1f),
                new PositionedText("Page 1 of 2", 260.5f, 660f, 0, 50.3f),
                new PositionedText("Call 9999999999 for help", 50.2f, 645f, 0, 110.7f));
    }
}
