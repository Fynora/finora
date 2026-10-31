package com.finora.uploads;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.StatementUpload;
import com.finora.service.AuditService;
import com.finora.uploads.MalwareScanner.ScanResult;
import com.finora.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The one call every upload endpoint makes before the bytes reach a parser.
 *
 * <p>Audit F-18 (2026-09-24). Enforced structurally, not by convention: {@code UploadScanGuardTest}
 * (FG-034) fails the build for any {@code @RestController} method that takes a
 * {@code MultipartFile} and does not call {@link #requireClean}. That is the half that makes this
 * a control rather than a utility -- see {@code docs/security/security-control-audit.md} on
 * masking for the same distinction.
 *
 * <h2>Policy when the scanner cannot answer</h2>
 * {@code app.malware-scan.on-unavailable} decides. {@code reject} (the default) refuses the upload
 * with a 503 and a message that says to try again shortly: for a product holding bank statements
 * the honest answer to "the scanner is down" is "not right now", and the rejection is logged at
 * error level so the outage is visible. {@code allow} logs the same warning and lets the upload
 * through, for an operator who has decided availability wins during a scanner incident. Neither
 * applies when no scanner is configured at all: then every upload is unscanned, silently here and
 * loudly at boot in the prod profile.
 *
 * <h2>Waiting for a scanner that is waking up</h2>
 * {@code app.malware-scan.unavailable-retry-window-ms} is how long an unanswered scan keeps being
 * retried before the policy above applies. It exists for a scanner that sleeps when idle: on
 * Railway the clamd service can be put to sleep (App Sleeping) and is woken by the first connection
 * to it, and until clamd has loaded its signatures every attempt answers UNAVAILABLE. Railway's
 * deploy logs put clamd's start-to-ready time at about 9 s (2026-09-25). Retries back off from
 * 0.5 s to a 4 s cap and never sleep past the window; an attempt already started inside the window
 * runs to its own timeouts, so the worst case is the window plus one attempt. Only UNAVAILABLE is
 * retried: CLEAN and INFECTED are verdicts, and an upload that cannot be read is the upload's
 * failure, not the scanner's, so it is refused at once. The file is re-read from the start for
 * every attempt.
 * {@code allow} waits too, so a waking scanner still gets to scan the file rather than it passing
 * unscanned on the first refusal. 0 means a single attempt.
 *
 * <p>The wait happens on the request thread, before any concurrency permit is taken (see below),
 * so a slow wake holds a servlet thread and nothing else. Mobile's non-upload calls (a Fyn
 * screenshot, a support attachment) give up after 30 s, which is why the production window is
 * below that; statement uploads have no client timeout.
 *
 * <h2>Why here and not inside {@code StatementUpload.requireReadable}</h2>
 * Order. The cheap structural checks run first so an empty file or a PDF posted to the CSV
 * endpoint is refused without a round trip to the scanner, and the scan runs before the
 * concurrency limiter's expensive work so a rejected file never takes one of its permits.
 * Callers place this between the two.
 */
@Component
public class UploadScanGate {

    public static final String AUDIT_ACTION_REJECTED = "UPLOAD_MALWARE_REJECTED";

    private static final Logger log = LoggerFactory.getLogger(UploadScanGate.class);

    private static final long FIRST_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 4_000;

    /** {@link Thread#sleep(long)}, as a seam so tests can wait without waiting. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final ObjectProvider<MalwareScanner> scanner;
    private final AuditService auditService;
    private final boolean rejectWhenUnavailable;
    private final long retryWindowNanos;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;

    @Autowired
    public UploadScanGate(ObjectProvider<MalwareScanner> scanner, AuditService auditService,
                          @Value("${app.malware-scan.on-unavailable:reject}") String onUnavailable,
                          @Value("${app.malware-scan.unavailable-retry-window-ms:0}") long retryWindowMs) {
        this(scanner, auditService, onUnavailable, retryWindowMs, System::nanoTime, Thread::sleep);
    }

    UploadScanGate(ObjectProvider<MalwareScanner> scanner, AuditService auditService, String onUnavailable,
                   long retryWindowMs, LongSupplier nanoClock, Sleeper sleeper) {
        this.scanner = scanner;
        this.auditService = auditService;
        this.rejectWhenUnavailable = !"allow".equalsIgnoreCase(onUnavailable);
        this.retryWindowNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, retryWindowMs));
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
    }

    /** Whether a scanner is configured at all -- for the admin diagnostics page and the boot-time
     *  warning, so "unscanned" is a reported state rather than an invisible one. */
    public boolean isScanning() {
        return scanner.getIfAvailable() != null;
    }

    /**
     * @param file    the upload; null or empty passes, since the caller's own emptiness check is
     *                what refuses those and there is nothing to scan
     * @param userId  who uploaded it, for the audit row a rejection writes
     * @param context which endpoint, for the same row: {@code statement-import},
     *                {@code support-attachment}, {@code fyn-screenshot}, {@code admin-analysis}
     * @throws ApiException 400 when the scanner matched a signature; 503 when it could not
     *                      answer and the policy is {@code reject}
     */
    public void requireClean(MultipartFile file, UUID userId, String context) {
        if (file == null || file.isEmpty()) {
            return;
        }
        check(file, file.getSize(), StatementUpload.safeFileName(file, "upload"), userId, context);
    }

    /**
     * The same scan for a file with no account and no name behind it: the free statement converter
     * ({@code statement-convert}), whose visitor is anonymous and whose file is never kept. A
     * rejection writes its audit row with no user and no file name -- the signature, the size and
     * the context are enough to see an attack, and a file name can carry a person's name.
     *
     * @param content the upload's bytes; null or empty passes, as for {@link #requireClean}
     */
    public void requireCleanAnonymous(byte[] content, String context) {
        if (content == null || content.length == 0) {
            return;
        }
        check(new org.springframework.core.io.ByteArrayResource(content), content.length, null, null, context);
    }

    /** @param fileName null when no name may be kept (the anonymous path) */
    private void check(org.springframework.core.io.InputStreamSource file, long size, String fileName,
                       UUID userId, String context) {
        MalwareScanner active = scanner.getIfAvailable();
        if (active == null) {
            return;
        }

        long started = nanoClock.getAsLong();
        long backoffMs = FIRST_BACKOFF_MS;
        int attempt = 0;
        ScanResult result;
        while (true) {
            attempt++;
            try (InputStream in = file.getInputStream()) {
                result = active.scan(in);
            } catch (IOException e) {
                // The upload, not the scanner: waiting does not make it readable.
                result = ScanResult.unavailable("could not read the upload for scanning: " + e);
                break;
            }
            if (result.status() != ScanResult.Status.UNAVAILABLE) {
                break;
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(retryWindowNanos - (nanoClock.getAsLong() - started));
            if (remainingMs <= 0) {
                break;
            }
            try {
                sleeper.sleep(Math.min(backoffMs, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - started);
        if (attempt > 1 && result.status() != ScanResult.Status.UNAVAILABLE) {
            log.info("Malware scanner {} answered on attempt {} after {} ms", active.describe(), attempt, waitedMs);
        }

        // For the log lines only; the audit row below keeps the raw values. safeFileName already
        // strips control characters, but a log argument is not something to leave to a different
        // method's promise, and the detail is the scanner's reply text (or an I/O error message),
        // which nothing here has bounded. The same helper the webhook controllers and the import
        // path already use.
        String loggedName = fileName == null ? "(name not kept)" : LogSanitizer.sanitize(fileName);
        String loggedDetail = LogSanitizer.sanitize(result.detail());
        switch (result.status()) {
            case CLEAN -> log.debug("Upload {} ({} bytes, {}) scanned clean by {}",
                    loggedName, size, context, active.describe());
            case INFECTED -> {
                log.warn("Upload {} ({} bytes, {}) rejected by {}: {}",
                        loggedName, size, context, active.describe(), loggedDetail);
                // "actorId" alongside the subject, as every admin-reachable audit write does (FG-025):
                // here they are the same person, since nobody uploads a file on someone else's
                // behalf -- an admin's analysis upload is the admin's own act. The anonymous path
                // has neither a user nor a name to record.
                Map<String, Object> metadata = new java.util.LinkedHashMap<>();
                if (fileName != null) metadata.put("fileName", fileName);
                metadata.put("sizeBytes", size);
                metadata.put("context", context);
                metadata.put("signature", result.detail());
                if (userId != null) metadata.put("actorId", String.valueOf(userId));
                auditService.record(userId, AUDIT_ACTION_REJECTED, "Upload", userId, metadata);
                throw new ApiException(ErrorCode.UPLOAD_MALWARE_DETECTED);
            }
            case UNAVAILABLE -> {
                if (rejectWhenUnavailable) {
                    log.error("Malware scanner unavailable ({}) after {} attempt(s) over {} ms; refusing upload {} ({}) "
                            + "per app.malware-scan.on-unavailable=reject: {}",
                            active.describe(), attempt, waitedMs, loggedName, context, loggedDetail);
                    throw new ApiException(ErrorCode.UPLOAD_SCANNER_UNAVAILABLE);
                }
                log.warn("Malware scanner unavailable ({}) after {} attempt(s) over {} ms; upload {} ({}) allowed "
                        + "through UNSCANNED per app.malware-scan.on-unavailable=allow: {}",
                        active.describe(), attempt, waitedMs, loggedName, context, loggedDetail);
            }
        }
    }
}
