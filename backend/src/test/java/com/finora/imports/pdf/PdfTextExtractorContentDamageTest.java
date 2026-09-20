package com.finora.imports.pdf;

import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser knows when it has had to throw content away.
 *
 * <p>PDFBox is lenient: when a text operator's operands are destroyed (a damaged download) it logs the
 * failure and carries on with the rest of the page, so extraction "succeeds" with fewer rows and
 * nothing downstream can tell. Measured 2026-09-20 on a real statement's fixture: 1 of 6 rows imported,
 * rated CLEAN, with STATEMENT_TOTALS "verified". This is the missing signal, recorded at the one
 * place that sees it.
 */
class PdfTextExtractorContentDamageTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    private static byte[] damaged(byte[] pdf, double fraction) {
        byte[] d = pdf.clone();
        Random r = new Random(7);
        int start = (int) (d.length * fraction);
        for (int i = 0; i < 60 && start + i < d.length; i++) d[start + i] = (byte) r.nextInt(256);
        return d;
    }

    @Test
    void anIntactDocumentReportsNoDamage() throws Exception {
        var extraction = extractor.extractWithDiagnostics(
                PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), null);

        assertThat(extraction.damage().isDamaged()).isFalse();
        assertThat(extraction.damage().pages()).isEmpty();
        assertThat(extraction.runs()).isNotEmpty();
    }

    @Test
    void aDocumentWhoseTextOperatorsWereDestroyedReportsWhichPage() throws Exception {
        byte[] intact = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();
        // 0.31 through the file: the case that imported 1 of 6 rows as "clean".
        var extraction = extractor.extractWithDiagnostics(damaged(intact, 0.31), null);

        assertThat(extraction.damage().isDamaged()).isTrue();
        assertThat(extraction.damage().failedTextOperators()).isGreaterThan(0);
        assertThat(extraction.damage().pages()).contains(1);
        assertThat(extraction.runs().size()).as("some text still came out -- that is the danger")
                .isLessThan(extractor.extract(intact, null).size());
    }

    /** extract() is the API every existing caller uses; it must return exactly what it always did. */
    @Test
    void extractStillReturnsTheSameRuns() throws Exception {
        byte[] pdf = damaged(PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), 0.31);

        assertThat(extractor.extract(pdf, null)).isEqualTo(extractor.extractWithDiagnostics(pdf, null).runs());
    }

    /**
     * The property that matters, over every damage offset rather than one hand-picked case: whenever
     * damage costs the document some of its text, the parser says so. Text that vanishes silently is the
     * defect -- measured on a real statement, a page whose compressed content stream was corrupted
     * decoded to 2,790 of 14,402 bytes with no operator ever failing, and the last transaction and the
     * printed summary went with it. A damaged file that is refused outright (the user is told) is fine.
     */
    @Test
    void wheneverDamageCostsTextTheExtractorSaysSo() throws Exception {
        byte[] intact = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();
        int intactRuns = extractor.extract(intact, null).size();
        List<String> silent = new ArrayList<>();
        int lossy = 0;
        for (int i = 1; i < 50; i++) {
            double fraction = i * 0.02;
            try {
                var e = extractor.extractWithDiagnostics(damaged(intact, fraction), null);
                // Nothing extracted at all is refused downstream (ExtractionCheck), so the user is told.
                if (!e.runs().isEmpty() && e.runs().size() < intactRuns) {
                    lossy++;
                    if (!e.damage().isDamaged()) silent.add(fraction + " -> " + e.runs().size() + " of " + intactRuns + " runs");
                }
            } catch (com.finora.exception.ApiException refusedOutright) {
                // Told to the user: not silent.
            }
        }
        assertThat(lossy).as("the sweep really does lose text at some offsets").isGreaterThan(0);
        assertThat(silent).as("text lost with no damage reported").isEmpty();
    }

    /**
     * Corrupted bytes that still parse as deflate: no failing operator and a "complete" stream, but the
     * decoded content is garbage (11 of 39 runs survive at this offset). Only the checksum the producer
     * wrote gives it away.
     */
    @Test
    void corruptionThatStillParsesAsDeflateIsCaughtByItsChecksum() throws Exception {
        byte[] intact = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();

        var e = extractor.extractWithDiagnostics(damaged(intact, 0.28), null);

        assertThat(e.runs()).isNotEmpty();
        assertThat(e.runs().size()).isLessThan(extractor.extract(intact, null).size());
        assertThat(e.damage().corruptContentStreams()).isGreaterThan(0);
    }

    /**
     * A page whose dictionary still declares {@code /Contents} but whose content object can no longer be
     * found (its header was overwritten): PDFBox sees a page with no content and extraction "succeeds"
     * without it. Measured on a real statement: the last transaction and the printed summary sat on that
     * page, so nothing was left to reveal the loss.
     */
    @Test
    void aPageWhoseDeclaredContentsCannotBeFoundIsReportedAsDamage() throws Exception {
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 9 0 R >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R /Size 10 >>\n%%EOF\n";

        var e = extractor.extractWithDiagnostics(pdf.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), null);

        assertThat(e.damage().corruptContentStreams()).isEqualTo(1);
        assertThat(e.damage().pages()).containsExactly(1);
    }

    /** ...but a page that declares no contents at all is simply blank, which is legitimate. */
    @Test
    void aGenuinelyBlankPageIsNotDamage() throws Exception {
        byte[] blank;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            blank = out.toByteArray();
        }

        assertThat(extractor.extractWithDiagnostics(blank, null).damage().isDamaged()).isFalse();
    }

    /** A compressed content stream that ends early: complete-looking tokens, no failing operator, less text. */
    @Test
    void aTruncatedCompressedContentStreamIsReportedAsDamage() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();
        byte[] truncated = truncateFirstFlateContentStream(pdf, 0.2);

        var e = extractor.extractWithDiagnostics(truncated, null);

        assertThat(e.damage().corruptContentStreams()).isGreaterThan(0);
        assertThat(e.damage().pages()).contains(1);
        assertThat(e.runs().size()).isLessThan(extractor.extract(pdf, null).size());
    }

    /**
     * A page whose content is destroyed in a way that stops the parser outright (a malformed inline image
     * here) used to escape as a raw IOException: unrecognised, so the worker retried it and then held it
     * "for review". It is a damaged file -- IMPORT_CORRUPT_PDF, failed at once, with its own message.
     */
    @Test
    void aDestroyedInlineImageIsRefusedAsACorruptPdfNotARawIoException() throws Exception {
        byte[] pdf;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            page.setContents(new org.apache.pdfbox.pdmodel.common.PDStream(doc,
                    new java.io.ByteArrayInputStream("BI /W 1 /H 1 /BPC 8 /CS /G Iy 00 EI".getBytes())));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> extractor.extractWithDiagnostics(pdf, null))
                .isInstanceOfSatisfying(com.finora.exception.ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(com.finora.exception.ErrorCode.IMPORT_CORRUPT_PDF));
    }

    /**
     * Finds the first {@code FlateDecode} content stream in the raw bytes and overwrites everything after
     * {@code keepFraction} of its compressed data with noise, so it stops decoding part-way -- the shape a
     * damaged download leaves.
     */
    private static byte[] truncateFirstFlateContentStream(byte[] pdf, double keepFraction) {
        byte[] out = pdf.clone();
        java.nio.charset.Charset latin1 = java.nio.charset.StandardCharsets.ISO_8859_1;
        // A stream's data begins after a "stream" keyword that ends its own line -- never the "stream"
        // inside "endstream", which is why the keyword is matched with its preceding newline or space.
        byte[][] starts = {" stream\r\n".getBytes(latin1), "\nstream\r\n".getBytes(latin1),
                " stream\n".getBytes(latin1), "\nstream\n".getBytes(latin1)};
        byte[] end = "endstream".getBytes(latin1);
        int from = 0;
        while (from < out.length) {
            int s = -1, len = 0;
            for (byte[] marker : starts) {
                int i = indexOf(out, marker, from);
                if (i >= 0 && (s < 0 || i < s)) { s = i; len = marker.length; }
            }
            if (s < 0) break;
            int dataStart = s + len;
            int dataEnd = indexOf(out, end, dataStart);
            if (dataEnd < 0) break;
            // A zlib header: 0x78 then a check byte -- what PDFBox writes for FlateDecode.
            if (dataEnd > dataStart + 40 && (out[dataStart] & 0xFF) == 0x78) {
                int cut = dataStart + (int) ((dataEnd - dataStart) * keepFraction);
                Random r = new Random(11);
                for (int i = cut; i < dataEnd - 2; i++) out[i] = (byte) r.nextInt(256);
                return out;
            }
            from = dataEnd + end.length;
        }
        throw new AssertionError("no compressed stream found in the fixture");
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /**
     * Precision: a signal that fired on healthy documents would tell users their good statement was
     * damaged. Every layout the fixture builder can produce, and the committed sample PDF, must read as
     * undamaged. (The real corpus is not in the repository; this is the widest healthy set there is.)
     */
    @Test
    void noHealthyFixtureLayoutIsReportedAsDamaged() throws Exception {
        List<String> flagged = new ArrayList<>();
        int checked = 0;
        for (Method m : PdfFixtureBuilder.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())
                    || m.getParameterCount() != 0 || m.getReturnType() != byte[].class
                    || !m.getName().startsWith("build")) continue;
            byte[] pdf = (byte[]) m.invoke(null);
            checked++;
            if (extractor.extractWithDiagnostics(pdf, null).damage().isDamaged()) flagged.add(m.getName());
        }
        Path sample = Path.of("src/test/resources/pdf/separate_debit_credit_balance_sample.pdf");
        checked++;
        if (extractor.extractWithDiagnostics(Files.readAllBytes(sample), null).damage().isDamaged()) flagged.add("committed sample");

        assertThat(checked).as("the reflection found the builders").isGreaterThan(5);
        assertThat(flagged).as("healthy documents reported as damaged").isEmpty();
    }

    @Test
    void anEncryptedDocumentOpenedWithItsPasswordReportsNoDamage() throws Exception {
        byte[] pdf = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), "AAAA1234");

        assertThat(extractor.extractWithDiagnostics(pdf, "AAAA1234").damage().isDamaged()).isFalse();
    }
}
