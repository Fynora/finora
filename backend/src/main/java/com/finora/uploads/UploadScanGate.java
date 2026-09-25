package com.finora.uploads;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.StatementUpload;
import com.finora.service.AuditService;
import com.finora.uploads.MalwareScanner.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

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

    private final ObjectProvider<MalwareScanner> scanner;
    private final AuditService auditService;
    private final boolean rejectWhenUnavailable;

    public UploadScanGate(ObjectProvider<MalwareScanner> scanner, AuditService auditService,
                          @Value("${app.malware-scan.on-unavailable:reject}") String onUnavailable) {
        this.scanner = scanner;
        this.auditService = auditService;
        this.rejectWhenUnavailable = !"allow".equalsIgnoreCase(onUnavailable);
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
        MalwareScanner active = scanner.getIfAvailable();
        if (active == null) {
            return;
        }

        ScanResult result;
        try (InputStream in = file.getInputStream()) {
            result = active.scan(in, file.getSize());
        } catch (IOException e) {
            result = ScanResult.unavailable("could not read the upload for scanning: " + e);
        }

        String fileName = StatementUpload.safeFileName(file, "upload");
        switch (result.status()) {
            case CLEAN -> log.debug("Upload {} ({} bytes, {}) scanned clean by {}",
                    fileName, file.getSize(), context, active.describe());
            case INFECTED -> {
                log.warn("Upload {} ({} bytes, {}) rejected by {}: {}",
                        fileName, file.getSize(), context, active.describe(), result.detail());
                // "actorId" alongside the subject, as every admin-reachable audit write does (FG-025):
                // here they are the same person, since nobody uploads a file on someone else's
                // behalf -- an admin's analysis upload is the admin's own act.
                auditService.record(userId, AUDIT_ACTION_REJECTED, "Upload", userId, Map.of(
                        "fileName", fileName,
                        "sizeBytes", file.getSize(),
                        "context", context,
                        "signature", result.detail(),
                        "actorId", String.valueOf(userId)));
                throw new ApiException(ErrorCode.UPLOAD_MALWARE_DETECTED);
            }
            case UNAVAILABLE -> {
                if (rejectWhenUnavailable) {
                    log.error("Malware scanner unavailable ({}); refusing upload {} ({}) per "
                            + "app.malware-scan.on-unavailable=reject: {}",
                            active.describe(), fileName, context, result.detail());
                    throw new ApiException(ErrorCode.UPLOAD_SCANNER_UNAVAILABLE);
                }
                log.warn("Malware scanner unavailable ({}); upload {} ({}) allowed through UNSCANNED per "
                        + "app.malware-scan.on-unavailable=allow: {}",
                        active.describe(), fileName, context, result.detail());
            }
        }
    }
}
