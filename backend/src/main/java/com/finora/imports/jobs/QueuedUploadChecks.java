package com.finora.imports.jobs;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.StatementUpload;
import com.finora.imports.passwords.StatementPasswordService;
import com.finora.imports.pdf.PdfTextExtractor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * The checks an upload passes before it may be queued, shared by the signed-in upload
 * ({@code ImportJobController}) and the public send-from-a-computer upload ({@code PublicSendController}).
 *
 * <p>The malware scan is NOT here, on purpose: {@code UploadScanGuardTest} (FG-034) requires every
 * controller method that takes a file to call {@code UploadScanGate.requireClean} itself, so each
 * caller runs {@link #requireReadable}, then the scan, then {@link #passwordToSave}, inside its own
 * try so a refusal at any of the three is recorded by {@code UploadRefusalLog}.
 */
@Component
public class QueuedUploadChecks {

    private final StatementPasswordService statementPasswordService;

    public QueuedUploadChecks(StatementPasswordService statementPasswordService) {
        this.statementPasswordService = statementPasswordService;
    }

    /** Empty files and files whose contents don't match their name, refused before anything else. */
    public void requireReadable(MultipartFile file, StatementUpload.Format format) {
        StatementUpload.requireReadable(file, format);
    }

    /**
     * For a PDF: whether it needs a password, and if so whether it may be queued. A protected PDF
     * cannot be queued without its password: the job carries a content address and no password, so
     * the worker would open it minutes later with nobody to ask and fail with a bare "couldn't
     * finish". Refused with the same IMPORT_008 the synchronous endpoints return, which both apps
     * answer by opening the password field on the same file. Production, 2026-09-19: a blank
     * password field on a locked statement reached the queue and died there.
     *
     * <p>Statement refresh, step 4: a locked PDF IS queued when the user agreed to let Fynora keep
     * its password ({@code savePassword} with the {@code password}). The password is checked
     * against the file and returned, to be saved encrypted against the job.
     *
     * @return the password to save with the job, or null (not a PDF, not locked, or no consent;
     *     an unlocked file needs no password, so one sent with it is never kept)
     */
    public String passwordToSave(MultipartFile file, StatementUpload.Format format, Boolean savePassword,
                                 String password) throws IOException {
        if (format != StatementUpload.Format.PDF) return null;
        boolean locked;
        try (InputStream in = file.getInputStream()) {
            locked = PdfTextExtractor.needsPassword(in);
        }
        if (!locked) return null;
        boolean consented = Boolean.TRUE.equals(savePassword) && password != null && !password.isEmpty()
                && statementPasswordService.enabled();
        if (!consented) {
            throw new ApiException(ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED);
        }
        try (InputStream in = file.getInputStream()) {
            if (!PdfTextExtractor.opensWith(in, password)) {
                throw new ApiException(ErrorCode.IMPORT_PDF_PASSWORD_INVALID);
            }
        }
        return password;
    }
}
