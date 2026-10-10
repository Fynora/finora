package com.finora.imports;

import com.finora.dto.ApiResponse;
import com.finora.exception.GlobalExceptionHandler;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Records a statement upload refused for its size (Gate 1 spec §5.1), then answers exactly as
 * before.
 *
 * <p>The size limit is the servlet container's, enforced while the multipart body is read --
 * before {@code ImportController} or {@code ImportJobController} runs, so the try/catch those use
 * for their own refusals never sees it. Neither client checks a file's size before sending it, so
 * this is a real way a user is turned away, and it left no record.
 *
 * <p>An advice of its own, ahead of {@link GlobalExceptionHandler}, rather than a change to that
 * class: the response is produced by delegating to its {@code handleUploadTooLarge}, so the 413
 * and its wording cannot drift, and the handler every other error goes through gains no
 * dependency on the import pipeline. Only the three statement upload endpoints are recorded;
 * every other multipart endpoint (support attachments, screenshots, admin analysis) passes
 * straight through.
 *
 * <p>Recording never changes the outcome: a missing user or a failed write is swallowed, and the
 * client still gets its 413.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OversizedImportUploadAdvice {

    private static final Logger log = LoggerFactory.getLogger(OversizedImportUploadAdvice.class);

    /** The wire code {@link GlobalExceptionHandler#handleUploadTooLarge} answers with. */
    static final String REFUSAL_CODE = "UPLOAD_TOO_LARGE";

    private final GlobalExceptionHandler delegate;
    private final StatementAnalysisRecorder analysisRecorder;
    private final CurrentUser currentUser;

    public OversizedImportUploadAdvice(GlobalExceptionHandler delegate, StatementAnalysisRecorder analysisRecorder,
                                       CurrentUser currentUser) {
        this.delegate = delegate;
        this.analysisRecorder = analysisRecorder;
        this.currentUser = currentUser;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                                                  HttpServletRequest request) {
        try {
            String path = request.getRequestURI();
            if ("POST".equalsIgnoreCase(request.getMethod()) && isStatementUpload(path)) {
                analysisRecorder.recordRejected(currentUser.id(), formatOf(path), REFUSAL_CODE);
            }
        } catch (RuntimeException e) {
            // No authenticated user, or the write failed: evidence lost, answer unchanged.
            log.warn("Could not record an oversized statement upload as refused", e);
        }
        return delegate.handleUploadTooLarge(ex);
    }

    private static boolean isStatementUpload(String path) {
        return path != null && (path.endsWith("/api/v1/import/csv/stage")
                || path.endsWith("/api/v1/import/pdf/stage")
                || path.endsWith("/api/v1/import/jobs"));
    }

    /** The queue takes either format and decides from the file name, which was never read: null. */
    private static String formatOf(String path) {
        if (path.endsWith("/csv/stage")) return StatementUpload.Format.CSV.name();
        if (path.endsWith("/pdf/stage")) return StatementUpload.Format.PDF.name();
        return null;
    }
}
