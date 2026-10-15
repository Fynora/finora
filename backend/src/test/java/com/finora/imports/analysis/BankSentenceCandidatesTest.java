package com.finora.imports.analysis;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed-candidate probe over synthetic PDFs only: a line is a candidate when it has no digit,
 * at least four words, and is printed in at least the given number of statements. Every line here
 * is synthetic.
 */
class BankSentenceCandidatesTest {

    private static final String NOTICE = "Please examine this statement upon receipt";

    @Test
    void aNoticeInThreeStatementsIsTheOnlyCandidate(@TempDir Path dir) throws IOException {
        pdf(dir.resolve("a.pdf"), "Prepared for Jane Example today", NOTICE, "Page 1 of 1 printed here", "Statement of Account");
        pdf(dir.resolve("b.pdf"), "Prepared for John Sample today", NOTICE, "Page 1 of 1 printed here", "Statement of Account");
        Files.createDirectories(dir.resolve("sub"));
        pdf(dir.resolve("sub").resolve("c.PDF"), "Prepared for Asha Verma today", NOTICE, "Page 1 of 1 printed here", "Statement of Account");
        pdf(dir.resolve("d.pdf"), "Prepared for Ravi Sample today", "Please retain this notice for your records",
                "Page 1 of 1 printed here", "Statement of Account");
        Files.writeString(dir.resolve("notes.txt"), "Please examine this statement upon receipt");

        BankSentenceCandidates.Result result = BankSentenceCandidates.candidates(dir, 3);

        assertThat(result.statementsRead()).isEqualTo(4);
        assertThat(result.unreadable()).isZero();
        assertThat(result.linesConsidered()).isEqualTo(16);
        assertThat(result.candidates()).containsExactly(
                new BankSentenceCandidates.Candidate(3, "unknown", "please examine this statement upon receipt"));
    }

    @Test
    void theThresholdIsTheNumberOfStatements(@TempDir Path dir) throws IOException {
        pdf(dir.resolve("a.pdf"), NOTICE, NOTICE, "Prepared for Jane Example today");
        pdf(dir.resolve("b.pdf"), NOTICE, "Prepared for Jane Example today");

        // The same line twice in one statement counts once.
        assertThat(BankSentenceCandidates.candidates(dir, 3).candidates()).isEmpty();
        assertThat(BankSentenceCandidates.candidates(dir, 2).candidates()).containsExactly(
                // Most common first, then in alphabetical order.
                new BankSentenceCandidates.Candidate(2, "unknown", "please examine this statement upon receipt"),
                new BankSentenceCandidates.Candidate(2, "unknown", "prepared for jane example today"));
    }

    @Test
    void anUnreadableFileIsCountedNotRead(@TempDir Path dir) throws IOException {
        pdf(dir.resolve("a.pdf"), NOTICE);
        Files.writeString(dir.resolve("broken.pdf"), "not a pdf");

        BankSentenceCandidates.Result result = BankSentenceCandidates.candidates(dir, 1);

        assertThat(result.statementsRead()).isEqualTo(1);
        assertThat(result.unreadable()).isEqualTo(1);
    }

    @Test
    void theFileHoldsOneTabSeparatedLinePerCandidate(@TempDir Path dir) throws IOException {
        List<BankSentenceCandidates.Candidate> candidates = List.of(
                new BankSentenceCandidates.Candidate(5, "HDFC,ICICI", "please examine this statement upon receipt"),
                new BankSentenceCandidates.Candidate(3, "unknown", "please retain this notice for your records"));
        Path out = dir.resolve("candidates.txt");

        BankSentenceCandidates.write(out, candidates);

        assertThat(Files.readAllLines(out)).containsExactly(
                "5\tHDFC,ICICI\tplease examine this statement upon receipt",
                "3\tunknown\tplease retain this notice for your records");
    }

    @Test
    void theSummaryIsCountsOnly() {
        assertThat(BankSentenceCandidates.summary(new BankSentenceCandidates.Result(34, 0, 1200, List.of(
                new BankSentenceCandidates.Candidate(3, "unknown", "please examine this statement upon receipt")))))
                .isEqualTo("statements read: 34, lines considered: 1200, candidates: 1");
        assertThat(BankSentenceCandidates.summary(new BankSentenceCandidates.Result(33, 1, 1200, List.of())))
                .isEqualTo("statements read: 33, lines considered: 1200, candidates: 0, unreadable: 1");
    }

    /** One page, one printed line per string, 20 points apart. */
    private static void pdf(Path path, String... lines) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                float y = 760;
                for (String line : lines) {
                    s.beginText();
                    s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    s.newLineAtOffset(72, y);
                    s.showText(line);
                    s.endText();
                    y -= 20;
                }
            }
            doc.save(path.toFile());
        }
    }
}
