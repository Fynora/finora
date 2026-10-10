package com.finora.imports.analysis;

import com.finora.config.CorrelationIdFilter;
import com.finora.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one way a controller records that it turned an upload away (Gate 1 spec §5.1) -- and the
 * guarantee that doing so can never change what the user is told.
 *
 * <p>{@link StatementAnalysisRecorder} swallows what goes wrong inside its methods, but each of
 * them opens a transaction of its own, and that happens in the proxy AROUND the method: no
 * connection to be had, or a commit that fails, throws from outside the method's own try/catch.
 * A caller that records a refusal and then rethrows it would have that exception replace the
 * refusal, and the user would get a 500 where they were owed "that file is empty". Catching here,
 * once, is what makes "recording never changes the outcome" true for every caller rather than a
 * rule each one has to remember.
 *
 * <p>Deliberately not transactional and not asynchronous itself, so nothing can throw around these
 * methods either.
 */
@Component
public class UploadRefusalLog {

    private static final Logger log = LoggerFactory.getLogger(UploadRefusalLog.class);

    private final StatementAnalysisRecorder recorder;

    public UploadRefusalLog(StatementAnalysisRecorder recorder) {
        this.recorder = recorder;
    }

    /** A refusal thrown by one of the upload gates. The code is read from the exception. */
    public void refused(UUID userId, String sourceFormat, Throwable refusal) {
        refused(userId, sourceFormat, StatementAnalysisRecorder.rejectionCodeOf(refusal));
    }

    /** A refusal that has no exception of the app's own to read a code from. */
    public void refused(UUID userId, String sourceFormat, String refusalCode) {
        try {
            recorder.recordRejected(userId, sourceFormat, refusalCode);
        } catch (RuntimeException e) {
            log.warn("Could not record a refused upload ({}); the refusal itself is unaffected", refusalCode, e);
        }
    }

    /**
     * Turned away because the server was busy. Written off the request thread: this refusal is
     * the import limiter shedding load and must stay instant, so it may not wait on the database --
     * see {@code BackgroundWorkConfig.uploadRefusalRecordExecutor}. Under real overload the record
     * may be dropped; the refusal never is.
     */
    public void refusedAsBusy(UUID userId, String sourceFormat) {
        try {
            recorder.recordRejectedOffRequest(userId, sourceFormat, ErrorCode.IMPORT_SYSTEM_BUSY.name(),
                    MDC.get(CorrelationIdFilter.MDC_KEY));
        } catch (RuntimeException e) {
            log.warn("Could not queue the record of an upload refused as busy; the refusal itself is unaffected", e);
        }
    }
}
