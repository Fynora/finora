package com.finora.support;

import io.swagger.v3.oas.annotations.media.Schema;
import com.finora.entity.ClientPlatform;
import com.finora.entity.SupportTicket;
import com.finora.entity.SupportTicketInternalNote;
import com.finora.repository.SupportTicketAttachmentRepository.AttachmentMetadata;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The support-ticket API contract — one file, several nested records, following {@code ImportDto}
 *  and {@code StatementImportDto}'s own convention for a feature with more than one shape. */
public final class SupportTicketDto {

    private SupportTicketDto() {}

    /** The admin list-row shape (claim, unclaim, mark duplicate, a status change): everything a queue
     *  row needs, and nothing that requires a second query to produce. Deliberately excludes
     *  {@code description} — the same reason {@code HeldImportDto}'s list shape excludes the raw
     *  parser error. Never sent to a customer: it carries {@code claimedByAdminId}, a staff
     *  account's id — the customer's "My Tickets" list is {@link OwnSummary}. */
    @Schema(name = "SupportTicketSummary")
    public record Summary(
            UUID id,
            String ticketNumber,
            UUID userId,
            SupportTicket.Category category,
            String subject,
            SupportTicket.Status status,
            UUID claimedByAdminId,
            Instant createdAt,
            Instant updatedAt,
            UUID duplicateOfTicketId,
            String duplicateOfTicketNumber
    ) {
        public static Summary from(SupportTicket ticket) {
            return new Summary(ticket.getId(), ticket.getTicketNumber(), ticket.getUserId(),
                    ticket.getCategory(), ticket.getSubject(), ticket.getStatus(),
                    ticket.getClaimedByAdminId(), ticket.getCreatedAt(), ticket.getUpdatedAt(),
                    ticket.getDuplicateOfTicketId(), ticket.getDuplicateOfTicketNumber());
        }
    }

    /** The customer's own list row ("My Tickets"): {@link Summary} without {@code claimedByAdminId}.
     *  Who on the staff holds a ticket is Fynora's own working detail, and the id is a staff
     *  account's — it used to reach the customer here (break-test 2026-11-13). */
    @Schema(name = "SupportTicketOwnSummary")
    public record OwnSummary(
            UUID id,
            String ticketNumber,
            UUID userId,
            SupportTicket.Category category,
            String subject,
            SupportTicket.Status status,
            Instant createdAt,
            Instant updatedAt,
            UUID duplicateOfTicketId,
            String duplicateOfTicketNumber
    ) {
        public static OwnSummary from(SupportTicket ticket) {
            return new OwnSummary(ticket.getId(), ticket.getTicketNumber(), ticket.getUserId(),
                    ticket.getCategory(), ticket.getSubject(), ticket.getStatus(),
                    ticket.getCreatedAt(), ticket.getUpdatedAt(),
                    ticket.getDuplicateOfTicketId(), ticket.getDuplicateOfTicketNumber());
        }
    }

    /** Single-ticket shape for the customer, including the attachment list: the ticket detail and
     *  create routes, and {@code support_tickets.json} in the data export. No staff account's id —
     *  who holds the ticket is on {@link AdminDetail} only (break-test 2026-11-13). */
    @Schema(name = "SupportTicketDetail")
    public record Detail(
            UUID id,
            String ticketNumber,
            UUID userId,
            SupportTicket.Category category,
            String subject,
            String description,
            SupportTicket.Status status,
            ClientPlatform source,
            String appVersion,
            Instant resolvedAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt,
            List<AttachmentSummary> attachments,
            UUID duplicateOfTicketId,
            String duplicateOfTicketNumber
    ) {
        public static Detail from(SupportTicket ticket, List<AttachmentSummary> attachments) {
            return new Detail(ticket.getId(), ticket.getTicketNumber(), ticket.getUserId(),
                    ticket.getCategory(), ticket.getSubject(), ticket.getDescription(), ticket.getStatus(),
                    ticket.getSource(), ticket.getAppVersion(),
                    ticket.getResolvedAt(), ticket.getClosedAt(), ticket.getCreatedAt(), ticket.getUpdatedAt(),
                    attachments, ticket.getDuplicateOfTicketId(), ticket.getDuplicateOfTicketNumber());
        }
    }

    /** Metadata only — never the bytes. Mirrors {@code AttachmentMetadata}, the projection that
     *  keeps a ticket-detail read from pulling every attachment's content into memory. */
    public record AttachmentSummary(UUID id, String filename, String contentType, long sizeBytes) {
        public static AttachmentSummary from(AttachmentMetadata metadata) {
            return new AttachmentSummary(metadata.getId(), metadata.getFilename(),
                    metadata.getContentType(), metadata.getSizeBytes());
        }
    }

    /**
     * An admin moving a ticket on. {@code notifyCustomer} and {@code message} apply only when the new
     * status is RESOLVED or CLOSED: the customer is emailed (a verified address only) and the message,
     * if any, is included in that email. Both optional, so a status-only request from an older
     * client still works and sends nothing.
     */
    public record UpdateStatusRequest(
            @NotNull(message = "status is required") SupportTicket.Status status,
            Boolean notifyCustomer,
            @jakarta.validation.constraints.Size(max = MAX_CUSTOMER_MESSAGE_LENGTH,
                    message = "The message to the customer must be at most 2000 characters")
            String message) {}

    /** The queue's claim filter: tickets the calling admin holds, or tickets nobody holds. */
    public enum ClaimFilter { MINE, UNCLAIMED }

    /** Longest message an admin can put in the resolved/closed email. */
    public static final int MAX_CUSTOMER_MESSAGE_LENGTH = 2000;

    /** The result of a status change: the ticket, and whether the customer was emailed. When the
     *  admin asked to tell the customer but no email went, {@code notEmailedReason} says why, so
     *  the admin can reach them another way. */
    public record StatusChangeResult(Summary ticket, boolean customerEmailed, String notEmailedReason) {}

    /**
     * A queue row as admins see it: {@link Summary} plus who raised it and which admin holds it,
     * named. Admin-only -- the customer-facing endpoints keep returning {@link Summary}, so no admin
     * name ever reaches a customer. {@code claimedByYou} is whether the calling admin holds it.
     * The customer's name and email are null for a caller without
     * USER_VIEW (who could never look a customer up) and for a deleted account.
     */
    @Schema(name = "AdminSupportTicketSummary")
    public record AdminSummary(
            UUID id,
            String ticketNumber,
            UUID userId,
            String customerName,
            String customerEmail,
            SupportTicket.Category category,
            String subject,
            SupportTicket.Status status,
            UUID claimedByAdminId,
            String claimedByAdminName,
            boolean claimedByYou,
            Instant createdAt,
            Instant updatedAt,
            UUID duplicateOfTicketId,
            String duplicateOfTicketNumber
    ) {}

    /** {@link Detail} as admins see it, with the same naming rules as {@link AdminSummary}. */
    @Schema(name = "AdminSupportTicketDetail")
    public record AdminDetail(
            UUID id,
            String ticketNumber,
            UUID userId,
            String customerName,
            String customerEmail,
            SupportTicket.Category category,
            String subject,
            String description,
            SupportTicket.Status status,
            ClientPlatform source,
            String appVersion,
            UUID claimedByAdminId,
            String claimedByAdminName,
            boolean claimedByYou,
            Instant resolvedAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt,
            List<AttachmentSummary> attachments,
            UUID duplicateOfTicketId,
            String duplicateOfTicketNumber
    ) {}

    /** The ticket number (SUP-000042) of the ticket this one duplicates, as an admin reads it off the queue. */
    public record MarkDuplicateRequest(
            @NotBlank(message = "referenceTicketNumber is required") String referenceTicketNumber) {}

    /** An internal note, as returned to an admin. Never constructed from, or exposed to, a
     *  user-facing endpoint — see {@link SupportTicketInternalNote}'s own doc for why that must
     *  stay structurally true rather than merely conventionally true. */
    /** Admin-only. {@code adminName} is null when the writing admin's account no longer exists. */
    public record NoteDto(UUID id, UUID adminId, String adminName, String note, Instant createdAt) {
        public static NoteDto from(SupportTicketInternalNote note, String adminName) {
            return new NoteDto(note.getId(), note.getAdminId(), adminName, note.getNote(), note.getCreatedAt());
        }
    }

    public record AddNoteRequest(@NotBlank(message = "note is required") String note) {}
}
