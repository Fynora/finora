package com.finora.support;

import com.finora.dto.PagedResponse;
import com.finora.entity.SupportTicket;
import com.finora.entity.SupportTicketAttachment;
import com.finora.entity.SupportTicketInternalNote;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.SupportTicketAttachmentRepository;
import com.finora.repository.SupportTicketInternalNoteRepository;
import com.finora.repository.SupportTicketRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AuditService;
import com.finora.util.AfterCommit;
import com.finora.util.PageBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestration for support tickets: creation, the user's own view, the admin queue, status
 * transitions, claiming, and internal notes.
 *
 * <h2>One route serves two audiences, twice</h2>
 *
 * <p>{@link #getDetail} and {@link #downloadAttachment} are each reachable by a ticket's owner OR
 * by any admin holding {@code SUPPORT_MANAGE} — not by a separate admin-only route. The proposal's
 * §3.6 says exactly this for attachments: "every download goes through an authenticated endpoint
 * ... that re-checks ownership/admin status per request." The revised plan's own endpoint count (11
 * total, five admin-prefixed) has no separate "admin ticket detail" entry, and the security section
 * describes viewing-your-own and admin-viewing-all as two checks on one read path, not two routes —
 * the same shape the attachment sentence states explicitly. Both methods therefore take a
 * {@code callerIsAdmin} flag resolved by the controller from {@code CurrentUser.hasAuthority
 * ("SUPPORT_MANAGE")}, and {@link #fetchForCaller} is the one place that branches on it.
 *
 * <h2>Ownership stays a predicate on the fetch</h2>
 *
 * <p>For the non-admin path, {@link #fetchForCaller} calls {@code
 * SupportTicketRepository.findByIdAndUserId} — the same shape {@code StatementImportService.getFile}
 * uses — rather than {@code findById} plus an {@code if}. Only the admin branch uses a bare
 * {@code findById}, and only because there is no single owner to scope it to.
 *
 * <h2>Audit: owner as subject, actor in metadata</h2>
 *
 * <p>Six events, all string literals passed to {@code AuditService.record} — there is no {@code
 * AuditAction} catalog to extend, matching that service's own documented convention.
 * {@code SUPPORT_TICKET_CREATED} is self-service (the ticket's own {@code userId} is the only
 * actor, and this method is never admin-reachable). The other five are admin actions on a user's
 * own ticket, and follow {@code AccountService.create}'s convention exactly: {@code record}'s first
 * argument stays the ticket owner's {@code userId} (the subject whose data changed), and the acting
 * admin goes into {@code metadata["actorId"]} — never the reverse. Every admin-only-reachable
 * method that writes one of these carries a parameter literally named {@code actingAdminId}, not
 * merely a differently-named admin id: {@code AuditActorAttributionTest} walks the call graph from
 * every {@code /api/v1/admin/**} controller and fails the build on an audited write it can reach
 * without that exact parameter name — the same rule that already caught eight real instances of
 * this bug elsewhere in the codebase (see that test's own doc). {@link #getDetail} and {@link
 * #downloadAttachment} are the exception to the parameter name, not to the underlying rule: both are
 * reachable by an admin AND the ticket's own owner through the single shared route ({@code callerId}
 * already carries whichever one actually called), and their controller is
 * {@code SupportTicketController} under {@code /api/v1/support}, not an admin-rooted path — so
 * {@code AuditActorAttributionTest}'s walk, which starts only from {@code /api/v1/admin/**}
 * controllers, never reaches either at all. {@code SUPPORT_TICKET_VIEWED} and {@code
 * SUPPORT_TICKET_ATTACHMENT_DOWNLOADED} fire only when {@code callerIsAdmin} is true, following the
 * same reasoning {@code AdminHeldImportController.detail} states for auditing every open of a detail
 * view that can surface a user's own free text — a downloaded attachment is raw file content and
 * gets the identical posture.
 *
 * <p>{@code SUPPORT_TICKET_CLAIMED} covers claim, unclaim and a takeover as one event type,
 * carrying {@code previousAdminId} and {@code newAdminId} — both keys always present, including
 * when either is null, so all three shapes reconstruct from one action string. That is also why
 * {@link #recordClaimChange} builds its metadata with a mutable map rather than {@code Map.of(...)}:
 * {@code Map.of} throws on a null value, and a first claim or an unclaim is exactly a null value.
 *
 * <p>No {@code recordEvenOnRollback} anywhere here, on purpose. That variant exists for a write
 * recorded immediately before a later throw in the same transaction rolls it back with everything
 * else (see that method's own doc — a real bug once in {@code DataExportService}). Every audit call
 * below runs only after its corresponding {@code save} has already succeeded, with nothing riskier
 * after it in the same method, so the plain, transaction-joining {@code record} is the correct
 * choice: if the transaction does roll back for some other reason, the audit entry should too,
 * because then the action it describes did not actually happen.
 */
@Service
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);

    private static final int MAX_SUBJECT_LENGTH = 120;
    /** Room for a long explanation (a suspension appeal is at most 4,000 characters plus a short
     *  preamble), not for a pasted document: before this, a 1 MB description was stored. */
    static final int MAX_DESCRIPTION_LENGTH = 10_000;
    /** Staff notes on a ticket, same bound as the description they are written about. */
    static final int MAX_NOTE_LENGTH = 10_000;
    static final int MAX_ACKNOWLEDGEMENTS_PER_DAY = 3;
    private static final Duration ACKNOWLEDGEMENT_WINDOW = Duration.ofHours(24);

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketAttachmentRepository attachmentRepository;
    private final SupportTicketInternalNoteRepository noteRepository;
    private final SupportTicketIdGenerator idGenerator;
    private final ClientIdentity clientIdentity;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final SupportAdminAlertService adminAlerts;
    private final SupportSubmissionLimiter submissionLimiter;
    private final UserRepository userRepository;

    public SupportTicketService(SupportTicketRepository ticketRepository,
                                 SupportTicketAttachmentRepository attachmentRepository,
                                 SupportTicketInternalNoteRepository noteRepository,
                                 SupportTicketIdGenerator idGenerator,
                                 ClientIdentity clientIdentity,
                                 AuditService auditService,
                                 NotificationService notificationService,
                                 SupportAdminAlertService adminAlerts,
                                 SupportSubmissionLimiter submissionLimiter,
                                 UserRepository userRepository) {
        this.submissionLimiter = submissionLimiter;
        this.ticketRepository = ticketRepository;
        this.attachmentRepository = attachmentRepository;
        this.noteRepository = noteRepository;
        this.idGenerator = idGenerator;
        this.clientIdentity = clientIdentity;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.adminAlerts = adminAlerts;
        this.userRepository = userRepository;
    }

    /**
     * Validates the file, if any, BEFORE writing the ticket — a rejected attachment (too large, an
     * unrecognised format) must never leave behind a ticket with no attachment the user thought
     * they were sending, which a validate-after-save order would risk even inside one transaction.
     */
    @Transactional
    public SupportTicketDto.Detail create(UUID userId, String rawCategory, String subject,
                                          String description, MultipartFile file) {
        SupportTicket.Category category = parseCategory(rawCategory);
        if (category.isSuspensionRequest()) {
            // Raised only from the suspended screen, which checks the account is suspended and
            // allows one open request of each kind; the ordinary form must not bypass that.
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown category: " + rawCategory);
        }
        return createTicket(userId, category, subject, description, file);
    }

    /** Privacy workstream 10: an appeal or deletion request from the suspended screen. The caller
     *  ({@code AccountSuspensionService}) has already checked the account is suspended. */
    @Transactional
    public SupportTicketDto.Detail createSuspensionRequest(UUID userId, SupportTicket.Category category,
                                                           String subject, String description) {
        if (!category.isSuspensionRequest()) {
            throw new IllegalArgumentException("Not a suspension request category: " + category);
        }
        return createTicket(userId, category, subject, description, null);
    }

    private SupportTicketDto.Detail createTicket(UUID userId, SupportTicket.Category category, String subject,
                                                 String description, MultipartFile file) {
        String trimmedSubject = requireBounded(subject, "Subject", MAX_SUBJECT_LENGTH);
        String trimmedDescription = requireBounded(description, "Description", MAX_DESCRIPTION_LENGTH);
        SupportAttachmentUpload.Validated validated =
                (file != null && !file.isEmpty()) ? SupportAttachmentUpload.validate(file) : null;
        // One ticket at a time per customer: the daily ceiling and the acknowledgement cap below
        // both count this customer's saved tickets, and without the lock tickets sent at once all
        // counted before any was saved -- 10 at once queued 8 acknowledgement emails against a cap
        // of 3, and 30 at once saved 11 against a ceiling of 10. Held to commit, so the next
        // request counts this ticket. The suspension path already holds this lock (re-entrant).
        SubmissionLock.acquire(userRepository, userId);
        // After validation, so a malformed request still answers 400 and never reads as "limit
        // reached"; before the ticket number is drawn, so a refused request uses none up.
        submissionLimiter.requireTicketAllowance(userId);

        SupportTicket ticket = new SupportTicket();
        ticket.setTicketNumber(idGenerator.next());
        ticket.setUserId(userId);
        ticket.setCategory(category);
        ticket.setSubject(trimmedSubject);
        ticket.setDescription(trimmedDescription);
        ticket.setSource(clientIdentity.platform());
        ticket.setAppVersion(clientIdentity.appVersion());
        SupportTicket saved = ticketRepository.save(ticket);

        List<SupportTicketDto.AttachmentSummary> attachments = List.of();
        if (validated != null) {
            SupportTicketAttachment attachment = new SupportTicketAttachment();
            attachment.setTicketId(saved.getId());
            attachment.setFilename(validated.filename());
            attachment.setContentType(validated.format().contentType());
            attachment.setSizeBytes(validated.content().length);
            attachment.setSha256Hash(validated.sha256Hash());
            attachment.setContent(validated.content());
            SupportTicketAttachment savedAttachment = attachmentRepository.save(attachment);
            attachments = List.of(new SupportTicketDto.AttachmentSummary(savedAttachment.getId(),
                    savedAttachment.getFilename(), savedAttachment.getContentType(), savedAttachment.getSizeBytes()));
        }

        auditService.record(userId, "SUPPORT_TICKET_CREATED", "SupportTicket", saved.getId(),
                Map.of("ticketNumber", saved.getTicketNumber(), "category", category.name()));
        acknowledge(saved);
        // Tell the admins once the ticket is durable, never for one that then rolls back. Hands over
        // what it needs rather than the entity: the alert runs later, on its own thread.
        AfterCommit.run("support ticket admin alert", () -> adminAlerts.alertTicketRaised(
                saved.getId(), saved.getTicketNumber(), saved.getCategory(), saved.getSource(), saved.getCreatedAt()));
        return SupportTicketDto.Detail.from(saved, attachments);
    }

    /**
     * Queues the "we received your request" email in the ticket's own transaction -- the outbox
     * pattern {@code NotificationService.request} documents -- so the ticket and its acknowledgement
     * commit together and the email is sent only once the ticket is durable. {@code request} never
     * throws, so a notification problem cannot fail a ticket the customer just took the trouble to
     * write.
     *
     * <p>SECURITY, which {@code DatabaseNotificationPreferenceResolver} never suppresses: the
     * customer asked us a question and expects a receipt, so a switched-off "financial" preference
     * or a stepped-away account status must not silently swallow it. EMAIL only -- see V287.
     *
     * <p><b>Only to a verified email address (product decision).</b> Password signup never verified
     * email ownership (see V93), so an account can be registered with another person's address; an
     * acknowledgement to an address nobody has proven they own would land in that stranger's inbox,
     * from {@code support@}. A customer whose email is unverified gets no email -- the ticket is
     * created and shown as normal. Nothing is queued, so there is no row to dead-letter either.
     *
     * <p><b>And capped per customer</b>, as a second line of defence: ticket creation is not rate
     * limited, so even a verified account looping tickets would otherwise mail its owner without
     * limit. The first {@link #MAX_ACKNOWLEDGEMENTS_PER_DAY} tickets a customer raises in 24 hours
     * are acknowledged and the rest are not -- the tickets themselves are untouched and all appear
     * in "My Tickets" and the admin queue. Counted in the database, so it holds across instances.
     * The ticket just saved is already in the count.
     */
    private void acknowledge(SupportTicket ticket) {
        boolean emailVerified = userRepository.findById(ticket.getUserId())
                .map(User::isEmailVerified).orElse(false);
        if (!emailVerified) {
            log.info("Not acknowledging support ticket {}: the customer's email is not verified",
                    ticket.getTicketNumber());
            return;
        }
        long raisedToday = ticketRepository.countByUserIdAndCreatedAtAfter(
                ticket.getUserId(), Instant.now().minus(ACKNOWLEDGEMENT_WINDOW));
        if (raisedToday > MAX_ACKNOWLEDGEMENTS_PER_DAY) {
            log.info("Not acknowledging support ticket {}: this customer has raised {} in the last day",
                    ticket.getTicketNumber(), raisedToday);
            return;
        }
        notificationService.request(NotificationRequest.of(
                ticket.getUserId(),
                NotificationType.SUPPORT_TICKET_RECEIVED,
                NotificationCategory.SECURITY,
                NotificationPriority.NORMAL,
                "SUPPORT_TICKET_RECEIVED_" + ticket.getId(),
                Set.of(NotificationChannel.EMAIL),
                // ticketId is for the email's "View your request" button; the template ignores it.
                Map.of("ticketNumber", ticket.getTicketNumber(), "ticketId", ticket.getId().toString())));
    }

    @Transactional(readOnly = true)
    public PagedResponse<SupportTicketDto.OwnSummary> listOwn(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size));
        return PagedResponse.of(
                ticketRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable).map(SupportTicketDto.OwnSummary::from));
    }

    /**
     * Not {@code readOnly = true}, unlike {@link #listOwn}, {@link #adminList} and {@link
     * #listNotes} — {@code AdminHeldImportService.detail} is the precedent: it drops the hint for
     * exactly the same reason, because a read that also writes an audit row is not read-only, and
     * {@code readOnly} is a hint the JDBC driver can act on (it maps to {@code
     * Connection.setReadOnly(true)} in this app's Postgres configuration), not decoration — an
     * INSERT inside a connection actually marked read-only fails at the database, it does not
     * silently no-op. {@link #downloadAttachment} carries the identical exception and reasoning.
     */
    @Transactional
    public SupportTicketDto.Detail getDetail(UUID callerId, boolean callerIsAdmin, UUID ticketId) {
        SupportTicket ticket = fetchForCaller(callerId, callerIsAdmin, ticketId);
        List<SupportTicketDto.AttachmentSummary> attachments = attachmentRepository.findMetadataByTicketId(ticket.getId())
                .stream().map(SupportTicketDto.AttachmentSummary::from).toList();
        if (callerIsAdmin) {
            auditService.record(ticket.getUserId(), "SUPPORT_TICKET_VIEWED", "SupportTicket", ticket.getId(),
                    Map.of("actorId", callerId.toString(), "ticketNumber", ticket.getTicketNumber()));
        }
        return SupportTicketDto.Detail.from(ticket, attachments);
    }

    /** Not {@code readOnly = true} — see {@link #getDetail}'s own doc for why. A downloaded
     *  attachment is raw file content (a screenshot can show as much financial detail as free text),
     *  so an admin download gets the identical audit posture as opening the ticket itself. */
    @Transactional
    public AttachmentDownload downloadAttachment(UUID callerId, boolean callerIsAdmin, UUID ticketId, UUID attachmentId) {
        SupportTicket ticket = fetchForCaller(callerId, callerIsAdmin, ticketId);
        SupportTicketAttachment attachment = attachmentRepository.findByIdAndTicketId(attachmentId, ticket.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attachment not found"));
        if (callerIsAdmin) {
            auditService.record(ticket.getUserId(), "SUPPORT_TICKET_ATTACHMENT_DOWNLOADED", "SupportTicket", ticket.getId(),
                    Map.of("actorId", callerId.toString(), "ticketNumber", ticket.getTicketNumber(),
                            "attachmentId", attachment.getId().toString(), "filename", attachment.getFilename()));
        }
        return new AttachmentDownload(attachment.getFilename(), attachment.getContentType(), attachment.getContent());
    }

    /**
     * @param claim            MINE (tickets {@code callerId} holds), UNCLAIMED, or null for all
     * @param mayNameCustomers whether the caller holds USER_VIEW -- see {@link SupportTicketDto.AdminSummary}
     */
    @Transactional(readOnly = true)
    public PagedResponse<SupportTicketDto.AdminSummary> adminList(SupportTicket.Status status, SupportTicket.Category category,
                                                                   String q, SupportTicketDto.ClaimFilter claim, UUID callerId,
                                                                   boolean mayNameCustomers, int page, int size) {
        Pageable pageable = PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size));
        // Blank (not just null) reads as "no search" -- a caller sending q="" (an untouched
        // search box submitted along with a real filter change, say) must not silently exclude
        // every ticket whose number/subject fails to contain the empty string... which would
        // actually match everything, harmlessly, but passing it through as literally empty is
        // still the wrong contract to expose: "no search" should mean the WHERE clause is skipped
        // entirely, not "matched trivially."
        String query = (q == null || q.isBlank()) ? null : q.trim();
        UUID claimedBy = claim == SupportTicketDto.ClaimFilter.MINE ? callerId : null;
        boolean unclaimedOnly = claim == SupportTicketDto.ClaimFilter.UNCLAIMED;
        var tickets = ticketRepository.findForAdmin(status, category, query, claimedBy, unclaimedOnly, pageable);
        Map<UUID, User> people = peopleNamedIn(tickets.getContent().stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getUserId(), t.getClaimedByAdminId())).toList());
        return PagedResponse.of(tickets.map(t -> {
            User customer = nameable(people.get(t.getUserId()), mayNameCustomers);
            User claimer = t.getClaimedByAdminId() != null ? nameable(people.get(t.getClaimedByAdminId()), mayNameCustomers) : null;
            return new SupportTicketDto.AdminSummary(t.getId(), t.getTicketNumber(), t.getUserId(),
                    customer != null ? customer.getFullName() : null, customer != null ? customer.getEmail() : null,
                    t.getCategory(), t.getSubject(), t.getStatus(), t.getClaimedByAdminId(),
                    claimer != null ? claimer.getFullName() : null,
                    t.getClaimedByAdminId() != null && t.getClaimedByAdminId().equals(callerId),
                    t.getCreatedAt(), t.getUpdatedAt(),
                    t.getDuplicateOfTicketId(), t.getDuplicateOfTicketNumber());
        }));
    }

    /** The admin's view of one ticket: what {@link #getDetail} shows its owner, plus the customer and
     *  the claiming admin, named under {@link SupportTicketDto.AdminDetail}'s rules. Records the view
     *  as SUPPORT_TICKET_VIEWED with the admin as actor, as an admin opening a ticket always has. Not
     *  readOnly, for the audit write -- see {@link #getDetail}. */
    @Transactional
    public SupportTicketDto.AdminDetail adminDetail(UUID actingAdminId, UUID ticketId, boolean mayNameCustomers) {
        SupportTicket ticket = requireTicket(ticketId);
        List<SupportTicketDto.AttachmentSummary> attachments = attachmentRepository.findMetadataByTicketId(ticket.getId())
                .stream().map(SupportTicketDto.AttachmentSummary::from).toList();
        auditService.record(ticket.getUserId(), "SUPPORT_TICKET_VIEWED", "SupportTicket", ticket.getId(),
                Map.of("actorId", actingAdminId.toString(), "ticketNumber", ticket.getTicketNumber()));
        SupportTicketDto.Detail d = SupportTicketDto.Detail.from(ticket, attachments);
        // The customer's Detail carries no claimer; who holds the ticket is read from the ticket.
        UUID claimedBy = ticket.getClaimedByAdminId();
        Map<UUID, User> people = peopleNamedIn(java.util.Arrays.asList(d.userId(), claimedBy));
        User customer = nameable(people.get(d.userId()), mayNameCustomers);
        User claimer = claimedBy != null ? nameable(people.get(claimedBy), mayNameCustomers) : null;
        return new SupportTicketDto.AdminDetail(d.id(), d.ticketNumber(), d.userId(),
                customer != null ? customer.getFullName() : null, customer != null ? customer.getEmail() : null,
                d.category(), d.subject(), d.description(), d.status(), d.source(), d.appVersion(),
                claimedBy, claimer != null ? claimer.getFullName() : null,
                claimedBy != null && claimedBy.equals(actingAdminId),
                d.resolvedAt(), d.closedAt(), d.createdAt(), d.updatedAt(), d.attachments(),
                d.duplicateOfTicketId(), d.duplicateOfTicketNumber());
    }

    /** Every account named in a page, in one query. Nulls (no claimer) are skipped. */
    private Map<UUID, User> peopleNamedIn(java.util.Collection<UUID> ids) {
        java.util.Set<UUID> wanted = new java.util.HashSet<>();
        for (UUID id : ids) if (id != null) wanted.add(id);
        if (wanted.isEmpty()) return Map.of();
        Map<UUID, User> byId = new java.util.HashMap<>();
        for (User u : userRepository.findAllById(wanted)) byId.put(u.getId(), u);
        return byId;
    }

    /** Staff are always named; a customer only for a caller who may look customers up (USER_VIEW). */
    private static User nameable(User user, boolean mayNameCustomers) {
        if (user == null) return null;
        return User.SCOPE_ADMIN.equals(user.getAccountScope()) || mayNameCustomers ? user : null;
    }

    /**
     * Moves a ticket on, and when it is resolved or closed and the admin asks for it, emails the
     * customer -- with the admin's message if one was written.
     *
     * <p>The ticket row is locked first, as {@code SupportTicketDuplicateService} does: two admins
     * finishing the same ticket at once would otherwise both pass {@code canTransitionTo} and both
     * email the customer. With the lock the second waits, sees the ticket already finished, and is
     * refused with 409.
     *
     * <p>The email goes only to a verified address, for the reason {@link #acknowledge} gives (an
     * unproven address may be a stranger's). When it does not go, the result says why, so the admin
     * can reach the customer another way. The audit row records whether the customer was emailed
     * and the message they were sent.
     */
    @Transactional
    public SupportTicketDto.StatusChangeResult updateStatus(UUID actingAdminId, UUID ticketId, SupportTicket.Status newStatus,
                                                            boolean notifyCustomer, String message) {
        boolean finishing = newStatus == SupportTicket.Status.RESOLVED || newStatus == SupportTicket.Status.CLOSED;
        String customerMessage = message == null || message.isBlank() ? null : message.strip();
        if (!finishing && (notifyCustomer || customerMessage != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The customer is told only when a ticket is resolved or closed.");
        }
        if (customerMessage != null && !notifyCustomer) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A message is sent only when the customer is told.");
        }
        if (customerMessage != null && customerMessage.length() > SupportTicketDto.MAX_CUSTOMER_MESSAGE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The message to the customer must be at most " + SupportTicketDto.MAX_CUSTOMER_MESSAGE_LENGTH + " characters.");
        }
        SupportTicket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Support ticket not found"));
        SupportTicket.Status previousStatus = ticket.getStatus();
        if (!previousStatus.canTransitionTo(newStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot move a ticket from " + previousStatus + " to " + newStatus + ".");
        }
        ticket.setStatus(newStatus);
        Instant now = Instant.now();
        if (newStatus == SupportTicket.Status.RESOLVED) {
            ticket.setResolvedAt(now);
        } else if (newStatus == SupportTicket.Status.CLOSED) {
            ticket.setClosedAt(now);
        }
        SupportTicket saved = ticketRepository.save(ticket);

        String notEmailedReason = null;
        if (notifyCustomer) {
            notEmailedReason = tellCustomerTicketFinished(saved, customerMessage);
        }
        boolean customerEmailed = notifyCustomer && notEmailedReason == null;

        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("actorId", actingAdminId.toString());
        metadata.put("ticketNumber", ticket.getTicketNumber());
        metadata.put("previousStatus", previousStatus.name());
        metadata.put("newStatus", newStatus.name());
        if (finishing) metadata.put("customerEmailed", customerEmailed);
        if (customerEmailed && customerMessage != null) metadata.put("messageToCustomer", customerMessage);
        auditService.record(ticket.getUserId(), "SUPPORT_TICKET_STATUS_CHANGED", "SupportTicket", ticket.getId(), metadata);
        return new SupportTicketDto.StatusChangeResult(SupportTicketDto.Summary.from(saved), customerEmailed, notEmailedReason);
    }

    /**
     * Queues the resolved/closed email in the status change's own transaction (the outbox pattern
     * {@link #acknowledge} uses). Returns null when queued, else why it was not.
     *
     * <p>SECURITY category and EMAIL only, as the acknowledgement: the customer asked us something
     * and this is our answer, so a switched-off preference must not swallow it. Keyed per ticket and
     * per outcome; a ticket can only be finished once (RESOLVED and CLOSED have no way out), so this
     * sends at most once.
     */
    private String tellCustomerTicketFinished(SupportTicket ticket, String customerMessage) {
        boolean emailVerified = userRepository.findById(ticket.getUserId())
                .map(User::isEmailVerified).orElse(false);
        if (!emailVerified) {
            log.info("Not emailing the outcome of support ticket {}: the customer's email is not verified",
                    ticket.getTicketNumber());
            return "The customer's email address is not verified, so no email was sent.";
        }
        NotificationType type = ticket.getStatus() == SupportTicket.Status.RESOLVED
                ? NotificationType.SUPPORT_TICKET_RESOLVED : NotificationType.SUPPORT_TICKET_CLOSED;
        Map<String, String> params = new java.util.HashMap<>();
        params.put("ticketNumber", ticket.getTicketNumber());
        params.put("ticketId", ticket.getId().toString());
        if (customerMessage != null) params.put("message", customerMessage);
        notificationService.request(NotificationRequest.of(
                ticket.getUserId(), type, NotificationCategory.SECURITY, NotificationPriority.NORMAL,
                type.name() + "_" + ticket.getId(), Set.of(NotificationChannel.EMAIL), params));
        return null;
    }

    @Transactional(readOnly = true)
    public List<SupportTicketDto.NoteDto> listNotes(UUID ticketId) {
        requireTicket(ticketId);
        List<SupportTicketInternalNote> notes = noteRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        // Note authors are admins (staff), so always named.
        Map<UUID, User> authors = peopleNamedIn(notes.stream().map(SupportTicketInternalNote::getAdminId).toList());
        return notes.stream().map(n -> SupportTicketDto.NoteDto.from(n,
                authors.get(n.getAdminId()) != null ? authors.get(n.getAdminId()).getFullName() : null)).toList();
    }

    /** The note body is deliberately not copied into the audit metadata — the note table is already
     *  append-only and admin-scoped, so duplicating free text into a second store widens the surface
     *  for no gain (V147's own reasoning for the note table itself). */
    @Transactional
    public SupportTicketDto.NoteDto addNote(UUID actingAdminId, UUID ticketId, String note) {
        SupportTicket ticket = requireTicket(ticketId);
        SupportTicketInternalNote entity = new SupportTicketInternalNote();
        entity.setTicketId(ticketId);
        entity.setAdminId(actingAdminId);
        entity.setNote(requireBounded(note, "Note", MAX_NOTE_LENGTH));
        SupportTicketInternalNote saved = noteRepository.save(entity);
        auditService.record(ticket.getUserId(), "SUPPORT_TICKET_NOTE_ADDED", "SupportTicket", ticketId,
                Map.of("actorId", actingAdminId.toString(), "ticketNumber", ticket.getTicketNumber()));
        String author = userRepository.findById(actingAdminId).map(User::getFullName).orElse(null);
        return SupportTicketDto.NoteDto.from(saved, author);
    }

    /** Always succeeds, including on an already-claimed ticket — see {@link SupportTicket#getClaimedByAdminId()}'s
     *  own doc: a claim warns, it never blocks, so one admin's absence cannot freeze a customer's ticket. */
    @Transactional
    public SupportTicketDto.Summary claim(UUID actingAdminId, UUID ticketId) {
        SupportTicket ticket = requireTicket(ticketId);
        UUID previousAdminId = ticket.getClaimedByAdminId();
        ticket.setClaimedByAdminId(actingAdminId);
        SupportTicket saved = ticketRepository.save(ticket);
        recordClaimChange(actingAdminId, ticket, previousAdminId, actingAdminId);
        return SupportTicketDto.Summary.from(saved);
    }

    /** Releases a claim back to unclaimed. Any admin may call this, not only the one who claimed
     *  it — so a ticket nobody is actively working can visibly go back to the queue. */
    @Transactional
    public SupportTicketDto.Summary unclaim(UUID actingAdminId, UUID ticketId) {
        SupportTicket ticket = requireTicket(ticketId);
        UUID previousAdminId = ticket.getClaimedByAdminId();
        ticket.setClaimedByAdminId(null);
        SupportTicket saved = ticketRepository.save(ticket);
        recordClaimChange(actingAdminId, ticket, previousAdminId, null);
        return SupportTicketDto.Summary.from(saved);
    }

    /** The single write point for {@code SUPPORT_TICKET_CLAIMED} — claim, unclaim and a takeover
     *  all pass through here so the three shapes stay reconstructable from one action string.
     *  {@code actingAdminId} is who performed THIS action, which is not always {@code newAdminId}:
     *  on an unclaim the actor releases the ticket, they don't become its new claimant. */
    private void recordClaimChange(UUID actingAdminId, SupportTicket ticket, UUID previousAdminId, UUID newAdminId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("actorId", actingAdminId.toString());
        metadata.put("ticketNumber", ticket.getTicketNumber());
        metadata.put("previousAdminId", previousAdminId == null ? null : previousAdminId.toString());
        metadata.put("newAdminId", newAdminId == null ? null : newAdminId.toString());
        auditService.record(ticket.getUserId(), "SUPPORT_TICKET_CLAIMED", "SupportTicket", ticket.getId(), metadata);
    }

    /** Owner-scoped for a regular caller, unscoped for an admin — the one branch point both
     *  dual-audience reads share. */
    private SupportTicket fetchForCaller(UUID callerId, boolean callerIsAdmin, UUID ticketId) {
        if (callerIsAdmin) {
            return ticketRepository.findById(ticketId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Support ticket not found"));
        }
        return ticketRepository.findByIdAndUserId(ticketId, callerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Support ticket not found"));
    }

    private SupportTicket requireTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Support ticket not found"));
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " is required.");
        }
        return value.trim();
    }

    private static String requireBounded(String value, String label, int maxLength) {
        String trimmed = requireNonBlank(value, label);
        if (trimmed.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " must be " + maxLength + " characters or fewer.");
        }
        return trimmed;
    }

    /** {@code category} arrives from a multipart form field, so unlike the JSON endpoints there is
     *  no Jackson enum conversion to lean on — this is that conversion's manual equivalent. Lives
     *  here rather than on the controller: {@code LayerDependencyDirectionTest
     *  .controllersNeverReturnAnEntity} rejects any {@code *Controller} method whose return type
     *  touches {@code com.finora.entity}, private helpers included. */
    private static SupportTicket.Category parseCategory(String raw) {
        try {
            return SupportTicket.Category.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown category: " + raw);
        }
    }

    public record AttachmentDownload(String filename, String contentType, byte[] content) {}
}
