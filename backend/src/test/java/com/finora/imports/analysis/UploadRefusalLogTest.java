package com.finora.imports.analysis;

import com.finora.config.CorrelationIdFilter;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Recording a refusal can never change the refusal.
 *
 * <p>The recorder opens a transaction of its own around each method, so what goes wrong there --
 * no connection to be had, a commit that fails -- is thrown from outside the method's own
 * try/catch. A controller records and then rethrows the refusal; without this class catching, that
 * exception would replace it and the user would get a 500 instead of the reason they were owed.
 */
class UploadRefusalLogTest {

    private final StatementAnalysisRecorder recorder = mock(StatementAnalysisRecorder.class);
    private final UploadRefusalLog refusals = new UploadRefusalLog(recorder);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aRecorderThatCannotOpenItsTransactionNeverReachesTheCaller() {
        doThrow(new CannotCreateTransactionException("no connection available"))
                .when(recorder).recordRejected(any(), any(), anyString());

        assertThatCode(() -> refusals.refused(userId, "CSV", new ApiException(HttpStatus.BAD_REQUEST, "empty")))
                .doesNotThrowAnyException();
        assertThatCode(() -> refusals.refused(userId, null, "UPLOAD_TOO_LARGE")).doesNotThrowAnyException();
    }

    @Test
    void aBusyRefusalThatCannotBeQueuedNeverReachesTheCaller() {
        doThrow(new org.springframework.core.task.TaskRejectedException("executor shut down"))
                .when(recorder).recordRejectedOffRequest(any(), any(), any(), any());

        assertThatCode(() -> refusals.refusedAsBusy(userId, "PDF")).doesNotThrowAnyException();
    }

    @Test
    void aRefusalIsRecordedByTheCodeItsExceptionCarries() {
        refusals.refused(userId, "CSV", new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "not a CSV"));
        refusals.refused(userId, "PDF", new ApiException(ErrorCode.UPLOAD_MALWARE_DETECTED, "flagged"));

        verify(recorder).recordRejected(userId, "CSV", "HTTP_415");
        verify(recorder).recordRejected(userId, "PDF", "UPLOAD_MALWARE_DETECTED");
    }

    /**
     * Busy is the server shedding load: the record is handed to another thread, never written on
     * this one, and carries the request's correlation id because MDC does not follow it there.
     */
    @Test
    void aBusyRefusalIsHandedOffWithTheRequestsCorrelationId_neverWrittenOnTheRequestThread() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "request-abc123");

        refusals.refusedAsBusy(userId, "PDF");

        verify(recorder).recordRejectedOffRequest(userId, "PDF", "IMPORT_SYSTEM_BUSY", "request-abc123");
        verify(recorder, never()).recordRejected(any(), any(), anyString());
    }
}
