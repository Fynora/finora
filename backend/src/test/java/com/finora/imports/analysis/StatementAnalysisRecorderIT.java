package com.finora.imports.analysis;

import com.finora.AbstractIntegrationTest;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The property this whole table depends on: a failure is still recorded when the operation that
 * discovered it rolls back.
 *
 * <p>Not a theoretical concern. Parse failures are reported by throwing {@link ApiException}, a
 * RuntimeException, which marks the caller's transaction rollback-only — so a naive insert would
 * produce a table that captures every success and silently loses every failure. That is precisely
 * inverted from the reason it exists.
 *
 * <p>The same shape had already caused a real security bug here: reuse detection wrote an
 * account-wide revocation, threw to reject the request, and the revocation was rolled back while
 * the API reported "all sessions have been signed out". Three unit tests covered that path and all
 * three passed, because they mocked the repository — {@code save} was called, {@code verify()}
 * succeeded, and no transaction existed to undo it. So this test uses a real database and a real
 * rollback, because a mock physically cannot observe the failure mode.
 */
@org.springframework.context.annotation.Import(StatementAnalysisRecorderIT.RollbackHarness.class)
class StatementAnalysisRecorderIT extends AbstractIntegrationTest {

    @Autowired private StatementAnalysisRecorder recorder;
    @Autowired private StatementAnalysisSessionRepository repository;
    @Autowired private RollbackHarness harness;

    /**
     * A caller that records evidence and then fails, which is the exact shape of every parse
     * failure in {@code ImportService}: record, then throw.
     */
    static class RollbackHarness {
        private final StatementAnalysisRecorder recorder;

        RollbackHarness(StatementAnalysisRecorder recorder) {
            this.recorder = recorder;
        }

        @Transactional
        public String recordThenFail(UUID userId) {
            String reference = recorder.recordFailed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                    "hdfc-savings.pdf", "PDF", 4096L, "FP-TEST-1A9E", "IMPORT_001",
                    "No transaction table found", 120L,
                    ParseDiagnostics.of(0, java.util.Map.of("NO_DATE_IN_ANCHOR_COLUMN", 97)));
            throw new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED, "No transaction table found");
        }

        /** The overload {@code ImportService} actually calls -- the one that names the file. */
        @Transactional
        public String recordWithContentHashThenFail(UUID userId) {
            recorder.recordFailed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                    "hdfc-savings.pdf", "PDF", 4096L, "FP-TEST-HASH", "IMPORT_001",
                    "No transaction table found", 120L, ParseDiagnostics.NONE, "a".repeat(64));
            throw new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED, "No transaction table found");
        }

        /** A refusal before reading, recorded and then rethrown inside a transaction. */
        @Transactional
        public String recordRejectedThenFail(UUID userId, ApiException refusal) {
            recorder.recordRejected(userId, "PDF", refusal);
            throw refusal;
        }
    }

    private java.util.List<StatementAnalysisSession> rowsOf(UUID userId) {
        return repository.findAll().stream().filter(s -> userId.equals(s.getUserId())).toList();
    }

    /**
     * The overload that carries the content hash had no transaction of its own from V265 until
     * V267's work, so a caller inside a transaction lost the row with its rollback -- the exact
     * failure this class exists to prevent. Its only caller happened to run outside one.
     */
    @Test
    void aFailureRecordedWithItsContentHashAlsoSurvivesTheRollback() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> harness.recordWithContentHashThenFail(userId)).isInstanceOf(ApiException.class);

        assertThat(rowsOf(userId)).singleElement()
                .satisfies(row -> assertThat(row.getContentHash()).isEqualTo("a".repeat(64)));
    }

    // --- Gate 1 spec §5.1: uploads refused before reading ------------------------------------------

    @Test
    void aRefusedUploadIsRecordedAndSurvivesTheRollbackOfTheRequestThatRefusedIt() {
        UUID userId = UUID.randomUUID();
        ApiException refusal = new ApiException(ErrorCode.UPLOAD_SCANNER_UNAVAILABLE, "scanner down");

        assertThatThrownBy(() -> harness.recordRejectedThenFail(userId, refusal)).isSameAs(refusal);

        assertThat(rowsOf(userId)).singleElement().satisfies(row -> {
            assertThat(row.getOutcome()).isEqualTo(StatementAnalysisSession.Outcome.REJECTED);
            assertThat(row.getSource()).isEqualTo(StatementAnalysisSession.Source.CUSTOMER_IMPORT);
            assertThat(row.getFailureCode()).isEqualTo("UPLOAD_SCANNER_UNAVAILABLE");
            assertThat(row.getSourceFormat()).isEqualTo("PDF");
            assertThat(row.getReference()).startsWith("SA-");
            // The file never entered the pipeline: nothing about it is recorded.
            assertThat(row.getFileName()).isNull();
            assertThat(row.getByteSize()).isNull();
            assertThat(row.getContentHash()).isNull();
            assertThat(row.getLayoutFingerprint()).isNull();
            assertThat(row.getFailureDetail()).isNull();
            assertThat(row.getImportSessionId()).isNull();
            assertThat(row.getExpiredUnconfirmedAt()).isNull();
        });
    }

    /** {@code StatementUpload} refuses with a bare status and no ErrorCode. */
    @Test
    void aRefusalWithNoErrorCodeIsRecordedByItsHttpStatus() {
        UUID userId = UUID.randomUUID();

        recorder.recordRejected(userId, "CSV",
                new ApiException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload a CSV file."));

        assertThat(rowsOf(userId)).singleElement()
                .satisfies(row -> assertThat(row.getFailureCode()).isEqualTo("HTTP_415"));
    }

    @Test
    void rejectionCodes_fallBackToUnclassifiedAndNeverExceedTheColumn() {
        assertThat(StatementAnalysisRecorder.rejectionCodeOf(new IllegalStateException("boom")))
                .isEqualTo("UNCLASSIFIED");
        assertThat(StatementAnalysisRecorder.rejectionCodeOf(null)).isEqualTo("UNCLASSIFIED");
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(StatementAnalysisRecorder.rejectionCodeOf(new ApiException(code, "x")))
                    .hasSizeLessThanOrEqualTo(32);
        }
    }

    /**
     * A refusal is not a parse failure. It has no file name and no content hash, so in the
     * customer's failure list it would be a nameless row that the "same file later imported" rule
     * (V265) could never clear -- and the user was already told at the moment of the refusal.
     */
    @Test
    void aRefusedUploadNeverAppearsInAFailureList() {
        UUID userId = UUID.randomUUID();
        recorder.recordRejected(userId, "PDF", new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY, "busy"));

        assertThat(recorder.recentCustomerFailures(userId, 10)).isEmpty();
        assertThat(recorder.recentUnresolvedCustomerFailures(userId, 10)).isEmpty();
    }

    @Test
    void aFailureSurvivesTheRollbackOfTheOperationThatReportedIt() {
        UUID userId = UUID.randomUUID();
        long before = repository.count();

        assertThatThrownBy(() -> harness.recordThenFail(userId)).isInstanceOf(ApiException.class);

        // The witness is the row's continued existence AFTER the enclosing transaction rolled back.
        // Asserting only that recordFailed returned a reference would pass against a plain
        // @Transactional recorder whose insert is then discarded -- the observation would be true
        // for two different reasons, and only one of them is the property under test.
        assertThat(repository.count())
                .as("the evidence row must outlive the transaction that threw; without "
                    + "REQUIRES_NEW every failed parse is silently unrecorded")
                .isEqualTo(before + 1);

        var recorded = repository.findAll().stream()
                .filter(s -> userId.equals(s.getUserId()))
                .findFirst()
                .orElseThrow();
        assertThat(recorded.getOutcome()).isEqualTo(StatementAnalysisSession.Outcome.FAILED);
        assertThat(recorded.getFailureCode()).isEqualTo("IMPORT_001");
        assertThat(recorded.getLayoutFingerprint()).isEqualTo("FP-TEST-1A9E");

        // The diagnostics have to survive the same rollback, and this is the case that needs them
        // most: "no transaction table found" says the document was rejected, and the histogram is
        // the only field on the row that says why. A failure recorded without it is a failure
        // nobody can act on.
        assertThat(recorded.getUnanchoredReasonsJson())
                .as("the reason histogram must outlive the rollback alongside the rest of the row")
                .contains("NO_DATE_IN_ANCHOR_COLUMN").contains("97");
        assertThat(recorded.getRowCount()).isZero();
    }

    @Test
    void theReasonHistogramIsStoredDominantReasonFirst() {
        // Stored ordered so the same parse run always serialises identically and the reason worth
        // acting on reads first. Order is NOT free here: DocumentContext hands back a Map.copyOf,
        // whose iteration order is unspecified, so without the explicit sort the same document
        // could persist two different strings on two runs.
        String reference = recorder.recordParsed(UUID.randomUUID(), StatementAnalysisSession.Source.ADMIN_ANALYSIS,
                "ordered.pdf", "PDF", 1L, "FP-ORDER", 1, 1L,
                ParseDiagnostics.of(569, new java.util.LinkedHashMap<>(java.util.Map.of(
                        "UNANCHORED_ROWS_ABANDONED", 12,
                        "NO_DATE_IN_ANCHOR_COLUMN", 649,
                        "AMOUNT_CELL_UNPARSEABLE", 88))));

        var stored = repository.findByReference(reference).orElseThrow();
        assertThat(stored.getUnanchoredReasonsJson()).isEqualTo(
                "{\"NO_DATE_IN_ANCHOR_COLUMN\":649,\"AMOUNT_CELL_UNPARSEABLE\":88,\"UNANCHORED_ROWS_ABANDONED\":12}");
        assertThat(stored.getRowCount()).isEqualTo(569);
    }

    @Test
    void aDocumentWhereEveryRowAnchoredStoresNoHistogramRatherThanAnEmptyOne() {
        // "Nothing failed" should read as absence, not as a document carrying an empty finding --
        // otherwise every healthy import leaves a "{}" that a reader has to interpret.
        String reference = recorder.recordParsed(UUID.randomUUID(), StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "clean.csv", "CSV", 1L, "FP-CLEAN", 1, 1L, ParseDiagnostics.of(140, java.util.Map.of()));

        var stored = repository.findByReference(reference).orElseThrow();
        assertThat(stored.getUnanchoredReasonsJson()).isNull();
        assertThat(stored.getRowCount()).isEqualTo(140);
    }

    @Test
    void aRowCountOfZeroIsDistinguishableFromNeverHavingMeasured() {
        // The distinction the dashboard depends on. "Read the document, extracted nothing" and
        // "never got far enough to extract" look identical if both store null, and they call for
        // completely different investigations -- one is a parser capability, the other is a file
        // that could not be opened at all.
        String measured = recorder.recordFailed(UUID.randomUUID(), StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "empty.pdf", "PDF", 1L, "FP-EMPTY", "IMPORT_001", "nothing extracted", 1L,
                ParseDiagnostics.of(0, java.util.Map.of("NO_DATE_IN_ANCHOR_COLUMN", 4)));
        String neverMeasured = recorder.recordFailed(UUID.randomUUID(), StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "locked.pdf", "PDF", 1L, null, "IMPORT_008", "wrong password", 1L, ParseDiagnostics.NONE);

        assertThat(repository.findByReference(measured).orElseThrow().getRowCount()).isZero();
        assertThat(repository.findByReference(neverMeasured).orElseThrow().getRowCount()).isNull();
    }

    @Test
    void referencesAreUniqueAndHumanQuotable() {
        String first = recorder.recordParsed(UUID.randomUUID(), StatementAnalysisSession.Source.ADMIN_ANALYSIS,
                "a.pdf", "PDF", 1L, "FP-A", 1, 1L, ParseDiagnostics.NONE);
        String second = recorder.recordParsed(UUID.randomUUID(), StatementAnalysisSession.Source.ADMIN_ANALYSIS,
                "b.pdf", "PDF", 1L, "FP-B", 1, 1L, ParseDiagnostics.NONE);

        // From a database sequence, not a row count: two concurrent uploads counting rows would
        // pick the same number, and the unique constraint would then drop whichever lost the race.
        assertThat(first).isNotEqualTo(second);
        assertThat(first).matches("SA-\\d{8}-\\d{4}");
    }

    @Test
    void recordingNeverBreaksTheUploadWhenItCannotWrite() {
        // failureDetail far beyond the column's tolerance, standing in for any write that goes
        // wrong. A telemetry insert must never be the reason a user's statement import fails --
        // the compensating control is the ERROR log, not a propagated exception.
        String enormous = "x".repeat(50_000);

        String reference = recorder.recordFailed(UUID.randomUUID(), StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "big.pdf", "PDF", 1L, "FP-C", "IMPORT_001", enormous, 1L, ParseDiagnostics.NONE);

        // Either it truncated and stored (a reference), or it failed and returned null. Both are
        // acceptable; throwing is not.
        assertThat(reference == null || reference.startsWith("SA-")).isTrue();
    }

    /**
     * Bug fix, caught by post-commit review rather than by any test at the time this method was
     * added: {@code recentCustomerFailures} used to hand back the entity's raw {@code
     * failureCode}, which is {@code ApiException.getCode().name()} -- the Java enum identifier --
     * not the wire code the frontend's failure-UX contract is keyed by. Every real failure in the
     * customer-facing failures list silently fell through to the contract's generic fallback.
     */
    @Test
    void recentCustomerFailures_returnsTheWireCodeNotTheStoredEnumName() {
        UUID userId = UUID.randomUUID();
        recorder.recordFailed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "no-header.pdf", "PDF", 1L, "FP-WIRE-1",
                ErrorCode.IMPORT_NO_HEADER_DETECTED.name(), "No transaction table found", 1L, ParseDiagnostics.NONE);

        var failures = recorder.recentCustomerFailures(userId, 10);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).failureCode())
                .as("the wire code (\"IMPORT_001\"), not the stored enum name (\"IMPORT_NO_HEADER_DETECTED\")")
                .isEqualTo(ErrorCode.IMPORT_NO_HEADER_DETECTED.code());
    }

    @Test
    void recentCustomerFailures_returnsNullRatherThanThrowingForAnUnrecognizedStoredCode() {
        // ImportService.recordParseFailure falls back to failure.getClass().getSimpleName() for a
        // failure that was never an ApiException in the first place -- not a valid ErrorCode name,
        // and not something a customer response should carry regardless.
        UUID userId = UUID.randomUUID();
        recorder.recordFailed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "crashed.pdf", "PDF", 1L, "FP-WIRE-2",
                "NullPointerException", "boom", 1L, ParseDiagnostics.NONE);

        var failures = recorder.recentCustomerFailures(userId, 10);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).failureCode()).isNull();
    }

    @Test
    void recentCustomerFailures_returnsNullWhenTheStoredCodeIsAlreadyNull() {
        UUID userId = UUID.randomUUID();
        recorder.recordFailed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "no-code.pdf", "PDF", 1L, null, null, "some IOException message", 1L, ParseDiagnostics.NONE);

        var failures = recorder.recentCustomerFailures(userId, 10);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).failureCode()).isNull();
    }

    @Test
    void adminAnalysisAndCustomerImportAreDistinguishable() {
        // Both sources share one pipeline on purpose -- a customer hitting an unknown layout is at
        // least as informative as an admin doing it deliberately -- but the reports have to be able
        // to separate deliberate probing from real usage.
        recorder.recordParsed(UUID.randomUUID(), StatementAnalysisSession.Source.ADMIN_ANALYSIS,
                "probe.pdf", "PDF", 1L, "FP-SHARED", 1, 1L, ParseDiagnostics.NONE);
        recorder.recordParsed(UUID.randomUUID(), StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "real.pdf", "PDF", 1L, "FP-SHARED", 1, 1L, ParseDiagnostics.NONE);

        var bySource = repository.findAll().stream()
                .filter(s -> "FP-SHARED".equals(s.getLayoutFingerprint()))
                .map(StatementAnalysisSession::getSource)
                .toList();
        assertThat(bySource).containsExactlyInAnyOrder(
                StatementAnalysisSession.Source.ADMIN_ANALYSIS,
                StatementAnalysisSession.Source.CUSTOMER_IMPORT);
    }
}
