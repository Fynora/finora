package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.blanked.BlankedCopy;
import com.finora.imports.blanked.BlankedCopyComparison;
import com.finora.imports.blanked.BlankedCopyRefusedException;
import com.finora.imports.blanked.BlankedCopyService;
import com.finora.imports.blanked.RowMapBlock;
import com.finora.imports.blanked.StaffStatementContent;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.RowLines;
import com.finora.imports.pdf.RowMapSink;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.trace.PdfTraceRedactor;
import com.finora.imports.pdf.trace.TraceFormat;
import com.finora.imports.pdf.trace.TraceMetadata;
import com.finora.imports.pdf.trace.TraceValidator;
import com.finora.observability.BlankedCopyMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

    /**
     * The metadata block is built by the server from ids, versions and the date only, so the
     * privacy check scans the runs and not these lines (a job id is a random UUID, which matches
     * the phone-number shape about once in 270). This pins exactly which lines and values it
     * writes: a future change that adds free text to the metadata fails here and has to be
     * scanned before it ships.
     */
    @Test
    void theMetadataHoldsOnlyServerBuiltFields() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildSampleWithPlantedCustomerData();
        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000008", "PDF", pdf));

        List<String> commentLines = copy.trace().lines()
                .filter(l -> l.startsWith("#") && !l.startsWith("# rowmap")).toList();
        // The row map block is server-built too: positions and row numbers only, pinned by shape.
        List<String> rowMapLines = copy.trace().lines().filter(l -> l.startsWith("# rowmap")).toList();
        assertThat(rowMapLines).isNotEmpty().first().isEqualTo("# rowmap v1");
        assertThat(rowMapLines.subList(1, rowMapLines.size())).isNotEmpty()
                .allMatch(l -> l.matches("# rowmap section \\d+ row \\d+:( p\\d+( \\d+\\.\\d{2})+)*"));
        assertThat(commentLines).containsExactly(
                "# finora-pdf-trace v" + TraceMetadata.CURRENT_TRACE_VERSION,
                "# traceVersion: " + TraceMetadata.CURRENT_TRACE_VERSION,
                "# redactorVersion: " + PdfTraceRedactor.REDACTOR_VERSION,
                "# allowlistFingerprint: " + PdfTraceRedactor.allowlistFingerprint(),
                "# capturedAt: 2026-10-14",
                "# generatedFrom: held item HLD-2026-000008",
                "# capabilities: ",
                "# regressions: ",
                "# requiredHeaders: ",
                "# motivation: ",
                "# page\tx\ty\twidth\ttext");
        TraceMetadata metadata = TraceMetadata.parse(copy.trace());
        assertThat(metadata.capabilities()).isEmpty();
        assertThat(metadata.regressions()).isEmpty();
        assertThat(metadata.requiredHeaders()).isEmpty();
        assertThat(metadata.motivation()).isEmpty();
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
     * printed only as a Type word, "Income" or "Expense", and neither word is kept, so the copy's
     * reading has no type to read and its income rows come back as money out. The check must say
     * so, name exactly those rows and that field, and carry nothing from the statement.
     */
    @Test
    void aStatementWhoseDirectionIsAMaskedWordDoesNotMatch() throws Exception {
        assertThat(PdfTraceRedactor.redactLine("Income")).isEqualTo("Xxxxxx");
        byte[] pdf = PdfFixtureBuilder.buildDirectionOnlyAsAWordSample();
        // The real reading takes the direction from the word, so the mismatch below is the copy's.
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
        // Where the statement printed the words, the copy holds them masked.
        assertThat(TraceFormat.parse(copy.trace())).extracting(PositionedText::text)
                .doesNotContain("Income", "Expense").contains("Xxxxxx", "Xxxxxxx");
        assertThat(produced(BlankedCopy.Check.DIFFERS)).isEqualTo(1);
    }

    /**
     * A direction printed only as a bare "C" or "D" is kept from redactor version 5 (the one-letter
     * markers TransactionNormalizer reads), so the copy reads the same directions and matches. Up to
     * version 4 the letter was masked and this statement was the end-to-end DIFFERS example.
     */
    @Test
    void aStatementWhoseDirectionIsABareLetterMatches() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildDirectionOnlyAsABareLetterSample();

        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000007", "PDF", pdf));

        assertThat(copy.differences()).isEmpty();
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.MATCHES);
        assertThat(TraceFormat.parse(copy.trace())).extracting(PositionedText::text).contains("C", "D");
    }

    /**
     * End to end, a statement whose row grouping depends on words the copy masks. The locator keeps
     * the footer lines "Generated on ..." and "Need help? Contact our support ..." out of the table
     * by their words; masked, they no longer read as footers, so the copy's reading groups them
     * into the last row's description. The check must name that row's DESCRIPTION, and its tokens
     * must be the copy's own masked forms only -- never a word of the statement.
     *
     * <p>Only the DESCRIPTION difference is asserted; any other field this statement differs in is
     * not what this test is about.
     */
    @Test
    void aMaskedFooterGroupedIntoTheLastRowIsADescriptionDifference() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildSignedAmountLedgerWithSupportFooterSample();
        // The real reading keeps both footer lines out of every description.
        var real = PdfGeneratorTestSupport.generator()
                .generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", pdf).sections();
        assertThat(real).hasSize(1);
        assertThat(real.get(0).rows()).extracting(StagedRow::description).containsExactly(
                "Account Transfer-Credit-SAMPLE", "Interest Cr. for 15-Sep-2026",
                "UPI-Debit-SAMPLE GROCER", "UPI-Debit-SAMPLE STORE");

        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000006", "PDF", pdf));

        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.DIFFERS);
        var description = copy.differences().stream()
                .filter(d -> d.field() == BlankedCopyComparison.Field.DESCRIPTION).toList();
        assertThat(description).singleElement().satisfies(d -> {
            assertThat(d.section()).isZero();
            assertThat(d.row()).isEqualTo(3);
            // The footer's words, as the copy masked them.
            assertThat(d.maskedTokens()).contains("Xxxxxxxxx", "Xxxx", "xxxx?", "xxxxxxx", "xxxx@xxxxxxx.xxx");
            String copyText = String.join(" ", TraceFormat.parse(copy.trace()).stream().map(PositionedText::text).toList());
            assertThat(d.maskedTokens()).allSatisfy(t -> {
                assertThat(t).matches("[Xx9\\p{Punct}]+");
                assertThat(copyText).contains(t);
            });
        });
        assertThat(copy.differences().toString())
                .doesNotContain("SAMPLE", "STORE", "Generated", "Need", "help", "support", "example");
    }

    @Test
    void theReplayNeverUsesTheCustomersId() throws Exception {
        PdfPreviewGenerator spy = spy(PdfGeneratorTestSupport.generator());
        byte[] pdf = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        service(spy).make(new StaffStatementContent("HLD-2026-000003", "PDF", pdf));
        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        verify(spy, times(2)).generateSectionsFromAcquired(ids.capture(), eq("statement.pdf"), any(), any());
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
        verify(spy, times(2)).generateSectionsFromAcquired(any(), any(), read.capture(), any());
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
                .generateSectionsFromAcquired(any(), any(), any(), any());
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
        doThrow(anonymous).when(generator).generateSectionsFromAcquired(any(), any(), any(), any());
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
                .generateSectionsFromAcquired(any(), any(), any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-10", "PDF",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample()));
        verify(generator, times(2)).generateSectionsFromAcquired(any(), any(), any(), any());
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.NOT_CHECKED);
        assertThat(copy.notCheckedReason()).isEqualTo("The two readings could not be compared (NullPointerException).");
        assertThat(produced(BlankedCopy.Check.NOT_CHECKED)).isEqualTo(1);
    }

    /** The real statement reads, the copy does not: the reason says which side failed. */
    @Test
    void aCopyThatCannotBeReadBackIsNotCheckedAndSaysSo() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doCallRealMethod().doThrow(new IllegalArgumentException("boom: Asha")).when(generator)
                .generateSectionsFromAcquired(any(), any(), any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-9", "PDF",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample()));
        verify(generator, times(2)).generateSectionsFromAcquired(any(), any(), any(), any());
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.NOT_CHECKED);
        assertThat(copy.notCheckedReason()).isEqualTo("The copy could not be read back (IllegalArgumentException).");
        assertThat(copy.trace()).startsWith("# finora-pdf-trace");
        assertThat(copy.toString()).doesNotContain("Asha");
        assertThat(produced(BlankedCopy.Check.NOT_CHECKED)).isEqualTo(1);
    }

    // ---- the row map (docs/superpowers/specs/2026-10-15-row-map-design.md)

    private static Map<Integer, Map<Integer, RowLines>> realRowMap(byte[] pdf) throws Exception {
        PdfPreviewGenerator generator = PdfGeneratorTestSupport.generator();
        Map<Integer, Map<Integer, RowLines>> map = new TreeMap<>();
        generator.generateSectionsFromAcquired(UUID.randomUUID(), "statement.pdf", generator.acquire(pdf, null), map::put);
        return map;
    }

    @Test
    void theCopyEndsWithTheRealReadingsRowMap() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000020", "PDF", pdf));

        Map<Integer, Map<Integer, RowLines>> expected = realRowMap(pdf);
        assertThat(expected).containsOnlyKeys(0);
        assertThat(expected.get(0)).hasSize(3);
        assertThat(RowMapBlock.parse(copy.trace())).isEqualTo(expected);
        assertThat(copy.trace()).endsWith(RowMapBlock.format(expected));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.MATCHES);
    }

    @Test
    void aRowThatUsesDifferentLinesInTheCopyIsAnotherDifference() throws Exception {
        PdfPreviewGenerator real = PdfGeneratorTestSupport.generator();
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            RowMapSink sink = invocation.getArgument(3);
            RowMapSink reported = calls.getAndIncrement() == 0 ? sink : (section, lines) -> {
                // The copy's reading reports row 0 as built from no line at all.
                Map<Integer, RowLines> altered = new TreeMap<>(lines);
                altered.put(0, RowLines.NONE);
                sink.section(section, altered);
            };
            return real.generateSectionsFromAcquired(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), reported);
        }).when(generator).generateSectionsFromAcquired(any(), any(), any(), any());

        BlankedCopy copy = service(generator).make(new StaffStatementContent("HLD-2026-000021", "PDF",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample()));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(copy.differences()).containsExactly(
                new BlankedCopyComparison.Difference(0, 0, BlankedCopyComparison.Field.ROW_LINES, List.of()));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.DIFFERS);
    }

    @Test
    void theRowMapBlockPassesThePrivacyChecks() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildSampleWithPlantedCustomerData();
        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000022", "PDF", pdf));
        assertThat(copy.trace()).contains("# rowmap section 1 row 1:");

        assertThat(TraceValidator.validate("copy", copy.trace()).findings())
                .noneMatch(finding -> finding.check().equals("pii"));
        List<PositionedText> copyRuns = TraceFormat.parse(copy.trace());
        assertThat(TraceValidator.privacyKinds(copyRuns)).isEmpty();
        assertThat(TraceValidator.copyLeaks(PdfGeneratorTestSupport.generator().acquire(pdf, null).runs(), copyRuns))
                .isEmpty();
    }

    @Test
    void aCopyThatCannotBeReadBackStillCarriesTheRealRowMap() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doCallRealMethod().doThrow(new IllegalArgumentException("boom")).when(generator)
                .generateSectionsFromAcquired(any(), any(), any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("HLD-2026-000023", "PDF", pdf));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.NOT_CHECKED);
        assertThat(RowMapBlock.parse(copy.trace())).isNotEmpty().isEqualTo(realRowMap(pdf));
    }

    @Test
    void aStatementThatCannotBeReadCarriesNoRowMap() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doThrow(new IllegalStateException("boom")).when(generator)
                .generateSectionsFromAcquired(any(), any(), any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("HLD-2026-000024", "PDF",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample()));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.NOT_CHECKED);
        assertThat(copy.trace()).doesNotContain("# rowmap");
    }
}
