package com.finora.imports.blanked;

import com.finora.exception.ApiException;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.PositionedText;
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
 * memory: the file is what Claude gets, and it carries no run's source, height or confidence, so a
 * scanned statement can honestly come back DIFFERS.
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
            log.warn("Blanked copy for {}: text acquisition failed ({})", content.reference(), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.CONFLICT, "The statement's text could not be read.");
        }

        List<PositionedText> redacted = PdfTraceRedactor.redact(real.runs());
        List<String> kinds = TraceValidator.privacyKinds(redacted);
        if (!kinds.isEmpty()) {
            metrics.refused();
            throw new BlankedCopyRefusedException(kinds);
        }
        TraceMetadata metadata = new TraceMetadata(TraceMetadata.CURRENT_TRACE_VERSION,
                PdfTraceRedactor.REDACTOR_VERSION, PdfTraceRedactor.allowlistFingerprint(),
                LocalDate.now(clock).toString(), "held item " + content.reference(),
                List.of(), List.of(), "", List.of());
        String trace = TraceFormat.format(redacted, metadata);
        String fileName = "blanked-" + content.reference() + ".trace";

        BlankedCopy copy;
        try {
            UUID nobody = UUID.randomUUID();
            var realReading = generator.generateSectionsFromAcquired(nobody, READING_NAME, real).sections();
            var copyReading = generator.generateSectionsFromAcquired(nobody, READING_NAME,
                    AcquiredDocument.of(TraceFormat.parse(trace))).sections();
            BlankedCopyComparison.Result result = BlankedCopyComparison.compare(realReading, copyReading);
            copy = new BlankedCopy(fileName, trace,
                    result.matches() ? BlankedCopy.Check.MATCHES : BlankedCopy.Check.DIFFERS,
                    null, result.differences());
        } catch (Exception e) {
            log.warn("Blanked copy for {}: the check could not run ({})", content.reference(), e.getClass().getSimpleName());
            copy = new BlankedCopy(fileName, trace, BlankedCopy.Check.NOT_CHECKED,
                    "The reading could not be repeated (" + e.getClass().getSimpleName() + ").", List.of());
        }
        metrics.produced(copy.check());
        return copy;
    }
}
