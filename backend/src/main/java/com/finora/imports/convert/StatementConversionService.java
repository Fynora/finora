package com.finora.imports.convert;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ExtractionCheck;
import com.finora.imports.ImportVerifier;
import com.finora.imports.StagedAccountSectionFilter;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.acquisition.NativePdfAcquirer;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.uploads.UploadScanGate;
import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The free statement converter: a visitor with no account sends one statement PDF and gets its
 * transactions back, to save as a spreadsheet in their browser. Design 2026-10-31, approved by Sid.
 *
 * <h2>Nothing is kept</h2>
 * The file and the transactions exist only in this request's memory. No import session, no job, no
 * stored file, no evidence row, no audit row (except one with no name and no user for a file the
 * virus scanner rejects), and no log line with anything from the statement in it. The one write is
 * a daily counter ({@link ConverterStats}). The visitor's address is held only in the rate limiter's
 * Redis keys, for at most 24 hours. Proven by {@code StatementConversionIT}: a conversion changes no
 * row in any table but the counter, and a statement's text never appears in the logs.
 *
 * <h2>Read the converter's way</h2>
 * A parser instance of its own: native text only (no OCR -- a scanned statement is refused rather
 * than costing about 100 s of recognition), and a normalizer that looks nothing up
 * ({@link TransactionNormalizer#withoutAccount()}): no categories, no duplicate checks, no query.
 *
 * <h2>Only confirmed amounts</h2>
 * Sid's rule: a spreadsheet only when {@link AmountsConfirmed} says the statement's own figures
 * confirm its amounts. Otherwise {@link ErrorCode#CONVERT_NOT_CONFIRMED}, never a file with a warning.
 *
 * <h2>Cost and abuse</h2>
 * Turnstile on every conversion, then per-address and global limits ({@link ConverterLimits}),
 * 5 MB, 20 pages, a pool of its own (2 at a time, refused at once when full -- never the import
 * pool, so the converter can never make a paying user's import "busy"), and a 60 s deadline.
 * Off unless {@code app.converter.enabled} is true.
 */
@Service
public class StatementConversionService {

    private static final Logger log = LoggerFactory.getLogger(StatementConversionService.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    static final String SCAN_CONTEXT = "statement-convert";

    private final boolean enabled;
    private final long maxBytes;
    private final int maxPages;
    private final long deadlineSeconds;
    private final TurnstileVerifier turnstile;
    private final ConverterLimits limits;
    private final ConverterStats stats;
    private final UploadScanGate scanGate;
    private final PdfPreviewGenerator parser;
    private final Semaphore permits;
    private final ExecutorService workers;
    /** The parse step. A seam only so tests can hold a place in the pool or overrun the deadline;
     *  production never replaces it. */
    ParseStep parseStep = this::parse;

    @FunctionalInterface
    interface ParseStep {
        ConvertDto.Result run(byte[] pdf, String password, Outcome outcome) throws Exception;
    }

    public StatementConversionService(@Value("${app.converter.enabled:false}") boolean enabled,
                                      @Value("${app.converter.max-bytes:5242880}") long maxBytes,
                                      @Value("${app.converter.max-pages:20}") int maxPages,
                                      @Value("${app.converter.max-concurrent:2}") int maxConcurrent,
                                      @Value("${app.converter.deadline-seconds:60}") long deadlineSeconds,
                                      TurnstileVerifier turnstile, ConverterLimits limits, ConverterStats stats,
                                      UploadScanGate scanGate, PdfTextExtractor textExtractor,
                                      PdfTableLocator tableLocator, PdfMetadataExtractor metadataExtractor,
                                      ProductDiscovery productDiscovery, ProductAttributeExtractor attributeExtractor,
                                      ImportVerifier importVerifier) {
        this.enabled = enabled;
        this.maxBytes = maxBytes;
        this.maxPages = maxPages;
        this.deadlineSeconds = deadlineSeconds;
        this.turnstile = turnstile;
        this.limits = limits;
        this.stats = stats;
        this.scanGate = scanGate;
        // No rule engine: a statement read with no account never asks for anyone's rules.
        this.parser = new PdfPreviewGenerator(new NativePdfAcquirer(textExtractor), tableLocator, metadataExtractor,
                TransactionNormalizer.withoutAccount(), productDiscovery, attributeExtractor, importVerifier, null);
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
        AtomicInteger threadNumber = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(Math.max(1, maxConcurrent), runnable -> {
            Thread thread = new Thread(runnable, "statement-convert-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }

    public ConvertDto.Availability availability() {
        return new ConvertDto.Availability(enabled, maxBytes, maxPages);
    }

    /**
     * @param clientIp the visitor's address as {@code ClientIpResolver} resolved it; used only as a
     *                 rate-limit key
     */
    public ConvertDto.Result convert(ConvertDto.Request request, String clientIp) {
        if (!enabled) throw new ApiException(ErrorCode.CONVERT_UNAVAILABLE);
        long started = System.nanoTime();
        Outcome outcome = new Outcome();
        try {
            ConvertDto.Result result = convertCounted(request, clientIp, outcome);
            outcome.code = "CONVERTED";
            return result;
        } catch (ApiException refused) {
            outcome.code = refused.getCode() == null ? "REFUSED" : refused.getCode().name();
            throw refused;
        } catch (RuntimeException unexpected) {
            outcome.code = ErrorCode.CONVERT_FAILED.name();
            logWithoutMessage("Statement conversion failed outside the parser", unexpected);
            throw new ApiException(ErrorCode.CONVERT_FAILED);
        } finally {
            stats.count(outcome.code, outcome.bank, outcome.rows);
            // One content-free line per conversion. No address, no file name, nothing read.
            log.info("Statement conversion: outcome={} pages={} rows={} bank={} ms={}", outcome.code,
                    outcome.pages, outcome.rows, outcome.bank == null ? ConverterStats.NO_BANK : outcome.bank,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    /** What the log line and the counter say about one conversion. Written by the parse thread,
     *  read by the request thread, hence volatile. */
    static final class Outcome {
        volatile String code = "REFUSED";
        volatile String bank;
        volatile int rows;
        volatile int pages = -1;
    }

    private ConvertDto.Result convertCounted(ConvertDto.Request request, String clientIp, Outcome outcome) {
        if (request == null || !Boolean.TRUE.equals(request.consent())) {
            throw new ApiException(ErrorCode.CONVERT_CONSENT_REQUIRED);
        }
        if (!turnstile.verify(request.turnstileToken())) {
            throw new ApiException(ErrorCode.CONVERT_CHALLENGE_FAILED);
        }
        ConverterLimits.Slots slots = limits.take(clientIp == null ? "unknown" : clientIp);
        if (slots == null) throw new ApiException(ErrorCode.CONVERT_LIMIT_REACHED);
        try {
            return convertWithinLimits(request, outcome);
        } catch (ApiException refused) {
            // Not the visitor's doing, or the first step of the password prompt: no allowance used.
            if (refused.getCode() == ErrorCode.CONVERT_BUSY
                    || refused.getCode() == ErrorCode.UPLOAD_SCANNER_UNAVAILABLE
                    || refused.getCode() == ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED) {
                limits.giveBack(slots);
            }
            throw refused;
        }
    }

    private ConvertDto.Result convertWithinLimits(ConvertDto.Request request, Outcome outcome) {
        byte[] pdf = decode(request.file());
        if (pdf.length == 0) throw new ApiException(ErrorCode.IMPORT_EMPTY_FILE);
        if (pdf.length > maxBytes) throw new ApiException(ErrorCode.CONVERT_TOO_LARGE);
        if (!startsWithPdfMagic(pdf)) throw new ApiException(ErrorCode.IMPORT_WRONG_FILE_TYPE);

        scanGate.requireCleanAnonymous(pdf, SCAN_CONTEXT);

        String password = passwordFor(pdf, request.password());
        outcome.pages = pageCount(pdf, password);
        if (outcome.pages > maxPages) throw new ApiException(ErrorCode.CONVERT_TOO_MANY_PAGES);

        if (!permits.tryAcquire()) throw new ApiException(ErrorCode.CONVERT_BUSY);
        Future<ConvertDto.Result> work;
        try {
            work = workers.submit(() -> {
                try {
                    return parseStep.run(pdf, password, outcome);
                } finally {
                    // Released when the parse really ends, not at the deadline: a parse that ignores
                    // the interrupt still holds its memory, so it still holds its place.
                    permits.release();
                }
            });
        } catch (RejectedExecutionException e) {
            permits.release();
            throw new ApiException(ErrorCode.CONVERT_BUSY);
        }
        try {
            return work.get(deadlineSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            work.cancel(true);
            log.warn("Statement conversion passed its {} s deadline and was abandoned", deadlineSeconds);
            throw new ApiException(ErrorCode.CONVERT_FAILED);
        } catch (InterruptedException e) {
            work.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.CONVERT_FAILED);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException refused) throw refused;
            logWithoutMessage("Statement conversion failed in the parser", cause);
            throw new ApiException(ErrorCode.CONVERT_FAILED);
        }
    }

    private ConvertDto.Result parse(byte[] pdf, String password, Outcome outcome) throws java.io.IOException {
        // No user (null): the parser's no-account path, which looks nothing up.
        PdfPreviewGenerator.PdfGenerationResult generated =
                parser.generateSectionsWithContext(null, "statement.pdf", pdf, password);
        List<StagedAccountSection> sections =
                StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(generated.sections());
        outcome.bank = bankIdOf(sections);
        // The app's own refusals for a document with nothing to import: scanned, payment-app history,
        // no table, no transactions, no activity in the period.
        ExtractionCheck.rejectIfNothingWasExtracted(sections, generated.documentContext());
        outcome.rows = sections.stream().mapToInt(section -> section.rows().size()).sum();

        AmountsConfirmed.Verdict verdict = AmountsConfirmed.of(sections, LocalDate.now(INDIA));
        if (!verdict.confirmed()) {
            log.info("Statement conversion not confirmed: {}", String.join(",", verdict.reasons()));
            throw new ApiException(ErrorCode.CONVERT_NOT_CONFIRMED);
        }
        List<ConvertDto.Section> out = new ArrayList<>();
        for (StagedAccountSection section : sections) {
            if (section.rows().isEmpty()) continue;
            out.add(sectionOf(section));
        }
        return new ConvertDto.Result(outcome.pages, List.copyOf(out));
    }

    static ConvertDto.Section sectionOf(StagedAccountSection section) {
        var account = section.detectedAccount();
        List<ConvertDto.Row> rows = new ArrayList<>(section.rows().size());
        for (StagedRow row : section.rows()) {
            boolean moneyIn = "INCOME".equals(row.type());
            rows.add(new ConvertDto.Row(row.date(), row.description(),
                    moneyIn ? null : row.amount(), moneyIn ? row.amount() : null,
                    row.balanceAfter(), row.referenceNumber()));
        }
        List<String> checks = section.verification() == null || section.verification().findings() == null
                ? List.of()
                : section.verification().findings().stream()
                        .filter(f -> f != null && AmountsConfirmed.AMOUNT_PROOFS.contains(f.rule())
                                && "VERIFIED".equals(f.outcome()))
                        .map(f -> f.rule()).distinct().toList();
        boolean bankKnown = account != null && account.bank() != null
                && !com.finora.util.BankRegistry.UNKNOWN_ID.equals(account.bank().id());
        return new ConvertDto.Section(
                bankKnown ? account.bank().officialName() : null,
                account == null ? null : account.suggestedAccountType(),
                account == null ? null : account.accountNumberMasked(),
                account == null ? null : account.statementPeriodStart(),
                account == null ? null : account.statementPeriodEnd(),
                account == null ? null : account.openingBalance(),
                account == null ? null : account.closingBalance(),
                List.copyOf(rows), checks);
    }

    private static String bankIdOf(List<StagedAccountSection> sections) {
        return sections.stream()
                .map(StagedAccountSection::detectedAccount)
                .filter(a -> a != null && a.bank() != null)
                .map(a -> a.bank().id())
                .findFirst().orElse(null);
    }

    private byte[] decode(String base64) {
        if (base64 == null || base64.isEmpty()) throw new ApiException(ErrorCode.IMPORT_EMPTY_FILE);
        // A base64 string a third longer than the cap cannot decode to a file within it.
        if (base64.length() > (maxBytes / 3 + 1) * 4 + 4) throw new ApiException(ErrorCode.CONVERT_TOO_LARGE);
        try {
            // Strict: a browser's base64 has no line breaks, and a lenient decoder skips characters
            // it does not know, turning a broken upload into a different file instead of an error.
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.CONVERT_BAD_REQUEST);
        }
    }

    private static boolean startsWithPdfMagic(byte[] pdf) {
        if (pdf.length < PDF_MAGIC.length) return false;
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (pdf[i] != PDF_MAGIC[i]) return false;
        }
        return true;
    }

    /** The password to open the PDF with: null for an unlocked one (a password sent with it is
     *  dropped), the typed one when it opens a locked one. Never stored, never logged. */
    private static String passwordFor(byte[] pdf, String typed) {
        if (!PdfTextExtractor.needsPassword(new ByteArrayInputStream(pdf))) return null;
        if (typed == null || typed.isEmpty()) throw new ApiException(ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED);
        if (!PdfTextExtractor.opensWith(new ByteArrayInputStream(pdf), typed)) {
            throw new ApiException(ErrorCode.IMPORT_PDF_PASSWORD_INVALID);
        }
        return typed;
    }

    private static int pageCount(byte[] pdf, String password) {
        try (PDDocument document = password == null ? Loader.loadPDF(pdf) : Loader.loadPDF(pdf, password)) {
            return document.getNumberOfPages();
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.IMPORT_CORRUPT_PDF);
        }
    }

    /**
     * Logs a failure by its type and stack frames only. A parser's exception message can quote the
     * statement (SentryScrubber's doc: "the messages its parsers produce quote the input that
     * failed"), so neither the message nor any cause -- each has its own message -- is logged.
     */
    static void logWithoutMessage(String what, Throwable failure) {
        Throwable bare = new ConversionFailure(failure);
        log.warn("{}: {}", what, failure == null ? "unknown" : failure.getClass().getName(), bare);
    }

    /** A copy of a failure with its type as its only message and no cause. */
    static final class ConversionFailure extends RuntimeException {
        ConversionFailure(Throwable original) {
            super(original == null ? "unknown" : original.getClass().getName(), null, false, true);
            if (original != null) setStackTrace(original.getStackTrace());
        }
    }
}
