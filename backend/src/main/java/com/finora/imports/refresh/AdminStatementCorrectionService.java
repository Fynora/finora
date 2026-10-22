package com.finora.imports.refresh;

import com.finora.dto.StatementRefreshOutcome;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementRefreshRun;
import com.finora.exception.ApiException;
import com.finora.imports.original.OriginalStatementViewService;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementRefreshPreviewRepository.PendingStatement;
import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An admin applying a statement correction on the user's behalf -- the same refresh the user's own
 * "Update" button runs ({@link StatementRefreshService#refresh}), started from the admin portal.
 *
 * <p>Why it exists: the background dry run finds the correction and offers it to the user, but a
 * user who never opens the app keeps the wrong figures. A real case (2026-10-22): a card statement
 * read under an older parser stored every payment to the card as a spend, which overstated what the
 * user owed by lakhs until they pressed Update. Sid decided the same day that staff may apply such a
 * correction for the user.
 *
 * <p>Guards, so this can only do what the user's own button would:
 * <ul>
 *   <li>Only a correction the dry run has already checked and marked CHANGES -- never a statement
 *       that needs a password, one held for review, or one whose last refresh failed since.</li>
 *   <li>The refresh itself re-checks everything (trust gate, too many rows removed, a different
 *       account) and holds instead of applying, exactly as for the user.</li>
 *   <li>A typed reason, 15 to 500 characters, the rule "View original" uses. It goes to the audit
 *       entries, filed under the user with the admin as actorId, never to the user.</li>
 *   <li>The user is told afterwards, by push and email, what was corrected: counts and the account
 *       name only. The app has no screen to reopen a refresh summary later, so the message itself
 *       says what changed.</li>
 * </ul>
 */
@Service
public class AdminStatementCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(AdminStatementCorrectionService.class);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public record WaitingCorrection(UUID statementImportId, String fileName, String accountName,
                                    LocalDate periodStart, LocalDate periodEnd, int rowsChanged,
                                    int rowsAdded, int rowsRemoved, int factsChanged, Instant checkedAt) {}

    public record AppliedCorrection(UUID statementImportId, String status, int rowsChanged, int rowsAdded,
                                    int rowsRemoved, int factsChanged, BigDecimal balanceChange,
                                    String reason, UUID runId, boolean userNotified) {}

    private final StatementRefreshUserService userService;
    private final StatementRefreshService refreshService;
    private final StatementImportRepository statementImportRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public AdminStatementCorrectionService(StatementRefreshUserService userService,
                                           StatementRefreshService refreshService,
                                           StatementImportRepository statementImportRepository,
                                           AuditService auditService,
                                           NotificationService notificationService) {
        this.userService = userService;
        this.refreshService = refreshService;
        this.statementImportRepository = statementImportRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    /** The user's statements with a checked correction waiting, newest check first. */
    public List<WaitingCorrection> waiting(UUID userId) {
        return userService.correctionsReady(userId).stream().map(AdminStatementCorrectionService::view).toList();
    }

    /** Applies the waiting correction for one statement, for its owner. */
    public AppliedCorrection apply(UUID actingAdminId, UUID statementId, String rawReason) {
        String reason = OriginalStatementViewService.validReason(rawReason);
        StatementImport statement = statementImportRepository.findById(statementId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Statement not found"));
        UUID ownerId = statement.getUserId();
        PendingStatement pending = userService.correctionsReady(ownerId).stream()
                .filter(p -> statementId.equals(p.getStatementImportId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "No checked correction is waiting for this statement. Only a correction the "
                                + "app has already checked, and that needs no password, can be applied."));

        // Filed under the user whose statement it is, like every other change to their data
        // (AccountService), with the admin as actorId. audit_logs never reaches the user's data export,
        // so the reason stays internal. Recorded before anything changes, so a correction can never
        // land without an entry naming who started it and why -- even if the process dies between the
        // refresh and the result entry below.
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("actorId", actingAdminId.toString());
        requested.put("reason", reason);
        auditService.record(ownerId, "STATEMENT_CORRECTION_REQUESTED_BY_ADMIN", "StatementImport", statementId,
                requested);

        StatementRefreshOutcome outcome;
        try {
            outcome = refreshService.refreshForOwnerByAdmin(ownerId, statementId, actingAdminId);
        } catch (RuntimeException e) {
            // Deleted, replaced or its account deleted since the check: nothing was applied. The
            // request above still needs its outcome. The class name only -- a message can quote text.
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("actorId", actingAdminId.toString());
            failed.put("status", "NOT_APPLIED");
            failed.put("error", e.getClass().getSimpleName());
            auditService.record(ownerId, "STATEMENT_CORRECTION_APPLIED_BY_ADMIN", "StatementImport", statementId, failed);
            throw e;
        }

        boolean applied = outcome.status() == StatementRefreshRun.Status.APPLIED;
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("actorId", actingAdminId.toString());
        audit.put("reason", reason);
        audit.put("status", outcome.status().name());
        audit.put("rowsChanged", outcome.rowsChanged());
        audit.put("rowsAdded", outcome.rowsAdded());
        audit.put("rowsRemoved", outcome.rowsRemoved());
        if (outcome.runId() != null) audit.put("runId", outcome.runId().toString());
        if (outcome.reason() != null) audit.put("refreshReason", outcome.reason());
        auditService.record(ownerId, "STATEMENT_CORRECTION_APPLIED_BY_ADMIN", "StatementImport", statementId, audit);

        boolean notified = applied && notifyUser(ownerId, outcome, pending);
        return new AppliedCorrection(statementId, outcome.status().name(), outcome.rowsChanged(),
                outcome.rowsAdded(), outcome.rowsRemoved(), outcome.factsChanged(), outcome.balanceChange(),
                outcome.reason(), outcome.runId(), notified);
    }

    /**
     * What the user is told changed, from the run's counts only. Every matching pass of
     * {@link StatementRefreshDiff} requires the type to be equal, so a transaction whose direction a
     * correction flips is never paired: it is removed and its corrected reading added. Each removed
     * row matched by an added one is therefore said as "replaced with a corrected reading"; only the
     * surplus on either side is said as added or removed, and changed rows as corrected.
     */
    static String message(int rowsChanged, int rowsAdded, int rowsRemoved, int factsChanged,
                          String accountName, LocalDate periodStart, LocalDate periodEnd) {
        String where = accountName == null || accountName.isBlank() ? "one of your statements" : "your " + accountName + " statement";
        String period = periodStart != null && periodEnd != null
                ? " (" + PERIOD.format(periodStart) + " to " + PERIOD.format(periodEnd) + ")" : "";
        int replaced = Math.min(rowsAdded, rowsRemoved);
        List<String> parts = new java.util.ArrayList<>();
        if (rowsChanged > 0) parts.add(count(rowsChanged) + " corrected");
        if (replaced > 0) parts.add(count(replaced) + " replaced with a corrected reading");
        if (rowsAdded > replaced) parts.add(count(rowsAdded - replaced) + " added");
        if (rowsRemoved > replaced) parts.add(count(rowsRemoved - replaced) + " removed");
        String what = parts.isEmpty() ? "" : " " + String.join(", ", parts) + ".";
        String facts = factsChanged > 0 ? " The statement's opening or closing balance was also updated." : "";
        return "We improved how Fynora reads bank statements and our support team applied the correction to "
                + where + period + "." + what + facts
                + " Any transaction you edited keeps your changes. Open Statements in Fynora to review it.";
    }

    private static String count(int n) {
        return n == 1 ? "1 transaction" : n + " transactions";
    }

    private boolean notifyUser(UUID ownerId, StatementRefreshOutcome outcome, PendingStatement pending) {
        try {
            notificationService.request(NotificationRequest.of(
                    ownerId,
                    NotificationType.STATEMENT_CORRECTED_BY_SUPPORT,
                    // SECURITY, which a user cannot switch off (DatabaseNotificationPreferenceResolver):
                    // staff changed their financial records, and the privacy policy promises they are
                    // told -- the same reasoning as the "opened to fix a reading problem" notice.
                    NotificationCategory.SECURITY,
                    NotificationPriority.NORMAL,
                    "STATEMENT_CORRECTED_" + outcome.runId(),
                    Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                    Map.of("message", message(outcome.rowsChanged(), outcome.rowsAdded(), outcome.rowsRemoved(),
                            outcome.factsChanged(), pending.getAccountName(), pending.getPeriodStart(),
                            pending.getPeriodEnd()))));
            return true;
        } catch (RuntimeException e) {
            // The correction is applied and audited; a lost notice must not report it as failed.
            log.warn("Could not queue the statement-corrected notice for user {}: {}", ownerId, e.getClass().getSimpleName());
            return false;
        }
    }

    private static WaitingCorrection view(PendingStatement p) {
        return new WaitingCorrection(p.getStatementImportId(), p.getFileName(), p.getAccountName(),
                p.getPeriodStart(), p.getPeriodEnd(), p.getRowsChanged(), p.getRowsAdded(),
                p.getRowsRemoved() + p.getRowsConflicting(), p.getFactsChanged(), p.getComputedAt());
    }
}
