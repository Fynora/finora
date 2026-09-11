package com.finora.service;

import com.finora.entity.ImportJob;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
}
