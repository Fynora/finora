package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.blanked.BlankedCopy;
import com.finora.imports.blanked.BlankedCopyComparison;
import com.finora.imports.blanked.BlankedCopyRefusedException;
import com.finora.imports.blanked.BlankedCopyService;
import com.finora.imports.blanked.StaffStatementContent;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.trace.TraceFormat;
import com.finora.observability.BlankedCopyMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The blanked-copy service against the real reading pipeline (persistence mocked).
 *
 * <p>On the refusal test: the redactor masks every email it meets character by character, and the
 * privacy scan exempts an email whose letters are all the redactor's own mask characters, as well as
 * one holding a placeholder run like "xxxx". So most emails cannot survive. One shape does: a
 * vocabulary word as the local part ("statement" is kept) and a domain too short to mask into a run
 * of four ("a.in" becomes "x.xx"). {@code aRefusalNamesKindsNotValues} plants exactly that, so the
 * refusal path is reached through the real redactor and scan rather than through a test seam.
 */
class BlankedCopyServiceTest {

    /** Every value {@link PdfFixtureBuilder#buildSampleWithPlantedCustomerData} plants. */
    private static final String[] PLANTED = {PdfFixtureBuilder.PLANTED_NAME, PdfFixtureBuilder.PLANTED_PHONE,
            PdfFixtureBuilder.PLANTED_EMAIL, PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER, PdfFixtureBuilder.PLANTED_IFSC,
            PdfFixtureBuilder.PLANTED_LONG_REFERENCE, PdfFixtureBuilder.PLANTED_REFERENCE,
            PdfFixtureBuilder.PLANTED_OTHER_SCRIPT_NAME};

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private BlankedCopyService service(PdfPreviewGenerator generator) {
        return new BlankedCopyService(generator, new BlankedCopyMetrics(registry), clock);
    }

    private double produced(BlankedCopy.Check check) {
        var counter = registry.find("finora.blanked_copy.produced").tag("check", check.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    /** A MATCHES is only worth something when the real reading found rows to compare. */
    private static void assertTheRealReadingHasThreeRows(byte[] pdf) throws Exception {
        var sections = PdfGeneratorTestSupport.generator()
                .generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", pdf).sections();
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).rows()).hasSize(3);
    }

    @Test
    void aSyntheticStatementMatches() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        assertTheRealReadingHasThreeRows(pdf);
        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000001", "PDF", pdf));
        assertThat(copy.differences()).isEmpty();
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.MATCHES);
        assertThat(copy.fileName()).isEqualTo("blanked-HLD-2026-000001.trace");
        assertThat(copy.trace()).startsWith("# finora-pdf-trace");
        assertThat(copy.notCheckedReason()).isNull();
        assertThat(produced(BlankedCopy.Check.MATCHES)).isEqualTo(1);
    }

    @Test
    void theCopyCarriesNoCustomerTextAndNoFileName() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildSampleWithPlantedCustomerData();
        assertTheRealReadingHasThreeRows(pdf);
        // The planted values really are in the statement's text, so their absence below is earned.
        String realText = String.join("\n", PdfGeneratorTestSupport.generator().acquire(pdf, null).runs()
                .stream().map(PositionedText::text).toList());
        assertThat(realText).contains(PLANTED);
        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000002", "PDF", pdf));
        assertThat(copy.trace()).doesNotContain(PLANTED).doesNotContain(".pdf");
        assertThat(copy.trace()).contains("held item HLD-2026-000002");
        assertThat(copy.toString()).doesNotContain(PLANTED);
        assertThat(copy.differences()).isEmpty();
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.MATCHES);
    }

    /**
     * End to end, a statement whose reading depends on a word the copy masks: the direction is
     * printed only as a bare "C" or "D", the redactor masks a lone letter to "X", and the copy's
     * reading has no type to read, so its credit rows come back as money out. The check must say
     * so, name exactly those rows and that field, and carry nothing from the statement.
     */
    @Test
    void aStatementWhoseDirectionIsAMaskedLetterDoesNotMatch() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildDirectionOnlyAsABareLetterSample();
        // The real reading takes the direction from the letter, so the mismatch below is the copy's.
        var real = PdfGeneratorTestSupport.generator()
                .generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", pdf).sections();
        assertThat(real).hasSize(1);
        assertThat(real.get(0).rows()).extracting(StagedRow::type)
                .containsExactly("EXPENSE", "INCOME", "EXPENSE", "INCOME");

        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000005", "PDF", pdf));

        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.DIFFERS);
        assertThat(copy.notCheckedReason()).isNull();
        assertThat(copy.differences())
                .extracting(BlankedCopyComparison.Difference::section, BlankedCopyComparison.Difference::row,
                        BlankedCopyComparison.Difference::field)
                .containsExactly(tuple(0, 1, BlankedCopyComparison.Field.DIRECTION),
                        tuple(0, 3, BlankedCopyComparison.Field.DIRECTION));
        assertThat(copy.differences()).flatExtracting(BlankedCopyComparison.Difference::maskedTokens)
                .allMatch(t -> t.matches("[Xx9\\p{Punct}]+"));
        assertThat(copy.differences().toString()).doesNotContain("EMPLOYER", "SENDER", "MERCHANT", "PAYEE");
        // Where the statement printed C and D, the copy holds the masked letter.
        assertThat(TraceFormat.parse(copy.trace())).extracting(PositionedText::text)
                .doesNotContain("C", "D").contains("X");
        assertThat(produced(BlankedCopy.Check.DIFFERS)).isEqualTo(1);
    }

    @Test
    void theReplayNeverUsesTheCustomersId() throws Exception {
        PdfPreviewGenerator spy = spy(PdfGeneratorTestSupport.generator());
        byte[] pdf = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        service(spy).make(new StaffStatementContent("HLD-2026-000003", "PDF", pdf));
        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        verify(spy, times(2)).generateSectionsFromAcquired(ids.capture(), eq("statement.pdf"), any());
        assertThat(ids.getAllValues().get(0)).isEqualTo(ids.getAllValues().get(1));
    }

    /** The real side reads the acquired runs; the copy side reads the trace FILE's round trip, not
     *  the redacted runs held in memory, because the file is what is handed over. */
    @Test
    void theCopySideReadsTheTraceFileItself() throws Exception {
        PdfPreviewGenerator spy = spy(PdfGeneratorTestSupport.generator());
        byte[] pdf = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        BlankedCopy copy = service(spy).make(new StaffStatementContent("HLD-2026-000004", "PDF", pdf));
        ArgumentCaptor<AcquiredDocument> read = ArgumentCaptor.forClass(AcquiredDocument.class);
        verify(spy, times(2)).generateSectionsFromAcquired(any(), any(), read.capture());
        List<PositionedText> realRuns = PdfGeneratorTestSupport.generator().acquire(pdf, null).runs();
        assertThat(realRuns).isNotEmpty();
        assertThat(read.getAllValues().get(0).runs()).isEqualTo(realRuns);
        assertThat(read.getAllValues().get(1).runs()).isEqualTo(TraceFormat.parse(copy.trace()));
    }

    @Test
    void aLayoutWithNoTableStillGetsACopy() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doReturn(AcquiredDocument.of(List.of(new PositionedText("Xyz Bank letter to Asha", 10f, 10f, 0, 40f))))
                .when(generator).acquire(any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-1", "PDF", new byte[]{1}));
        assertThat(copy.trace()).doesNotContain("Asha");
        assertThat(copy.check()).isIn(BlankedCopy.Check.MATCHES, BlankedCopy.Check.DIFFERS);
    }

    @Test
    void aDocumentWithNoTextGetsAnEmptyCopy() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doReturn(AcquiredDocument.of(List.of())).when(generator).acquire(any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-2", "PDF", new byte[]{1}));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.MATCHES);
    }

    @Test
    void ocrRunsStillProduceACopyAndAVerdict() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        var ocr = new PositionedText("Date", 10f, 10f, 0, 20f, 8f, 0.9f, TextSource.OCR);
        doReturn(AcquiredDocument.of(List.of(ocr))).when(generator).acquire(any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-3", "PDF", new byte[]{1}));
        assertThat(copy.check()).isIn(BlankedCopy.Check.MATCHES, BlankedCopy.Check.DIFFERS);
    }

    @Test
    void aRefusalNamesKindsNotValues() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        // See the class comment: the local part is kept and the domain masks to "x.xx", still an email.
        doReturn(AcquiredDocument.of(List.of(new PositionedText("statement@a.in", 0f, 0f, 0, 10f)))) // synthetic-ok: invented, two-letter domain
                .when(generator).acquire(any(), any());
        assertThatThrownBy(() -> service(generator).make(new StaffStatementContent("j-4", "PDF", new byte[]{1})))
                .isInstanceOfSatisfying(BlankedCopyRefusedException.class,
                        e -> assertThat(e.kinds()).containsExactly("email address"))
                .hasMessage("The blanked copy was refused: it still contained email address.")
                .hasMessageNotContaining("statement@");
        assertThat(registry.find("finora.blanked_copy.refused").counter().count()).isEqualTo(1);
        assertThat(registry.find("finora.blanked_copy.produced").counters()).isEmpty();
    }

    /** The refusal reached from a real PDF through the real redactor and scan, not a spy. */
    @Test
    void aStatementWhoseEmailSurvivesIsRefused() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildSampleWithAnEmailTheRedactorCannotBlank();
        assertThatThrownBy(() -> service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("j-7", "PDF", pdf)))
                .isInstanceOfSatisfying(BlankedCopyRefusedException.class,
                        e -> assertThat(e.kinds()).containsExactly("email address"))
                .hasMessageNotContaining("statement@");
        assertThat(registry.find("finora.blanked_copy.produced").counters()).isEmpty();
    }

    /**
     * Devanagari and Tamil through the real redactor and scan. The fixture PDFs are drawn in
     * Helvetica, whose WinAnsi encoding has no Indic glyphs, so these scripts cannot be planted in
     * a PDF; the acquired runs are stubbed instead. {@code theCopyCarriesNoCustomerTextAndNoFileName}
     * covers an accented-Latin name from a real PDF.
     */
    @Test
    void textInAnotherScriptDoesNotReachTheCopy() throws Exception {
        String name = "\u0905\u092E\u093F\u0924 \u0936\u0930\u094D\u092E\u093E"; // synthetic-ok: invented Devanagari name
        String tamil = "\u0BB5\u0BA3\u0B95\u0BCD\u0B95\u0BAE\u0BCD"; // synthetic-ok: invented Tamil word
        String digits = "\u0969\u0968\u096D\u096E\u096F\u0966\u0967\u0968\u0969\u096A"; // synthetic-ok: invented
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doReturn(AcquiredDocument.of(List.of(
                new PositionedText("Nominee " + name, 10f, 700f, 0, 80f),
                new PositionedText(tamil + " " + digits, 10f, 690f, 0, 80f))))
                .when(generator).acquire(any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-8", "PDF", new byte[]{1}));
        assertThat(copy.trace()).doesNotContain(name, tamil, digits);
        assertThat(copy.trace().codePoints().filter(cp -> cp > 127)).isEmpty();
    }

    @Test
    void aCsvIsRefused() {
        assertThatThrownBy(() -> service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("j-5", "CSV", "a,b".getBytes())))
                .hasMessage("Blanked copy not available for CSV yet.");
    }

    @Test
    void aLockedPdfIsRefused() throws Exception {
        byte[] locked = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildReferenceNumberAndBalanceSample(), "pw");
        assertThatThrownBy(() -> service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("j-6", "PDF", locked)))
                .hasMessage("This statement is password-protected and no password is saved.");
    }

    @Test
    void aFailedReplayIsNotChecked() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doThrow(new IllegalStateException("boom: Asha")).when(generator)
                .generateSectionsFromAcquired(any(), any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-7", "PDF",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample()));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.NOT_CHECKED);
        assertThat(copy.notCheckedReason()).isEqualTo("The statement itself could not be read (IllegalStateException).");
        assertThat(copy.toString()).doesNotContain("Asha");
        assertThat(produced(BlankedCopy.Check.NOT_CHECKED)).isEqualTo(1);
    }

    /** An anonymous exception class has an empty simple name: the reason names its full name
     *  rather than "()". */
    @Test
    void aFailureFromAnAnonymousExceptionClassStillNamesIt() throws Exception {
        RuntimeException anonymous = new RuntimeException("boom: Asha") {};
        assertThat(anonymous.getClass().getSimpleName()).isEmpty();
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doThrow(anonymous).when(generator).generateSectionsFromAcquired(any(), any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-11", "PDF",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample()));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.NOT_CHECKED);
        assertThat(copy.notCheckedReason()).isEqualTo(
                "The statement itself could not be read (" + anonymous.getClass().getName() + ").");
        assertThat(copy.toString()).doesNotContain("Asha");
    }

    /** Both readings ran but could not be compared (here: a reading came back with no section
     *  list at all). The reason names the comparison, not either reading. */
    @Test
    void readingsThatCannotBeComparedAreNotCheckedAndSaySo() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doReturn(new PdfPreviewGenerator.PdfGenerationResult(null, null, null)).when(generator)
                .generateSectionsFromAcquired(any(), any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-10", "PDF",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample()));
        verify(generator, times(2)).generateSectionsFromAcquired(any(), any(), any());
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.NOT_CHECKED);
        assertThat(copy.notCheckedReason()).isEqualTo("The two readings could not be compared (NullPointerException).");
        assertThat(produced(BlankedCopy.Check.NOT_CHECKED)).isEqualTo(1);
    }

    /** The real statement reads, the copy does not: the reason says which side failed. */
    @Test
    void aCopyThatCannotBeReadBackIsNotCheckedAndSaysSo() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doCallRealMethod().doThrow(new IllegalArgumentException("boom: Asha")).when(generator)
                .generateSectionsFromAcquired(any(), any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-9", "PDF",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample()));
        verify(generator, times(2)).generateSectionsFromAcquired(any(), any(), any());
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.NOT_CHECKED);
        assertThat(copy.notCheckedReason()).isEqualTo("The copy could not be read back (IllegalArgumentException).");
        assertThat(copy.trace()).startsWith("# finora-pdf-trace");
        assertThat(copy.toString()).doesNotContain("Asha");
        assertThat(produced(BlankedCopy.Check.NOT_CHECKED)).isEqualTo(1);
    }
}
