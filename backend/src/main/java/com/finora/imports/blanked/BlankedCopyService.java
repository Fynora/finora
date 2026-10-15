package com.finora.imports.blanked;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.exception.ApiException;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.RowLines;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.trace.PdfTraceRedactor;
import com.finora.imports.pdf.trace.TraceFormat;
import com.finora.imports.pdf.trace.TraceMetadata;
import com.finora.imports.pdf.trace.TraceValidator;
import com.finora.observability.BlankedCopyMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Turns a held statement into a blanked copy staff may take away, and says whether that copy reads
 * the same as the statement. See docs/superpowers/specs/2026-10-14-fixing-statements-without-the-
 * real-statement-design.md, project 1.
 *
 * <p>The real bytes and runs never leave this method: nothing from them is logged, thrown or
 * returned. Only the held item's reference and exception class names are ever logged, and a
 * refusal names kinds of data, never the data.
 *
 * <p>The copy side of the check reads the trace FILE's own round trip, not the redacted runs in
 * memory: the file is what Claude gets, and it is not an exact copy of those runs. {@code
 * TraceFormat} writes x, y and width rounded to two decimals and replaces a tab inside a run's text
 * with a space, so a reading that depends on either can honestly come back DIFFERS.
 *
 * <p>Both readings use one fresh random user id, so no customer's rules, merchants, duplicates or
 * resolutions are read, and the two readings cannot differ because of them. Both are given the
 * same neutral file name, never the customer's own.
 */
@Service
public class BlankedCopyService {

    private static final Logger log = LoggerFactory.getLogger(BlankedCopyService.class);
    /** The file name both readings are given: never the customer's own. */
    static final String READING_NAME = "statement.pdf";

    private final PdfPreviewGenerator generator;
    private final BlankedCopyMetrics metrics;
    private final Clock clock;

    /** The application has no {@link Clock} bean, and adding a global one could change which
     *  constructor Spring picks for other classes that take a Clock, so this one supplies its own. */
    @Autowired
    public BlankedCopyService(PdfPreviewGenerator generator, BlankedCopyMetrics metrics) {
        this(generator, metrics, Clock.systemUTC());
    }

    public BlankedCopyService(PdfPreviewGenerator generator, BlankedCopyMetrics metrics, Clock clock) {
        this.generator = generator;
        this.metrics = metrics;
        this.clock = clock;
    }

    public BlankedCopy make(StaffStatementContent content) {
        if (!"PDF".equalsIgnoreCase(content.sourceFormat())) {
            throw new ApiException(HttpStatus.CONFLICT, "Blanked copy not available for CSV yet.");
        }
        if (PdfTextExtractor.needsPassword(new ByteArrayInputStream(content.content()))) {
            throw new ApiException(HttpStatus.CONFLICT, "This statement is password-protected and no password is saved.");
        }
        AcquiredDocument real;
        try {
            real = generator.acquire(content.content(), null);
        } catch (Exception e) {
            log.warn("Blanked copy for {}: text acquisition failed ({})", content.reference(), typeName(e));
            throw new ApiException(HttpStatus.CONFLICT, "The statement's text could not be read.");
        }

        List<PositionedText> redacted = PdfTraceRedactor.redact(real.runs());
        // Two checks: the copy on its own, and the copy against the statement digit by digit.
        java.util.LinkedHashSet<String> refused = new java.util.LinkedHashSet<>(TraceValidator.privacyKinds(redacted));
        refused.addAll(TraceValidator.copyLeaks(real.runs(), redacted));
        List<String> kinds = List.copyOf(refused);
        if (!kinds.isEmpty()) {
            metrics.refused();
            throw new BlankedCopyRefusedException(kinds);
        }
        // Built from ids, versions and the date only, so the privacy check above scans the runs and
        // not these lines. BlankedCopyServiceTest.theMetadataHoldsOnlyServerBuiltFields pins them:
        // free text added here must be scanned before it ships.
        TraceMetadata metadata = new TraceMetadata(TraceMetadata.CURRENT_TRACE_VERSION,
                PdfTraceRedactor.REDACTOR_VERSION, PdfTraceRedactor.allowlistFingerprint(),
                LocalDate.now(clock).toString(), "held item " + content.reference(),
                List.of(), List.of(), "", List.of());
        String trace = TraceFormat.format(redacted, metadata);
        String fileName = "blanked-" + content.reference() + ".trace";

        BlankedCopy copy = check(content.reference(), fileName, trace, real);
        metrics.produced(copy.check().name());
        return copy;
    }

    /** Reads both sides and compares them. A failure on either side marks the copy not checked,
     *  with a reason that says which side failed and the exception's class name only.
     *
     *  <p>Both readings also report their row maps (which printed lines each shown row came from),
     *  and the copy ends with the real reading's, as {@link RowMapBlock} comment lines: positions and
     *  row numbers only, which the privacy scan above did not need to see. When the real reading
     *  failed there is no row map to add. */
    private BlankedCopy check(String reference, String fileName, String trace, AcquiredDocument real) {
        UUID nobody = UUID.randomUUID();
        List<StagedAccountSection> realReading;
        Map<Integer, Map<Integer, RowLines>> realRowMap = new TreeMap<>();
        try {
            realReading = generator.generateSectionsFromAcquired(nobody, READING_NAME, real, realRowMap::put).sections();
        } catch (Exception e) {
            return notChecked(reference, fileName, trace, "the statement's own reading failed",
                    "The statement itself could not be read", e);
        }
        String traceWithRowMap = trace + RowMapBlock.format(realRowMap);
        List<StagedAccountSection> copyReading;
        Map<Integer, Map<Integer, RowLines>> copyRowMap = new TreeMap<>();
        try {
            copyReading = generator.generateSectionsFromAcquired(nobody, READING_NAME,
                    AcquiredDocument.of(TraceFormat.parse(trace)), copyRowMap::put).sections();
        } catch (Exception e) {
            return notChecked(reference, fileName, traceWithRowMap, "the copy's reading failed",
                    "The copy could not be read back", e);
        }
        try {
            BlankedCopyComparison.Result result =
                    BlankedCopyComparison.compare(realReading, copyReading, realRowMap, copyRowMap);
            return new BlankedCopy(fileName, traceWithRowMap,
                    result.matches() ? BlankedCopy.Check.MATCHES : BlankedCopy.Check.DIFFERS,
                    null, result.differences());
        } catch (Exception e) {
            return notChecked(reference, fileName, traceWithRowMap, "the comparison failed",
                    "The two readings could not be compared", e);
        }
    }

    private static BlankedCopy notChecked(String reference, String fileName, String trace, String logWhat,
                                          String reason, Exception e) {
        String type = typeName(e);
        log.warn("Blanked copy for {}: the check could not run, {} ({})", reference, logWhat, type);
        return new BlankedCopy(fileName, trace, BlankedCopy.Check.NOT_CHECKED, reason + " (" + type + ").", List.of());
    }

    /** The exception's class name, never its message. An anonymous class has an empty simple name,
     *  so its full name is used instead. */
    private static String typeName(Exception e) {
        String simple = e.getClass().getSimpleName();
        return simple.isEmpty() ? e.getClass().getName() : simple;
    }
}
