package com.finora.imports;

import com.finora.imports.blanked.BlankedCopy;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The blanked-copy service against the real reading pipeline (persistence mocked).
 *
 * <p>On the refusal test: the redactor masks every email it meets character by character, and a
 * masked local part or domain of four or more letters becomes a run like "xxxx", which the privacy
 * scan's placeholder rule exempts. So most emails cannot survive. One shape does: a vocabulary word
 * as the local part ("statement" is kept) and a domain too short to mask into a run of four
 * ("a.in" becomes "x.xx"). {@code aRefusalNamesKindsNotValues} plants exactly that, so the refusal
 * path is reached through the real redactor and scan rather than through a test seam.
 */
class BlankedCopyServiceTest {

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
        assertThat(realText).contains(PdfFixtureBuilder.PLANTED_NAME, PdfFixtureBuilder.PLANTED_PHONE,
                PdfFixtureBuilder.PLANTED_EMAIL, PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER);
        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000002", "PDF", pdf));
        assertThat(copy.trace()).doesNotContain(PdfFixtureBuilder.PLANTED_NAME,
                PdfFixtureBuilder.PLANTED_PHONE, PdfFixtureBuilder.PLANTED_EMAIL,
                PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER, ".pdf");
        assertThat(copy.trace()).contains("held item HLD-2026-000002");
        assertThat(copy.toString()).doesNotContain(PdfFixtureBuilder.PLANTED_NAME,
                PdfFixtureBuilder.PLANTED_PHONE, PdfFixtureBuilder.PLANTED_EMAIL,
                PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER);
        assertThat(copy.differences()).isEmpty();
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.MATCHES);
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
        assertThat(copy.notCheckedReason()).isEqualTo("The reading could not be repeated (IllegalStateException).");
        assertThat(copy.toString()).doesNotContain("Asha");
        assertThat(produced(BlankedCopy.Check.NOT_CHECKED)).isEqualTo(1);
    }
}
