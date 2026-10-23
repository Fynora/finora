package com.finora.support;

import com.finora.entity.SupportTicket;
import com.finora.exception.ApiException;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.SupportTicketRepository;
import com.finora.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An admin closes a ticket as a duplicate of another ticket the same customer already raised, and
 * the customer is emailed once to say so.
 *
 * <p>Its own class rather than another method on {@link SupportTicketService}: that service is
 * already the home of creation, the queue and the status moves, and this is a distinct use case
 * with its own rules and its own outgoing email.
 *
 * <p><b>What "duplicate" means here.</b> It is an admin's judgement, never a guess by the system:
 * nothing is matched automatically, because two reports with the same subject can be two real
 * problems. The admin names the reference ticket by its number.
 *
 * <p><b>Two admins at once.</b> Both tickets are locked ({@code SELECT ... FOR UPDATE}, lowest id
 * first) before any state is read, so the rules below are checked against state nobody else can
 * change. Without that, A-as-a-duplicate-of-B and B-as-a-duplicate-of-A could both succeed, leaving
 * two closed tickets pointing at each other, and one ticket could be marked, and its customer
 * emailed, twice.
 *
 * <p><b>The rules, each with the reason a wrong answer would hurt:</b>
 * <ul>
 *   <li><b>Same customer.</b> The email tells the customer "you raised this earlier", and the
 *       reference is a ticket they can open in their own app. Pointing at someone else's ticket
 *       would be wrong in both ways, so it is refused.</li>
 *   <li><b>Never a duplicate of a duplicate.</b> A duplicate is closed and points at the ticket
 *       that carries the work. If the reference were itself closed as a duplicate, the customer
 *       would be sent to a dead end, so the admin is told which ticket to point at instead. That is
 *       also what keeps these links from ever forming chains or loops.</li>
 *   <li><b>Not itself, and not already closed.</b> A closed ticket cannot be closed again
 *       ({@link SupportTicket.Status#canTransitionTo}), so it cannot be re-marked and re-emailed.</li>
 * </ul>
 *
 * <p><b>The email</b> goes through the notification outbox, queued inside this transaction so the
 * closure and its email commit together and the email goes only once the ticket really is closed.
 * It is keyed on the ticket, so it can only ever be sent once. Like the ticket acknowledgement it is
 * {@code SECURITY}-category: the customer asked us something, and a switched-off preference must not
 * swallow our answer. It carries the two ticket numbers and nothing the customer wrote.
 *
 * <p>The actor is the first parameter, named {@code actingAdminId}, which
 * {@code AuditActorAttributionTest} requires of anything an admin controller can reach that writes
 * an audit row.
 */
@Service
public class SupportTicketDuplicateService {

    private final SupportTicketRepository ticketRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public SupportTicketDuplicateService(SupportTicketRepository ticketRepository, AuditService auditService,
                                         NotificationService notificationService) {
        this.ticketRepository = ticketRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public SupportTicketDto.Summary markDuplicate(UUID actingAdminId, UUID ticketId, String referenceTicketNumber) {
        String referenceNumber = referenceTicketNumber == null ? "" : referenceTicketNumber.trim().toUpperCase(Locale.ROOT);
        if (referenceNumber.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter the number of the ticket this duplicates.");
        }
        if (!ticketRepository.existsById(ticketId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Support ticket not found");
        }
        UUID referenceId = ticketRepository.findIdByTicketNumber(referenceNumber)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "There is no ticket numbered " + referenceNumber + "."));
        if (referenceId.equals(ticketId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A ticket cannot be a duplicate of itself.");
        }

        // Both rows are locked, lowest id first, BEFORE either one's state is read. Everything
        // below is then decided on state no other admin can change until this transaction ends: two
        // admins can no longer mark two tickets as duplicates of each other, or mark one ticket (and
        // email its customer) twice. A fixed lock order is what stops two of these waiting on each
        // other. See SupportTicketRepository.findByIdForUpdate.
        SupportTicket ticket;
        SupportTicket reference;
        if (ticketId.compareTo(referenceId) < 0) {
            ticket = lockTicket(ticketId);
            reference = lockTicket(referenceId);
        } else {
            reference = lockTicket(referenceId);
            ticket = lockTicket(ticketId);
        }

        if (!reference.getUserId().equals(ticket.getUserId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    referenceNumber + " belongs to a different customer. A ticket can only be a duplicate of "
                            + "another ticket the same customer raised.");
        }
        if (reference.getDuplicateOfTicketId() != null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    referenceNumber + " is itself closed as a duplicate of " + reference.getDuplicateOfTicketNumber()
                            + ". Mark this ticket as a duplicate of " + reference.getDuplicateOfTicketNumber()
                            + " instead.");
        }
        SupportTicket.Status previousStatus = ticket.getStatus();
        if (!previousStatus.canTransitionTo(SupportTicket.Status.CLOSED)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    ticket.getTicketNumber() + " is already closed, so it cannot be marked as a duplicate.");
        }

        ticket.markDuplicateOf(reference);
        ticket.setStatus(SupportTicket.Status.CLOSED);
        ticket.setClosedAt(Instant.now());
        SupportTicket saved = ticketRepository.save(ticket);

        auditService.record(ticket.getUserId(), "SUPPORT_TICKET_MARKED_DUPLICATE", "SupportTicket", ticket.getId(),
                Map.of("actorId", actingAdminId.toString(), "ticketNumber", ticket.getTicketNumber(),
                        "duplicateOfTicketNumber", reference.getTicketNumber(),
                        "previousStatus", previousStatus.name()));
        notificationService.request(NotificationRequest.of(
                ticket.getUserId(),
                NotificationType.SUPPORT_TICKET_DUPLICATE,
                NotificationCategory.SECURITY,
                NotificationPriority.NORMAL,
                "SUPPORT_TICKET_DUPLICATE_" + ticket.getId(),
                Set.of(NotificationChannel.EMAIL),
                Map.of("ticketNumber", ticket.getTicketNumber(), "referenceNumber", reference.getTicketNumber())));
        return SupportTicketDto.Summary.from(saved);
    }

    private SupportTicket lockTicket(UUID id) {
        return ticketRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Support ticket not found"));
    }
}
