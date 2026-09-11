package com.finora.service;

import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.util.AfterCommit;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * The single call site for "tell the user their statement's status changed" -- PUSH stays on the
 * existing notification outbox (unaffected by the premium email redesign, 2026-09-11); the email
 * leg is sent directly through {@link EmailProvider}, bypassing {@code notification_templates}
 * entirely (see V195's migration comment for why). Replaces logic that used to be duplicated
 * inline at 4 call sites across {@code ImportJobWorker} and {@code HeldStatementService}.
 *
 * <p>The held-email leg is guarded by {@link ImportJob#markStatementHeldEmailSent} so a job that
 * reprocesses, fails the same way, and re-holds does not get a second "we're checking your
 * statement" email -- see V194's migration comment for why this guarantee needed a new column
 * once the outbox's own idempotency stopped covering the email leg. The ready-email leg needs no
 * equivalent guard: both its call sites are already naturally single-fire (a job reaches
 * {@code COMPLETED} once; {@code HeldStatementService.approve} is gated by
 * {@code refuseIfResolved}), verified by reading both call sites before this class was written.
 */
@Service
public class StatementStatusNotifier {

    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final ImportJobRepository importJobRepository;
    private final EmailProvider emailProvider;
    private final AuditService auditService;

    public StatementStatusNotifier(NotificationService notificationService, UserRepository userRepository,
            ImportJobRepository importJobRepository, EmailProvider emailProvider, AuditService auditService) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.importJobRepository = importJobRepository;
        this.emailProvider = emailProvider;
        this.auditService = auditService;
    }

    /**
     * @param bankName the parser's own detected bank name, or the template's documented "bank"
     *                 fallback when none was detected -- the caller's responsibility, same as it
     *                 was before this class existed.
     */
    public void notifyReady(ImportJob job, String bankName) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_READY_" + job.getId(),
                Set.of(NotificationChannel.PUSH),
                Map.of("bank", bankName)));

        emailForUser(job.getUserId(), "statement_ready",
                user -> emailProvider.sendStatementReadyEmail(user.getEmail(), bankName, job.getId().toString()));
    }

    public void notifyHeld(ImportJob job) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_HELD,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_HELD_" + job.getId(),
                Set.of(NotificationChannel.PUSH),
                Map.of()));

        if (!job.markStatementHeldEmailSent(Instant.now())) {
            return;
        }
        importJobRepository.save(job);

        emailForUser(job.getUserId(), "statement_held",
                user -> emailProvider.sendStatementHeldEmail(user.getEmail()));
    }

    /** Same guard EmailNotificationProvider.send already applies before handing a user to the
     *  real provider: no user row, a purged account, or a blank email address all skip silently
     *  rather than sending to a stale or synthetic address. Deferred past commit (BH-016): this is
     *  a real network call and must not hold a pooled DB connection, nor fire for an event whose
     *  transaction then rolls back. */
    private void emailForUser(UUID userId, String type, Function<User, EmailResult> send) {
        AfterCommit.run(type + " email", () -> {
            Optional<User> user = userRepository.findById(userId);
            if (user.isEmpty() || user.get().isDeleted()
                    || user.get().getEmail() == null || user.get().getEmail().isBlank()) {
                return;
            }
            EmailResult result = send.apply(user.get());
            auditService.recordEvenOnRollback(userId, "EMAIL_SENT", "User", userId, Map.of(
                    "type", type, "provider", result.provider().name(), "success", result.success()));
        });
    }
}
