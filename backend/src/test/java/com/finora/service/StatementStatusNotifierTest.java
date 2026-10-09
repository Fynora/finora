package com.finora.service;

import com.finora.entity.ImportJob;
import com.finora.exception.ErrorCode;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class StatementStatusNotifierTest {

    private NotificationService notificationService;
    private StatementStatusNotifier notifier;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        notifier = new StatementStatusNotifier(notificationService);
    }

    private ImportJob job() {
        return new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
    }

    private NotificationRequest captureRequest() {
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        return captor.getValue();
    }

    @Test
    void notifyReady_requestsBothPushAndEmailThroughTheOutbox() {
        ImportJob job = job();

        notifier.notifyReady(job, "HDFC Bank");

        NotificationRequest sent = captureRequest();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_READY);
        assertThat(sent.category()).isEqualTo(NotificationCategory.FINANCIAL);
        assertThat(sent.userId()).isEqualTo(job.getUserId());
        assertThat(sent.channels())
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
        assertThat(sent.notificationKey()).isEqualTo("IMPORT_READY_" + job.getId());
    }

    @Test
    void notifyReady_carriesBankAndJobIdInParamsForTheEmailProviderToRecover() {
        ImportJob job = job();

        notifier.notifyReady(job, "HDFC Bank");

        NotificationRequest sent = captureRequest();
        assertThat(sent.params()).containsEntry("bank", "HDFC Bank");
        assertThat(sent.params()).containsEntry("jobId", job.getId().toString());
    }

    @Test
    void notifyHeld_requestsBothPushAndEmailThroughTheOutbox() {
        ImportJob job = job();

        notifier.notifyHeld(job);

        NotificationRequest sent = captureRequest();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_HELD);
        assertThat(sent.channels())
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
        assertThat(sent.notificationKey()).isEqualTo("IMPORT_HELD_" + job.getId());
    }

    @Test
    void notifyHeld_reusesTheSameKeyOnARepeatHoldSoTheOutboxAbsorbsTheDuplicate() {
        // No in-process guard here on purpose -- NotificationRepository.insertIfAbsent's ON
        // CONFLICT DO NOTHING is what actually absorbs a repeat with the identical key. This test
        // proves the precondition that guarantee depends on: the key must not vary per call.
        ImportJob job = job();

        notifier.notifyHeld(job);
        notifier.notifyHeld(job);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, times(2)).request(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationRequest::notificationKey)
                .containsExactly("IMPORT_HELD_" + job.getId(), "IMPORT_HELD_" + job.getId());
    }

    /** The "no" answer to the held email's promise, keyed on the job so a rejection after a reopen
     *  does not tell the user twice. */
    @Test
    void notifyRejected_requestsBothPushAndEmailKeyedOnTheJob() {
        ImportJob job = job();

        notifier.notifyRejected(job);

        NotificationRequest sent = captureRequest();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_REJECTED);
        assertThat(sent.category()).isEqualTo(NotificationCategory.FINANCIAL);
        assertThat(sent.userId()).isEqualTo(job.getUserId());
        assertThat(sent.channels())
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
        assertThat(sent.notificationKey()).isEqualTo("IMPORT_REJECTED_" + job.getId());
    }

    @Test
    void notifyResolved_requestsBothPushAndEmailCarryingTheAdminsMessage() {
        ImportJob job = job();

        notifier.notifyResolved(job, "Please download the statement again from your bank.");

        NotificationRequest sent = captureRequest();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_RESOLVED);
        assertThat(sent.category()).isEqualTo(NotificationCategory.FINANCIAL);
        assertThat(sent.userId()).isEqualTo(job.getUserId());
        assertThat(sent.channels())
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
        assertThat(sent.params()).containsEntry("message", "Please download the statement again from your bank.");
        assertThat(sent.params()).containsEntry("jobId", job.getId().toString());
    }

    /** Keyed on the job, so a double click or a retried request cannot send the user two emails. */
    @Test
    void notifyResolved_isKeyedOnTheJobSoItCannotSendTwice() {
        ImportJob job = job();

        notifier.notifyResolved(job, "msg");

        assertThat(captureRequest().notificationKey()).isEqualTo("IMPORT_RESOLVED_" + job.getId());
    }
    private ImportJob failedWith(String failureCode) {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.recordFailure("ApiException: refused", failureCode, ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        return job;
    }

    /** Rides the resolve template ("An update on your statement" / {{message}}), so the push lands
     *  on the screen every other statement-status message opens. */
    @Test
    void notifyFailedAfterHold_requestsBothPushAndEmailUnderItsOwnJobKey() {
        ImportJob job = failedWith(ErrorCode.IMPORT_CORRUPT_PDF.name());

        notifier.notifyFailedAfterHold(job);

        NotificationRequest sent = captureRequest();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_RESOLVED);
        assertThat(sent.category()).isEqualTo(NotificationCategory.FINANCIAL);
        assertThat(sent.userId()).isEqualTo(job.getUserId());
        assertThat(sent.channels())
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
        assertThat(sent.notificationKey()).isEqualTo("IMPORT_FAILED_" + job.getId());
        assertThat(sent.params()).containsEntry("jobId", job.getId().toString());
    }

    @Test
    void notifyFailedAfterHold_carriesTheFailureCodesOwnCuratedMessage() {
        ImportJob job = failedWith(ErrorCode.IMPORT_PAYMENT_APP_HISTORY.name());

        notifier.notifyFailedAfterHold(job);

        assertThat(captureRequest().params().get("message"))
                .isEqualTo("We've finished checking the statement you uploaded, but we couldn't import it. "
                        + ErrorCode.IMPORT_PAYMENT_APP_HISTORY.defaultMessage()
                        + " Nothing was added to your accounts.");
    }

    /** Several curated messages end without a full stop; the sentence after them must not run on. */
    @Test
    void failedAfterHoldMessage_closesACuratedMessageThatHasNoFullStop() {
        assertThat(ErrorCode.IMPORT_CORRUPT_PDF.defaultMessage()).doesNotEndWith(".");

        assertThat(StatementStatusNotifier.failedAfterHoldMessage(ErrorCode.IMPORT_CORRUPT_PDF.name()))
                .contains(ErrorCode.IMPORT_CORRUPT_PDF.defaultMessage() + ". Nothing was added");
    }

    /** A stored exception class name is for engineers and has no curated copy; neither does no code. */
    @Test
    void failedAfterHoldMessage_fallsBackToAGenericSentenceWithoutACuratedCode() {
        for (String code : new String[] {"StatementIntegrityException", null,
                // A real ErrorCode, but not about the statement: "Unexpected error" is no reason.
                ErrorCode.INTERNAL_ERROR.name(),
                // "We'll let you know when it's ready" would contradict "we couldn't import it".
                ErrorCode.IMPORT_SESSION_HELD_FOR_REVIEW.name()}) {
            String message = StatementStatusNotifier.failedAfterHoldMessage(code);
            assertThat(message).startsWith("We've finished checking the statement you uploaded");
            assertThat(message).contains("Please upload it again");
            assertThat(message).doesNotContain("Exception").doesNotContain("null")
                    .doesNotContain("Unexpected error").doesNotContain("let you know");
        }
    }

    /**
     * The password codes' own messages ask the user to "Enter the password" into a prompt that an
     * email or a push does not have; a held job reaches them when the user removes every saved
     * password (that removal includes a held job's).
     */
    @Test
    void failedAfterHoldMessage_tellsAPasswordFailureToChooseTheFileAgain() {
        for (ErrorCode code : new ErrorCode[] {
                ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED, ErrorCode.IMPORT_PDF_PASSWORD_INVALID}) {
            String message = StatementStatusNotifier.failedAfterHoldMessage(code.name());
            assertThat(message).doesNotContain(code.defaultMessage());
            assertThat(message).contains("Choose it again and enter the password your bank uses for it.");
            assertThat(message).endsWith(" Nothing was added to your accounts.");
        }
    }

    @Test
    void notifyFailedAfterHold_isKeyedOnTheJobSoItCannotSendTwice() {
        ImportJob job = failedWith(ErrorCode.IMPORT_CORRUPT_PDF.name());

        notifier.notifyFailedAfterHold(job);
        notifier.notifyFailedAfterHold(job);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, times(2)).request(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationRequest::notificationKey)
                .containsOnly("IMPORT_FAILED_" + job.getId());
    }
}
