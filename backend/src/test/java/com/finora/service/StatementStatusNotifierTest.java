package com.finora.service;

import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StatementStatusNotifierTest {

    private NotificationService notificationService;
    private UserRepository userRepository;
    private ImportJobRepository importJobRepository;
    private EmailProvider emailProvider;
    private AuditService auditService;
    private StatementStatusNotifier notifier;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        userRepository = mock(UserRepository.class);
        importJobRepository = mock(ImportJobRepository.class);
        emailProvider = mock(EmailProvider.class);
        auditService = mock(AuditService.class);
        notifier = new StatementStatusNotifier(notificationService, userRepository,
                importJobRepository, emailProvider, auditService);

        when(importJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ImportJob job() {
        return new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
    }

    private User userWithEmail(String email) {
        User user = mock(User.class);
        when(user.getEmail()).thenReturn(email);
        when(user.isDeleted()).thenReturn(false);
        return user;
    }

    @Test
    void notifyReady_requestsPushOnly() {
        ImportJob job = job();
        User user = userWithEmail("user@example.test");
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(user));
        when(emailProvider.sendStatementReadyEmail(any(), any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        notifier.notifyReady(job, "HDFC Bank");

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        NotificationRequest sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_READY);
        assertThat(sent.channels()).containsExactly(NotificationChannel.PUSH);
        assertThat(sent.notificationKey()).isEqualTo("IMPORT_READY_" + job.getId());
        assertThat(sent.params()).containsEntry("bank", "HDFC Bank");
    }

    @Test
    void notifyReady_sendsTheBrandedEmailToTheUsersAddress() {
        ImportJob job = job();
        User user = userWithEmail("user@example.test");
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(user));
        when(emailProvider.sendStatementReadyEmail("user@example.test", "HDFC Bank", job.getId().toString()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        notifier.notifyReady(job, "HDFC Bank");

        verify(emailProvider).sendStatementReadyEmail("user@example.test", "HDFC Bank", job.getId().toString());
    }

    @Test
    void notifyReady_skipsTheEmailWhenTheUserHasNoAddressOnFile() {
        ImportJob job = job();
        User user = userWithEmail("");
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(user));

        notifier.notifyReady(job, "HDFC Bank");

        verify(emailProvider, never()).sendStatementReadyEmail(any(), any(), any());
    }

    @Test
    void notifyHeld_requestsPushOnly() {
        ImportJob job = job();
        User user = userWithEmail("user@example.test");
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(user));
        when(emailProvider.sendStatementHeldEmail(any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        notifier.notifyHeld(job);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        NotificationRequest sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_HELD);
        assertThat(sent.channels()).containsExactly(NotificationChannel.PUSH);
        assertThat(sent.notificationKey()).isEqualTo("IMPORT_HELD_" + job.getId());
    }

    @Test
    void notifyHeld_sendsTheEmailTheFirstTime() {
        ImportJob job = job();
        User user = userWithEmail("user@example.test");
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(user));
        when(emailProvider.sendStatementHeldEmail(any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        notifier.notifyHeld(job);

        verify(emailProvider).sendStatementHeldEmail("user@example.test");
        verify(importJobRepository).save(job);
    }

    @Test
    void notifyHeld_doesNotSendTheEmailASecondTimeForTheSameJob() {
        ImportJob job = job();
        job.markStatementHeldEmailSent(Instant.now());
        User user = userWithEmail("user@example.test");
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(user));

        notifier.notifyHeld(job);

        verify(emailProvider, never()).sendStatementHeldEmail(any());
    }

    @Test
    void notifyHeld_stillRequestsThePushOnARepeatHold() {
        // PUSH keeps its own outbox-level dedup (notification_key) -- this notifier must still
        // ask for it every time; the outbox, not this class, is what absorbs the duplicate.
        ImportJob job = job();
        job.markStatementHeldEmailSent(Instant.now());
        User user = userWithEmail("user@example.test");
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(user));

        notifier.notifyHeld(job);

        verify(notificationService).request(any());
    }
}
